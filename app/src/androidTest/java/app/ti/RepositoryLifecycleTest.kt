package app.ti

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ti.data.RepositorySource
import app.ti.data.RepositoryStatus
import app.ti.data.RepositoryEntity
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.SessionEntity
import app.ti.data.MessageEntity
import app.ti.testing.RepositoryTestEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RepositoryLifecycleTest {
    private lateinit var environment: RepositoryTestEnvironment

    @Before
    fun setUp() {
        environment = RepositoryTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = runBlocking {
        environment.close()
    }

    @Test
    fun createsReadyLocalRepositoryWithSeparateDisplayName() = runBlocking {
        val container = environment.openContainer()

        val id = container.createLocalRepository("project", "My project").await()

        val repository = container.dao.repository(id)!!
        assertEquals("My project", repository.name)
        assertEquals(RepositorySource.LOCAL, repository.source)
        assertEquals(RepositoryStatus.READY, repository.status)
        assertNull(repository.remoteUrl)
        assertEquals(environment.root.resolve("project").canonicalPath, repository.localPath)
        assertTrue(container.git.isRepository(environment.root.resolve("project")))
        assertEquals("Working tree clean", container.git.status(repository))
    }

    @Test
    fun missingDirectoryDuplicateCannotReplaceExistingRepositoryConversation() = runBlocking {
        val container = environment.openContainer()
        val existing = repository("existing", environment.root.resolve("project"), RepositoryStatus.MISSING)
        container.dao.saveRepository(existing)
        saveConversation(container, existing)

        val failure = runCatching { container.createLocalRepository("project").await() }.exceptionOrNull()

        assertTrue("Existing records reserve their path even when their directory is missing", failure != null)
        assertEquals(listOf("existing"), container.dao.repositories().map { it.id })
        assertEquals("conversation", container.dao.session("conversation")!!.id)
        assertEquals("Keep this message", container.dao.messages("conversation").single().content)
        assertFalse(environment.root.resolve("project").exists())
    }

    @Test
    fun failedCreationPreservesFilesAddedDuringInitializationAndRemovesOnlyPartialGitOutput() = runBlocking {
        val container = environment.openContainer(initializeNewRepository = { directory ->
            directory.resolve(".git").mkdir()
            directory.resolve(".git/partial").writeText("unfinished Git metadata")
            directory.resolve("notes.txt").writeText("user content")
            error("Injected initialization failure")
        })

        val failure = runCatching { container.createLocalRepository("project").await() }.exceptionOrNull()

        assertEquals("Injected initialization failure", failure?.message)
        val persisted = container.dao.repositories().single()
        assertEquals(RepositoryStatus.FAILED, persisted.status)
        assertEquals("Injected initialization failure", persisted.progress)
        assertEquals("user content", environment.root.resolve("project/notes.txt").readText())
        assertFalse(environment.root.resolve("project/.git").exists())
    }

    @Test
    fun initializesExistingDirectoryWithoutChangingItsFiles() = runBlocking {
        val directory = environment.root.resolve("project").apply { mkdir() }
        directory.resolve("notes.txt").writeText("existing project")
        val container = environment.openContainer()

        val id = container.importLocalRepository(directory, initialize = true, displayName = "Imported project").await()

        assertEquals("existing project", directory.resolve("notes.txt").readText())
        assertEquals("Imported project", container.dao.repository(id)!!.name)
        assertEquals(RepositoryStatus.READY, container.dao.repository(id)!!.status)
        assertTrue(container.git.isRepository(directory))
    }

    private fun repository(id: String, directory: File, status: String = RepositoryStatus.READY) = RepositoryEntity(
        id = id,
        name = id,
        remoteUrl = null,
        localPath = directory.canonicalPath,
        username = "",
        token = "",
        createdAt = 1,
        status = status,
        source = RepositorySource.LOCAL,
    )

    private suspend fun saveConversation(container: AppContainer, repository: RepositoryEntity) {
        container.dao.saveProvider(ProviderEntity("provider", "Test", "https://unused.invalid", "", "", 1))
        container.dao.saveModel(ModelEntity("model", "provider", "test-model"))
        container.dao.saveSession(SessionEntity("conversation", repository.id, "model", createdAt = 1, updatedAt = 1))
        container.dao.saveMessage(MessageEntity("message", "conversation", "user", "Keep this message", createdAt = 1))
    }
}
