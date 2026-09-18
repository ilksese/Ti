package app.ti.agent

import app.ti.data.MessageEntity
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.RepositoryEntity
import app.ti.data.SessionEntity
import app.ti.git.GitService
import app.ti.llm.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentRunManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dao: FakeTiDao
    private lateinit var manager: AgentRunManager
    private lateinit var repoDir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scripts = ScriptedDispatcher()
    private val git = GitService()

    private val repoId = "r1"
    private val providerId = "p1"
    private val modelId = "m1"
    private val sessionId = "s1"

    private val repo get() = runBlocking { dao.repository(repoId)!! }

    @Before
    fun setUp() {
        repoDir = temp.newFolder("repo")
        Git.init().setDirectory(repoDir).call().close()
        server = MockWebServer()
        server.dispatcher = scripts
        server.start()

        dao = FakeTiDao()
        dao.seedRepository(
            RepositoryEntity(repoId, "repo", "https://github.com/acme/repo.git", repoDir.path, "", "", 0),
        )
        dao.seedProvider(
            ProviderEntity(providerId, "seamaid", server.url("/v1").toString().trimEnd('/'), "key", "", 0),
        )
        dao.seedModel(ModelEntity(modelId, providerId, "test-model", contextTokens = 100_000))
        dao.seedSetting("git_author_name", "Ti")
        dao.seedSetting("git_author_email", "ti@example.com")
        seedSession(titleGenerated = true)

        manager = AgentRunManager(dao, OpenAiClient(), RepoFiles(), git, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun seedSession(
        id: String = sessionId,
        titleGenerated: Boolean = true,
        status: String = "idle",
        repositoryId: String? = repoId,
    ) {
        dao.seedSession(
            SessionEntity(
                id = id,
                repositoryId = repositoryId,
                modelId = modelId,
                title = "Existing title",
                titleGenerated = titleGenerated,
                status = status,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
    }

    private fun awaitState(
        id: String = sessionId,
        timeoutMs: Long = 20_000,
        predicate: (AgentRunState?, String?) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = manager.states.value[id]
            val status = runBlocking { dao.session(id)?.status }
            if (predicate(state, status)) return
            Thread.sleep(10)
        }
        val state = manager.states.value[id]
        throw AssertionError("Timed out; state=$state status=${runBlocking { dao.session(id)?.status }}")
    }

    private fun awaitFinished(id: String = sessionId) = awaitState(id) { state, status ->
        state != null && !state.running && status != "running" && status != "waiting"
    }

    private fun awaitApproval(id: String = sessionId): String {
        awaitState(id) { state, _ -> state?.pendingToolCallId != null }
        return checkNotNull(manager.states.value[id]!!.pendingToolCallId)
    }

    private fun messages(id: String = sessionId) = runBlocking { dao.messages(id) }

    private fun status(id: String = sessionId) = runBlocking { dao.session(id)?.status }

    private fun toolMessage(callId: String) = messages().first { it.role == "tool" && it.toolCallId == callId }

    @Test
    fun ignoresBlankInput() {
        manager.send(sessionId, "   ")

        Thread.sleep(200)

        assertTrue(messages().isEmpty())
        assertEquals("idle", status())
    }

    @Test
    fun runsToolRoundAndPersistsConversation() {
        scripts.streams += toolCallSse("write_file", """{"path":"hello.txt","content":"hi"}""")
        scripts.streams += sseText("All done")

        manager.send(sessionId, "create hello.txt")
        awaitFinished()

        val stored = messages()
        assertEquals(listOf("user", "assistant", "tool", "assistant"), stored.map { it.role })
        assertEquals("write_file", stored[2].name)
        assertEquals("Wrote 2 bytes to hello.txt", stored[2].content)
        assertEquals("complete", stored[2].status)
        assertEquals("", stored[1].content)
        assertTrue(stored[1].toolCallsJson!!.contains("write_file"))
        assertEquals("All done", stored[3].content)
        assertEquals("hi", File(repoDir, "hello.txt").readText())
        assertEquals("idle", status())
    }

    @Test
    fun persistsReasoningPerSuccessfulModelRequestAndExcludesItFromFollowup() {
        scripts.streams += sseReasoningToolCall("deciding", "list_directory", """{"path":""}""")
        scripts.streams += sseReasoningText("checking result", "All done")

        manager.send(sessionId, "inspect the repo")
        awaitFinished()

        val stored = messages()
        assertEquals(listOf("user", "reasoning", "assistant", "tool", "reasoning", "assistant"), stored.map { it.role })
        assertEquals("deciding", stored[1].content)
        assertEquals("checking result", stored[4].content)
        assertTrue(scripts.bodies[1].contains("All done").not())
        assertTrue(scripts.bodies[1].contains("deciding").not())
        assertTrue(scripts.bodies[1].contains("checking result").not())
    }

    @Test
    fun discardsReasoningFromFailedAttemptBeforeRetry() {
        scripts.streams += sse(
            """{"choices":[{"delta":{"reasoning_content":"discard me"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"list_directory","arguments":"{}"}}]}}]}""",
        )
        scripts.streams += sseReasoningText("keep me", "done")

        manager.send(sessionId, "retry")
        awaitFinished()

        assertEquals(listOf("user", "reasoning", "assistant"), messages().map { it.role })
        assertEquals("keep me", messages()[1].content)
    }

    @Test
    fun discardsReasoningWhenStreamEndsBeforeDone() {
        scripts.streams += sseWithoutDone(
            """{"choices":[{"delta":{"reasoning_content":"discard me"}}]}""",
        )
        scripts.streams += sseReasoningText("keep me", "done")

        manager.send(sessionId, "retry after disconnect")
        awaitFinished()

        assertEquals(listOf("user", "reasoning", "assistant"), messages().map { it.role })
        assertEquals("keep me", messages()[1].content)
    }

    @Test
    fun executesMultipleReadOnlyToolsInOneRound() {
        scripts.streams += sseToolCalls(
            "call_1" to ("list_directory" to """{"path":""}"""),
            "call_2" to ("write_file" to """{"path":"a.txt","content":"x"}"""),
        )
        scripts.streams += sseText("done")

        manager.send(sessionId, "do two things")
        awaitFinished()

        val tools = messages().filter { it.role == "tool" }
        assertEquals(2, tools.size)
        assertTrue(tools.all { it.status == "complete" })
        assertEquals("x", File(repoDir, "a.txt").readText())
    }

    @Test
    fun mutatingGitToolWaitsForApprovalThenCommits() {
        File(repoDir, "a.txt").writeText("x")
        scripts.streams += toolCallSse("git_commit", """{"message":"first"}""")
        scripts.streams += sseText("Committed successfully")

        manager.send(sessionId, "commit")
        val callId = awaitApproval()

        assertEquals("waiting", status())
        assertEquals("pending", toolMessage(callId).status)

        manager.resolveApproval(callId, true)
        awaitFinished()

        val tool = toolMessage(callId)
        assertEquals("complete", tool.status)
        assertTrue(tool.content.startsWith("Committed"))
        assertEquals("Working tree clean", runBlocking { git.status(repo) })
    }

    @Test
    fun rejectedApprovalMarksToolRejectedAndKeepsWorking() {
        File(repoDir, "a.txt").writeText("x")
        scripts.streams += toolCallSse("git_commit", """{"message":"first"}""")
        scripts.streams += sseText("Understood")

        manager.send(sessionId, "commit")
        val callId = awaitApproval()
        manager.resolveApproval(callId, false)
        awaitFinished()

        val tool = toolMessage(callId)
        assertEquals("rejected", tool.status)
        assertEquals("User rejected this Git operation", tool.content)
        assertTrue(runBlocking { git.status(repo) }.contains("?? a.txt"))
        assertEquals("Understood", messages().last().content)
    }

    @Test
    fun unknownToolIsReportedAsToolError() {
        scripts.streams += toolCallSse("delete_everything", "{}")
        scripts.streams += sseText("recovered")

        manager.send(sessionId, "try unknown tool")
        awaitFinished()

        val tool = messages().first { it.role == "tool" }
        assertEquals("error", tool.status)
        assertTrue(tool.content.contains("Unknown tool: delete_everything"))
    }

    @Test
    fun retriesTransientLlmFailureThenSucceeds() {
        scripts.streams += MockResponse().setResponseCode(500).setBody("upstream boom")
        scripts.streams += sseText("after retry")

        manager.send(sessionId, "hello")
        awaitFinished()

        assertEquals("after retry", messages().last().content)
        assertEquals("idle", status())
    }

    @Test
    fun stopsAfterTwentyToolRounds() {
        repeat(20) { scripts.streams += toolCallSse("list_directory", """{"path":""}""") }

        manager.send(sessionId, "loop forever")
        awaitFinished()

        assertEquals("error", status())
        assertEquals("Error: Agent stopped after 20 tool rounds", messages().last().content)
    }

    @Test
    fun missingSessionFailsFast() {
        manager.send("ghost", "hello")
        awaitFinished("ghost")

        assertEquals("Error: Session not found", messages("ghost").last().content)
        assertEquals("error", messages("ghost").last().status)
    }

    @Test
    fun compactSummarisesOlderConversation() {
        var time = 100L
        repeat(8) { index ->
            dao.seedMessage(
                MessageEntity(
                    id = "m$index",
                    sessionId = sessionId,
                    role = if (index % 2 == 0) "user" else "assistant",
                    content = "turn $index",
                    createdAt = time++,
                ),
            )
        }
        scripts.plains += plainText("SUMMARY")

        manager.send(sessionId, "/compact")
        awaitFinished()

        assertEquals("SUMMARY", runBlocking { dao.session(sessionId)!!.summary })
        assertTrue(messages().count { it.compacted } >= 2)
        assertTrue(messages().any { it.role == "event" && it.content == "Context compacted" })
    }

    @Test
    fun compactWithShortHistoryReportsNothingToCompact() {
        manager.send(sessionId, "/compact")
        awaitFinished()

        assertTrue(messages().any { it.role == "event" && it.content == "Nothing to compact" })
    }

    @Test
    fun chatSessionRunsWithoutRepositoryOrTools() {
        seedSession(id = "c1", repositoryId = null)
        scripts.streams += sseText("just chatting")

        manager.send("c1", "hello")
        awaitFinished("c1")

        assertEquals("just chatting", messages("c1").last().content)
        assertEquals("idle", status("c1"))
        val body = scripts.bodies.last()
        assertTrue(body.contains("no repository"))
        assertTrue(!body.contains("\"tools\""))
    }

    @Test
    fun chatSessionRefusesToolCalls() {
        seedSession(id = "c2", repositoryId = null)
        scripts.streams += toolCallSse("git_status", "{}")
        scripts.streams += sseText("sorry")

        manager.send("c2", "what changed?")
        awaitFinished("c2")

        val tool = messages("c2").first { it.role == "tool" }
        assertEquals("error", tool.status)
        assertTrue(tool.content.contains("not attached to a repository"))
    }

    @Test
    fun generatesTitleForUntitledSession() {
        seedSession(id = "s2", titleGenerated = false)

        scripts.streams += sseText("hello there")
        manager.send("s2", "write a poem about git")

        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && runBlocking { dao.session("s2")!!.titleGenerated } != true) {
            Thread.sleep(10)
        }

        assertEquals("Generated Title", runBlocking { dao.session("s2")!!.title })
        assertTrue(runBlocking { dao.session("s2")!!.titleGenerated })
    }
}

private class ScriptedDispatcher : Dispatcher() {
    val streams = ArrayDeque<MockResponse>()
    val plains = ArrayDeque<MockResponse>()
    val bodies = mutableListOf<String>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val body = request.body.readUtf8()
        bodies += body
        val streaming = Regex("\"stream\"\\s*:\\s*true").containsMatchIn(body)
        return if (streaming) {
            streams.removeFirstOrNull() ?: sseText("fallback")
        } else {
            plains.removeFirstOrNull() ?: plainText("Generated Title")
        }
    }
}

