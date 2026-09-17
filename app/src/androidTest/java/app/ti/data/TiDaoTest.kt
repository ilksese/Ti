package app.ti.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TiDaoTest {
    private lateinit var database: TiDatabase
    private lateinit var dao: TiDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repository(id: String, createdAt: Long, name: String = id) = RepositoryEntity(
        id = id,
        name = name,
        remoteUrl = "https://github.com/acme/$name.git",
        localPath = "/tmp/$id",
        username = "u",
        token = "t",
        createdAt = createdAt,
    )

    private fun provider(id: String, name: String) = ProviderEntity(id, name, "https://api.test/v1", "key", "", 0)

    private fun model(id: String, providerId: String, modelId: String, discovered: Boolean = true) =
        ModelEntity(id = id, providerId = providerId, modelId = modelId, discovered = discovered)

    private fun session(id: String, repositoryId: String?, modelId: String, updatedAt: Long) = SessionEntity(
        id = id,
        repositoryId = repositoryId,
        modelId = modelId,
        createdAt = 0,
        updatedAt = updatedAt,
    )

    private fun message(id: String, sessionId: String, role: String, createdAt: Long, status: String = "complete") =
        MessageEntity(id = id, sessionId = sessionId, role = role, content = id, status = status, createdAt = createdAt)

    @Test
    fun repositoriesAreOrderedByNewestFirstAndDeletable() = runBlocking {
        dao.saveRepository(repository("a", 1))
        dao.saveRepository(repository("b", 2))

        assertEquals(listOf("b", "a"), dao.repositories().map { it.id })
        assertEquals(listOf("b", "a"), dao.observeRepositories().first().map { it.id })

        dao.saveRepository(repository("a", 1, name = "renamed"))
        assertEquals("renamed", dao.repository("a")!!.name)

        dao.deleteRepository(repository("a", 1))
        assertNull(dao.repository("a"))
    }

    @Test
    fun cloneProgressAndInterruption() = runBlocking {
        dao.saveRepository(repository("a", 1).copy(status = "cloning", progress = "Starting clone"))

        dao.updateRepositoryClone("a", "cloning", "Receiving objects 50%")
        assertEquals("Receiving objects 50%", dao.repository("a")!!.progress)

        dao.interruptCloningRepositories()
        assertEquals("failed", dao.repository("a")!!.status)
        assertEquals("Clone interrupted", dao.repository("a")!!.progress)
    }

    @Test
    fun providersAreSortedCaseInsensitively() = runBlocking {
        dao.saveProvider(provider("p1", "zeta"))
        dao.saveProvider(provider("p2", "Alpha"))

        assertEquals(listOf("p2", "p1"), dao.providers().map { it.id })
    }

    @Test
    fun deletingProviderCascadesToModels() = runBlocking {
        dao.saveProvider(provider("p1", "acme"))
        dao.saveModel(model("m1", "p1", "gpt"))

        assertEquals(1, dao.models("p1").size)
        dao.deleteProvider(provider("p1", "acme"))

        assertTrue(dao.models("p1").isEmpty())
        assertNull(dao.model("m1"))
    }

    @Test
    fun modelUniqueIndexIsHonoured() = runBlocking {
        dao.saveProvider(provider("p1", "acme"))
        dao.saveModel(model("m1", "p1", "gpt"))
        dao.saveModel(model("m2", "p1", "gpt"))

        assertEquals(1, dao.models("p1").size)
    }

    @Test
    fun markModelsUndiscoveredOnlyTouchOneProvider() = runBlocking {
        dao.saveProvider(provider("p1", "a"))
        dao.saveProvider(provider("p2", "b"))
        dao.saveModel(model("m1", "p1", "gpt"))
        dao.saveModel(model("m2", "p2", "gpt"))

        dao.markModelsUndiscovered("p1")

        assertEquals(false, dao.model("m1")!!.discovered)
        assertEquals(true, dao.model("m2")!!.discovered)
    }

    @Test
    fun deletingRepositoryCascadesSessionsAndMessages() = runBlocking {
        dao.saveRepository(repository("r1", 1))
        dao.saveProvider(provider("p1", "acme"))
        dao.saveModel(model("m1", "p1", "gpt"))
        dao.saveSession(session("s1", "r1", "m1", 1))
        dao.saveMessage(message("msg1", "s1", "user", 1))

        dao.deleteRepository(repository("r1", 1))

        assertNull(dao.session("s1"))
        assertTrue(dao.messages("s1").isEmpty())
    }

    @Test
    fun sessionStatusTitleAndSummaryUpdates() = runBlocking {
        dao.saveRepository(repository("r1", 1))
        dao.saveProvider(provider("p1", "acme"))
        dao.saveModel(model("m1", "p1", "gpt"))
        dao.saveSession(session("s1", "r1", "m1", 1))

        dao.updateSessionStatus("s1", "running", now = 5)
        assertEquals("running", dao.session("s1")!!.status)
        assertEquals(5L, dao.session("s1")!!.updatedAt)

        dao.updateSessionTitle("s1", "My title", now = 6)
        assertEquals("My title", dao.session("s1")!!.title)
        assertTrue(dao.session("s1")!!.titleGenerated)

        dao.updateSessionSummary("s1", "summary", through = 42, now = 7)
        assertEquals("summary", dao.session("s1")!!.summary)
        assertEquals(42L, dao.session("s1")!!.summaryThroughAt)
    }

    @Test
    fun interruptActiveSessionsOnlyAffectsRunningAndWaiting() = runBlocking {
        dao.saveRepository(repository("r1", 1))
        dao.saveProvider(provider("p1", "acme"))
        dao.saveModel(model("m1", "p1", "gpt"))
        dao.saveSession(session("running", "r1", "m1", 1).copy(status = "running"))
        dao.saveSession(session("waiting", "r1", "m1", 2).copy(status = "waiting"))
        dao.saveSession(session("idle", "r1", "m1", 3).copy(status = "idle"))

        dao.interruptActiveSessions()

        assertEquals("interrupted", dao.session("running")!!.status)
        assertEquals("interrupted", dao.session("waiting")!!.status)
        assertEquals("idle", dao.session("idle")!!.status)
    }

    @Test
    fun messagesAreOrderedAndToolLifecycleWorks() = runBlocking {
        dao.saveRepository(repository("r1", 1))
        dao.saveProvider(provider("p1", "acme"))
        dao.saveModel(model("m1", "p1", "gpt"))
        dao.saveSession(session("s1", "r1", "m1", 1))

        dao.saveMessage(message("b", "s1", "assistant", 2))
        dao.saveMessage(message("a", "s1", "user", 1))
        dao.saveMessage(message("c", "s1", "tool", 2, status = "pending"))

        assertEquals(listOf("a", "b", "c"), dao.messages("s1").map { it.id })

        dao.finishTool("c", "tool output", "complete")
        assertEquals("tool output", dao.messages("s1").first { it.id == "c" }.content)
        assertEquals("complete", dao.messages("s1").first { it.id == "c" }.status)
    }

    @Test
    fun pendingToolsCanBeInterruptedAndCompacted() = runBlocking {
        dao.saveRepository(repository("r1", 1))
        dao.saveProvider(provider("p1", "acme"))
        dao.saveModel(model("m1", "p1", "gpt"))
        dao.saveSession(session("s1", "r1", "m1", 1))
        dao.saveMessage(message("a", "s1", "user", 1))
        dao.saveMessage(message("b", "s1", "tool", 2, status = "pending"))
        dao.saveMessage(message("c", "s1", "tool", 3, status = "complete"))

        dao.interruptPendingTools()

        assertEquals("interrupted", dao.messages("s1").first { it.id == "b" }.status)
        assertEquals("complete", dao.messages("s1").first { it.id == "c" }.status)

        dao.compactMessages(listOf("a"))

        assertTrue(dao.messages("s1").first { it.id == "a" }.compacted)
        assertTrue(!dao.messages("s1").first { it.id == "c" }.compacted)
    }

    @Test
    fun settingsRoundTrip() = runBlocking {
        assertNull(dao.setting("git_token"))

        dao.saveSetting(SettingEntity("git_token", "abc"))
        assertEquals("abc", dao.setting("git_token")!!.value)
        assertEquals("abc", dao.observeSetting("git_token").first()!!.value)

        dao.saveSetting(SettingEntity("git_token", "xyz"))
        assertEquals("xyz", dao.setting("git_token")!!.value)
    }

    @Test
    fun chatSessionsAreSeparateFromRepositorySessions() = runBlocking {
        dao.saveRepository(repository("r1", 1))
        dao.saveProvider(provider("p1", "acme"))
        dao.saveModel(model("m1", "p1", "gpt"))
        dao.saveSession(session("s1", "r1", "m1", 1))
        dao.saveSession(session("c1", null, "m1", 2))

        assertEquals(listOf("c1"), dao.observeChatSessions().first().map { it.id })
        assertEquals(listOf("s1"), dao.observeSessions("r1").first().map { it.id })
        assertNull(dao.session("c1")!!.repositoryId)
    }

    @Test
    fun defaultModelIsFirstModelOfFirstProvider() = runBlocking {
        assertNull(dao.defaultModel())

        dao.saveProvider(provider("p2", "zebra"))
        dao.saveProvider(provider("p1", "alpha"))
        dao.saveModel(model("m1", "p1", "zzz"))
        dao.saveModel(model("m2", "p1", "aaa"))
        dao.saveModel(model("m3", "p2", "aaa"))

        assertEquals("m2", dao.defaultModel()!!.id)
    }
}