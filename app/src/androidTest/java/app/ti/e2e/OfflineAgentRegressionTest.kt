package app.ti.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ti.agent.AgentRunManager
import app.ti.agent.AgentRunState
import app.ti.agent.RepoFiles
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.RepositoryEntity
import app.ti.data.RepositorySource
import app.ti.data.SessionEntity
import app.ti.data.SettingEntity
import app.ti.data.TiDao
import app.ti.data.TiDatabase
import app.ti.git.GitService
import app.ti.llm.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real agent, Room and Git regression coverage with only the model served over loopback. */
@RunWith(AndroidJUnit4::class)
class OfflineAgentRegressionTest {
    private lateinit var database: TiDatabase
    private lateinit var dao: TiDao
    private lateinit var manager: AgentRunManager
    private lateinit var server: MockWebServer
    private lateinit var temporaryRoot: File
    private var agentJob: Job? = null
    private val replies = LinkedBlockingQueue<MockResponse>()
    private val git = GitService()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        temporaryRoot = File(context.cacheDir, "offline-agent-${UUID.randomUUID()}")
        check(temporaryRoot.mkdirs())
        database = Room.inMemoryDatabaseBuilder(context, TiDatabase::class.java).build()
        dao = database.dao()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = replies.poll()
                    ?: MockResponse().setResponseCode(400).setBody("Unexpected offline model request")
            }
            start()
        }
        dao.saveProvider(
            ProviderEntity(PROVIDER_ID, "Offline model", server.url("/v1").toString().trimEnd('/'), "offline-test-key", "", 0),
        )
        dao.saveModel(ModelEntity(MODEL_ID, PROVIDER_ID, "offline-test-model", contextTokens = 100_000))
        dao.saveSetting(SettingEntity("git_author_name", AUTHOR_NAME))
        dao.saveSetting(SettingEntity("git_author_email", AUTHOR_EMAIL))
        val job = SupervisorJob()
        agentJob = job
        manager = AgentRunManager(dao, OpenAiClient(), RepoFiles(), git, CoroutineScope(job + Dispatchers.Default))
    }

    @After
    fun tearDown() {
        try {
            runBlocking { agentJob?.cancelAndJoin() }
        } finally {
            try {
                if (::server.isInitialized) server.shutdown()
            } finally {
                if (::database.isInitialized) database.close()
                if (::temporaryRoot.isInitialized) temporaryRoot.deleteRecursively()
            }
        }
    }

    @Test
    fun localRepositoryAgentWritesAndCommitsOnlyAfterApproval() = runBlocking {
        val originDirectory = File(temporaryRoot, "origin")
        check(originDirectory.mkdirs())
        val originalHead = Git.init().setDirectory(originDirectory).call().use { origin ->
            File(originDirectory, "README.md").writeText("# Offline agent regression\n")
            origin.add().addFilepattern(".").call()
            origin.commit().setMessage("Initial local fixture")
                .setAuthor(AUTHOR_NAME, AUTHOR_EMAIL)
                .setCommitter(AUTHOR_NAME, AUTHOR_EMAIL)
                .call().name
        }
        val workDirectory = File(temporaryRoot, "work")
        // A filesystem-only origin lets the test also prove that committing never pushes.
        git.clone(originDirectory.toURI().toString(), workDirectory, "", "")
        val repository = RepositoryEntity(
            id = REPOSITORY_ID,
            name = "Offline local repository",
            remoteUrl = null,
            localPath = workDirectory.absolutePath,
            username = "",
            token = "",
            createdAt = 0,
            source = RepositorySource.LOCAL,
        )
        dao.saveRepository(repository)
        saveSession(repository.id)
        val originalRemote = Git.open(workDirectory).use { it.repository.config.getString("remote", "origin", "url") }
        replies += toolResponse("write-local-file", "write_file", """{"path":"offline.txt","content":"hello from the offline agent\n"}""")
        replies += toolResponse("commit-local-file", "git_commit", """{"message":"Offline agent commit"}""")
        replies += textResponse("Created offline.txt and committed it locally.")

        manager.send(SESSION_ID, "Create offline.txt and commit it locally; do not push.")
        awaitAgent("Git approval") { state, status -> state?.pendingToolCallId == "commit-local-file" && status == "waiting" }

        assertEquals("hello from the offline agent\n", File(workDirectory, "offline.txt").readText())
        val beforeApproval = dao.messages(SESSION_ID)
        assertEquals("complete", beforeApproval.single { it.name == "write_file" }.status)
        assertEquals("pending", beforeApproval.single { it.name == "git_commit" }.status)
        Git.open(workDirectory).use { assertEquals("No commit before user approval", originalHead, it.repository.resolve("HEAD").name) }

        manager.resolveApproval("commit-local-file", true)
        awaitIdle()

        val stored = dao.messages(SESSION_ID)
        assertEquals(listOf("user", "assistant", "tool", "assistant", "tool", "assistant"), stored.map { it.role })
        val tools = stored.filter { it.role == "tool" }
        assertEquals(listOf("write_file", "git_commit"), tools.map { it.name })
        assertTrue("Both tool results must be persisted as complete", tools.all { it.status == "complete" })
        assertTrue(tools.last().content.startsWith("Committed "))
        assertEquals("Created offline.txt and committed it locally.", stored.last().content)
        assertEquals("Working tree clean", git.status(repository))
        assertEquals("idle", dao.session(SESSION_ID)!!.status)
        assertFalse(manager.states.value.getValue(SESSION_ID).running)
        assertNull(manager.states.value.getValue(SESSION_ID).pendingToolCallId)
        assertEquals(repository, dao.repository(repository.id))
        Git.open(workDirectory).use { working ->
            val commits = working.log().call().toList()
            assertEquals(2, commits.size)
            assertEquals("Offline agent commit", commits.first().fullMessage)
            assertEquals(AUTHOR_NAME, commits.first().authorIdent.name)
            assertEquals(AUTHOR_EMAIL, commits.first().authorIdent.emailAddress)
            assertEquals(originalHead, commits.last().name)
            assertEquals(originalRemote, working.repository.config.getString("remote", "origin", "url"))
        }
        Git.open(originDirectory).use { origin ->
            assertEquals("The origin must not receive a commit", originalHead, origin.repository.resolve("HEAD").name)
            assertEquals(1, origin.log().call().count())
        }
        assertFalse(File(originDirectory, "offline.txt").exists())

        val requests = List(3) { takeModelRequest() }
        assertEquals(3, server.requestCount)
        assertTrue(requests.all { it["stream"]?.jsonPrimitive?.content == "true" })
        val offeredTools = requests.first().getValue("tools").jsonArray.map {
            it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
        }
        assertTrue("write_file" in offeredTools && "git_commit" in offeredTools)
        val returnedToolResults = requests.last().getValue("messages").jsonArray
            .map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals(listOf("write-local-file", "commit-local-file"), returnedToolResults.map { it.getValue("tool_call_id").jsonPrimitive.content })
        assertTrue(returnedToolResults.last().getValue("content").jsonPrimitive.content.startsWith("Committed "))
    }

    @Test
    fun standaloneChatAnswersWithoutAdvertisingToolsOrTouchingRepositories() = runBlocking {
        saveSession(repositoryId = null)
        replies += textResponse("pong")

        manager.send(SESSION_ID, "Reply with exactly: pong")
        awaitIdle()

        val stored = dao.messages(SESSION_ID)
        assertEquals(listOf("user", "assistant"), stored.map { it.role })
        assertEquals("pong", stored.last().content)
        assertNull(dao.session(SESSION_ID)!!.repositoryId)
        assertEquals("idle", dao.session(SESSION_ID)!!.status)
        assertTrue(dao.repositories().isEmpty())
        assertTrue(temporaryRoot.listFiles().orEmpty().isEmpty())
        val request = takeModelRequest()
        assertEquals(1, server.requestCount)
        assertFalse("Standalone chat must not advertise tools", "tools" in request)
        assertFalse("Standalone chat must not request a tool choice", "tool_choice" in request)
    }

    private suspend fun saveSession(repositoryId: String?) {
        dao.saveSession(
            SessionEntity(
                id = SESSION_ID,
                repositoryId = repositoryId,
                modelId = MODEL_ID,
                title = "Offline regression",
                titleGenerated = true,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
    }

    private suspend fun awaitIdle() = awaitAgent("idle session") { state, status ->
        state != null && !state.running && status == "idle"
    }

    private suspend fun awaitAgent(label: String, predicate: (AgentRunState?, String?) -> Boolean) {
        try {
            withTimeout(20_000) {
                while (true) {
                    val state = manager.states.value[SESSION_ID]
                    val status = dao.session(SESSION_ID)?.status
                    check(state?.error == null && status != "error") { "Agent failed while waiting for $label: $state" }
                    if (predicate(state, status)) return@withTimeout
                    delay(25)
                }
            }
        } catch (error: TimeoutCancellationException) {
            val transcript = dao.messages(SESSION_ID).joinToString("\n") { "${it.role}/${it.name}: ${it.status} ${it.content}" }
            throw AssertionError("Timed out waiting for $label; state=${manager.states.value[SESSION_ID]}\n$transcript", error)
        }
    }

    private fun takeModelRequest() = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) {
        "Expected an offline model request"
    }.let { request ->
        assertEquals("/v1/chat/completions", request.path)
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    }

    private fun toolResponse(id: String, name: String, arguments: String) = sse(
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"$id","type":"function","function":{"name":"$name","arguments":${JsonPrimitive(arguments)}}}]}}]}""",
    )

    private fun textResponse(text: String) = sse("""{"choices":[{"delta":{"content":${JsonPrimitive(text)}}}]}""")

    private fun sse(delta: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: $delta\n\ndata: [DONE]\n\n")

    private companion object {
        const val PROVIDER_ID = "offline-provider"
        const val MODEL_ID = "offline-model"
        const val REPOSITORY_ID = "offline-local-repository"
        const val SESSION_ID = "offline-agent-session"
        const val AUTHOR_NAME = "Ti Offline Regression"
        const val AUTHOR_EMAIL = "offline-regression@example.invalid"
    }
}
