package app.ti.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ti.TiApplication
import app.ti.data.MessageEntity
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.RepositoryEntity
import app.ti.data.SessionEntity
import app.ti.data.SettingEntity
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Opt-in end-to-end test that drives the real agent against the configured LLM provider.
 * Skipped unless run with `-e realLlm true`.
 *
 *   adb shell am instrument -w -e realLlm true \
 *     -e class app.ti.e2e.RealLlmAgentE2ETest app.ti.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class RealLlmAgentE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as TiApplication).container

    private val originDir = File(context.cacheDir, "e2e-origin")
    private val workDir = File(context.cacheDir, "e2e-work")
    private var repositoryId: String? = null
    private var modelId: String? = null
    private var sessionId: String? = null
    private var previousAuthorName: String? = null
    private var previousAuthorEmail: String? = null

    @Before
    fun requireOptInAndProvider() {
        val enabled = InstrumentationRegistry.getArguments().getString("realLlm") == "true"
        val provider = runBlocking { container.dao.providers().firstOrNull() }
        assumeTrue("Pass -e realLlm true to enable this test", enabled)
        assumeTrue("No LLM provider configured in the app", provider != null)
    }

    @After
    fun cleanUp() {
        if (InstrumentationRegistry.getArguments().getString("keepData") == "true") return
        runBlocking {
            sessionId?.let { id -> container.dao.session(id)?.let { container.dao.deleteSession(it) } }
            repositoryId?.let { id -> container.dao.repository(id)?.let { container.dao.deleteRepository(it) } }
            modelId?.let { id -> container.dao.model(id)?.let { container.dao.deleteModel(it) } }
            previousAuthorName?.let { container.dao.saveSetting(SettingEntity("git_author_name", it)) }
            previousAuthorEmail?.let { container.dao.saveSetting(SettingEntity("git_author_email", it)) }
        }
        originDir.deleteRecursively()
        workDir.deleteRecursively()
    }

    @Test
    fun agentCreatesFileAndCommitsItAgainstTheRealModel() {
        val provider = runBlocking { container.dao.providers().first() }
        val model = reuseOrCreateModel(provider)

        val origin = createOriginRepository()
        val cloned = File(context.cacheDir, "e2e-work").also { it.deleteRecursively() }
        runBlocking { container.git.clone("file://${origin.absolutePath}", cloned, "", "") }
        assertTrue("clone produced a working tree", File(cloned, "README.md").exists())

        val repo = RepositoryEntity(
            id = UUID.randomUUID().toString(),
            name = "ti-e2e",
            remoteUrl = "file://${origin.absolutePath}",
            localPath = cloned.absolutePath,
            username = "",
            token = "",
            createdAt = System.currentTimeMillis(),
            status = "ready",
        )
        repositoryId = repo.id
        runBlocking { container.dao.saveRepository(repo) }

        runBlocking {
            previousAuthorName = container.dao.setting("git_author_name")?.value
            previousAuthorEmail = container.dao.setting("git_author_email")?.value
            container.dao.saveSetting(SettingEntity("git_author_name", "Ti E2E"))
            container.dao.saveSetting(SettingEntity("git_author_email", "ti-e2e@example.com"))
        }

        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            repositoryId = repo.id,
            modelId = model.id,
            title = "E2E",
            titleGenerated = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        sessionId = session.id
        runBlocking { container.dao.saveSession(session) }

        runTurn(session.id, "Create a new file named e2e.txt in the repository root. Its content must be exactly: hello from Ti")
        runTurn(session.id, "Commit every change with the commit message 'e2e commit'.")

        val stored = runBlocking { container.dao.messages(session.id) }
        println("E2E transcript:\n" + stored.joinToString("\n") { "${it.role}/${it.name ?: "-"}: ${it.content.take(300)}" })

        assertEquals("hello from Ti", File(cloned, "e2e.txt").readText().trim())
        assertTrue(
            "write_file tool should have completed",
            stored.any { it.role == "tool" && it.name == "write_file" && it.status == "complete" },
        )
        assertTrue(
            "git_commit tool should have completed",
            stored.any { it.role == "tool" && it.name == "git_commit" && it.status == "complete" },
        )
        assertEquals("Working tree clean", runBlocking { container.git.status(repo) })
        assertTrue("final assistant answer expected", stored.last().role == "assistant")
    }

    @Test
    fun chatSessionAnswersWithoutRepositoryOrTools() {
        val provider = runBlocking { container.dao.providers().first() }
        val model = reuseOrCreateModel(provider)

        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            repositoryId = null,
            modelId = model.id,
            title = "E2E chat",
            titleGenerated = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        sessionId = session.id
        runBlocking { container.dao.saveSession(session) }

        runTurn(session.id, "Reply with exactly one word: pong")

        val stored = runBlocking { container.dao.messages(session.id) }
        println("E2E chat transcript:\n" + stored.joinToString("\n") { "${it.role}/${it.name ?: "-"}: ${it.content.take(300)}" })

        assertNull("chat session must not belong to a repository", runBlocking { container.dao.session(session.id)!!.repositoryId })
        assertTrue("no tool should run in a chat session", stored.none { it.role == "tool" })
        assertEquals("assistant", stored.last().role)
        assertTrue("assistant should answer", stored.last().content.isNotBlank())
    }

    /**
     * Reuses an existing model row when possible so the user's data is never replaced or deleted.
     * A temporary model is only created (and later removed) when the provider has none.
     */
    private fun reuseOrCreateModel(provider: ProviderEntity): ModelEntity {
        val existing = runBlocking { container.dao.models(provider.id) }
        PREFERRED.forEach { preferred -> existing.firstOrNull { it.modelId == preferred }?.let { return it } }
        val available = runBlocking { container.llm.listModels(provider) }
        assumeTrue("Provider returned no models", available.isNotEmpty())
        val chosen = PREFERRED.firstOrNull { it in available } ?: existing.firstOrNull()?.modelId ?: available.first()
        println("E2E model: $chosen (of ${available.size} available)")
        existing.firstOrNull { it.modelId == chosen }?.let { return it }
        return ModelEntity(UUID.randomUUID().toString(), provider.id, chosen, contextTokens = 200_000).also {
            modelId = it.id
            runBlocking { container.dao.saveModel(it) }
        }
    }

    private fun runTurn(session: String, prompt: String) {
        container.agents.send(session, prompt)
        val deadline = System.currentTimeMillis() + 240_000
        var started = false
        while (System.currentTimeMillis() < deadline) {
            val state = container.agents.states.value[session]
            state?.pendingToolCallId?.let { container.agents.resolveApproval(it, true) }
            if (state?.running == true) started = true
            val status = runBlocking { container.dao.session(session)?.status }
            val busy = state?.running == true || status == "running" || status == "waiting"
            if (started && !busy) {
                assertEquals("turn should end idle: $prompt", "idle", status)
                return
            }
            if (!started && status == "error") break
            Thread.sleep(250)
        }
        val transcript = runBlocking { container.dao.messages(session) }
            .joinToString("\n") { "${it.role}/${it.name ?: "-"}: ${it.content.take(500)}" }
        throw AssertionError("Turn did not finish in time: $prompt\n$transcript")
    }

    private fun createOriginRepository(): File {
        originDir.deleteRecursively()
        originDir.mkdirs()
        Git.init().setDirectory(originDir).call().use { git ->
            File(originDir, "README.md").writeText("# Ti E2E origin\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("origin").setAuthor("Ti E2E", "ti-e2e@example.com").setCommitter("Ti E2E", "ti-e2e@example.com").call()
        }
        return originDir
    }

    private companion object {
        val PREFERRED = listOf(
            "deepseek/deepseek-v4.1-flash",
            "deepseek-v4-flash",
            "gpt-5.5",
            "claude-sonnet-4-6",
        )
    }
}