private fun sse(vararg payloads: String) = MockResponse()
    .setHeader("Content-Type", "text/event-stream")
    .setBody(payloads.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

private fun sseWithoutDone(vararg payloads: String) = MockResponse()
    .setHeader("Content-Type", "text/event-stream")
    .setBody(payloads.joinToString("") { "data: $it\n\n" })

private fun sseText(content: String) =
    sse("""{"choices":[{"delta":{"content":${JsonPrimitive(content)}}}]}""")

private fun sseReasoningText(reasoning: String, content: String) = sse(
    """{"choices":[{"delta":{"reasoning_content":${JsonPrimitive(reasoning)}}}]}""",
    """{"choices":[{"delta":{"content":${JsonPrimitive(content)}}}]}""",
)

private fun sseReasoningToolCall(reasoning: String, name: String, arguments: String) = sse(
    """{"choices":[{"delta":{"reasoning_content":${JsonPrimitive(reasoning)}}}]}""",
    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"$name","arguments":${JsonPrimitive(arguments)}}}]}}]}""",
)

private fun sseToolCalls(vararg calls: Pair<String, Pair<String, String>>) = sse(
    buildString {
        append("""{"choices":[{"delta":{"tool_calls":[""")
        calls.forEachIndexed { index, (id, call) ->
            if (index > 0) append(",")
            append("""{"index":$index,"id":"$id","function":{"name":"${call.first}","arguments":${JsonPrimitive(call.second)}}}""")
        }
        append("""]}}]}""")
    },
)

private fun toolCallSse(name: String, arguments: String, id: String = "call_1") =
    sseToolCalls(id to (name to arguments))

private fun plainText(content: String) =
    MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(content)}}}]}""")
