package app.ti

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ti.data.RepositorySource
import app.ti.data.RepositoryStatus
import app.ti.data.RepositoryStorageType
import app.ti.data.RepositoryEntity
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.SessionEntity
import app.ti.data.MessageEntity
import app.ti.testing.RepositoryTestEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

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
    fun refusesToCreateOverAnExistingDirectoryWithoutChangingItsFiles() = runBlocking {
        val directory = environment.root.resolve("project").apply { mkdir() }
        directory.resolve("notes.txt").writeText("original content")
        val container = environment.openContainer()

        val failure = runCatching { container.createLocalRepository("project").await() }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(container.dao.repositories().isEmpty())
        assertEquals("original content", directory.resolve("notes.txt").readText())
        assertFalse(directory.resolve(".git").exists())
    }

    @Test
    fun simultaneousCreationsWithTheSameNameProduceOnlyOneRepository() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val continueInitialization = CompletableDeferred<Unit>()
        val git = app.ti.git.GitService()
        val container = environment.openContainer(git = git, initializeNewRepository = {
            started.complete(Unit)
            continueInitialization.await()
            git.init(it)
        })
        val first = container.createLocalRepository("project")
        withTimeout(10_000) { started.await() }
        val second = container.createLocalRepository("project")
        continueInitialization.complete(Unit)

        val id = withTimeout(10_000) { first.await() }
        val failure = runCatching { withTimeout(10_000) { second.await() } }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(listOf(id), container.dao.repositories().map { it.id })
        assertEquals(RepositoryStatus.READY, container.dao.repository(id)!!.status)
        assertTrue(git.isRepository(environment.root.resolve("project")))
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

    @Test
    fun recognizesExistingGitRepositoryWithoutRewritingItsConfiguration() = runBlocking {
        val directory = environment.root.resolve("project").apply { mkdir() }
        val container = environment.openContainer(initializeExistingRepository = {
            error("Existing Git repositories must never be initialized again")
        })
        container.git.initialize(directory)
        val configuration = directory.resolve(".git/config").apply {
            appendText("\n[validation]\n\tmarker = preserve-this-value\n")
        }.readText()

        val id = container.importLocalRepository(directory, initialize = true).await()

        assertEquals(RepositoryStatus.READY, container.dao.repository(id)!!.status)
        assertEquals(configuration, directory.resolve(".git/config").readText())
    }

    @Test
    fun failedExistingDirectoryInitializationNeverRemovesUserContentOrGitOutput() = runBlocking {
        val directory = environment.root.resolve("project").apply { mkdir() }
        directory.resolve("notes.txt").writeText("keep this project")
        val container = environment.openContainer(initializeExistingRepository = {
            it.resolve(".git").mkdir()
            it.resolve(".git/partial").writeText("initializer output")
            error("Injected import failure")
        })

        val failure = runCatching { container.importLocalRepository(directory, initialize = true).await() }.exceptionOrNull()

        assertEquals("Injected import failure", failure?.message)
        assertEquals(RepositoryStatus.FAILED, container.dao.repositories().single().status)
        assertEquals("keep this project", directory.resolve("notes.txt").readText())
        assertEquals("initializer output", directory.resolve(".git/partial").readText())
    }

    @Test
    fun duplicateImportDoesNotReplaceRepositoryOrConversation() = runBlocking {
        val directory = environment.root.resolve("project").apply { mkdir() }
        val container = environment.openContainer()
        val id = container.importLocalRepository(directory, initialize = true).await()
        val original = container.dao.repository(id)!!
        saveConversation(container, original)

        val failure = runCatching {
            container.importLocalRepository(File(directory, "../project"), initialize = false).await()
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(listOf(id), container.dao.repositories().map { it.id })
        assertEquals("conversation", container.dao.session("conversation")!!.id)
        assertEquals("Keep this message", container.dao.messages("conversation").single().content)
    }

    @Test
    fun cancellationFinishesCleanupAndLeavesAnActionableFailureInsteadOfCreatingForever() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val container = environment.openContainer(initializeNewRepository = {
            it.resolve(".git").mkdir()
            it.resolve(".git/partial").writeText("incomplete")
            started.complete(Unit)
            awaitCancellation()
        })
        val operation = container.createLocalRepository("project")
        withTimeout(10_000) { started.await() }

        operation.cancelAndJoin()

        val repository = container.dao.repositories().single()
        assertEquals(RepositoryStatus.FAILED, repository.status)
        assertTrue(repository.progress.contains("interrupted", ignoreCase = true))
        assertFalse(environment.root.resolve("project").exists())
    }

    @Test
    fun initializationPermissionFailureIsDistinguishedFromGenericFailure() = runBlocking {
        val container = environment.openContainer(initializeNewRepository = {
            throw SecurityException("Storage access denied")
        })

        val failure = runCatching { container.createLocalRepository("project").await() }.exceptionOrNull()

        assertTrue(failure is SecurityException)
        assertEquals(RepositoryStatus.PERMISSION_REQUIRED, container.dao.repositories().single().status)
        assertFalse(environment.root.resolve("project").exists())
    }

    @Test
    fun reopensDiskDatabaseAndRecoversInterruptedMissingAndInvalidRepositoriesThenRechecks() = runBlocking {
        val database = environment.openDatabase()
        val complete = environment.root.resolve("complete").apply { mkdir() }
        val interrupted = environment.root.resolve("interrupted").apply { mkdir() }
        val invalid = environment.root.resolve("invalid").apply { mkdir() }
        app.ti.git.GitService().initialize(complete)
        database.dao().saveRepository(repository("complete", complete, RepositoryStatus.CREATING))
        database.dao().saveRepository(repository("interrupted", interrupted, RepositoryStatus.CREATING))
        database.dao().saveRepository(repository("missing", environment.root.resolve("missing")))
        database.dao().saveRepository(repository("invalid", invalid))
        environment.closeContainer()

        val container = environment.openContainer()
        val expected = mapOf(
            "complete" to RepositoryStatus.READY,
            "interrupted" to RepositoryStatus.FAILED,
            "missing" to RepositoryStatus.MISSING,
            "invalid" to RepositoryStatus.INVALID,
        )
        val recovered = withTimeout(10_000) {
            container.dao.observeRepositories().first { rows -> rows.associate { it.id to it.status } == expected }
        }

        assertTrue(recovered.single { it.id == "interrupted" }.progress.contains("interrupted", ignoreCase = true))
        container.git.initialize(interrupted)
        container.retryRepository(container.dao.repository("interrupted")!!).join()
        assertEquals(RepositoryStatus.READY, container.dao.repository("interrupted")!!.status)
        assertEquals("", container.dao.repository("interrupted")!!.progress)
        environment.closeContainer()

        val reopened = environment.openContainer()
        assertEquals(RepositoryStatus.READY, reopened.dao.repository("interrupted")!!.status)
        assertEquals(RepositoryStatus.MISSING, reopened.dao.repository("missing")!!.status)
        assertEquals(RepositoryStatus.INVALID, reopened.dao.repository("invalid")!!.status)
    }

    @Test
    fun removingAnImportInProgressCancelsAndJoinsItButPreservesItsFiles() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Unit>()
        val directory = environment.root.resolve("project").apply { mkdir() }
        directory.resolve("notes.txt").writeText("user project")
        val container = environment.openContainer(initializeExistingRepository = {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    it.resolve("finalized.txt").writeText("cleanup finished")
                    finalized.complete(Unit)
                }
            }
        })
        val operation = container.importLocalRepository(directory, initialize = true)
        withTimeout(10_000) { started.await() }
        val repository = container.dao.repositories().single()

        withTimeout(10_000) { container.removeRepository(repository).join() }

        assertTrue(operation.isCancelled)
        assertTrue(finalized.isCompleted)
        assertNull(container.dao.repository(repository.id))
        assertEquals("user project", directory.resolve("notes.txt").readText())
        assertEquals("cleanup finished", directory.resolve("finalized.txt").readText())
        assertFalse(directory.resolve(".git").exists())
    }

    @Test
    fun deletingFilesDuringImportWaitsForCancellationBeforeDeletingTheDirectory() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Unit>()
        val directory = environment.root.resolve("project").apply { mkdir() }
        directory.resolve("notes.txt").writeText("user project")
        val container = environment.openContainer(initializeExistingRepository = {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    it.resolve("finalized.txt").writeText("cleanup finished")
                    finalized.complete(Unit)
                }
            }
        })
        val operation = container.importLocalRepository(directory, initialize = true)
        withTimeout(10_000) { started.await() }
        val repository = container.dao.repositories().single()

        withTimeout(10_000) { container.deleteRepositoryFiles(repository).join() }

        assertTrue(operation.isCancelled)
        assertTrue(finalized.isCompleted)
        assertEquals(RepositoryStatus.MISSING, container.dao.repository(repository.id)!!.status)
        assertFalse(directory.exists())
    }

    @Test
    fun importingRootOrOutsideDirectoryIsRejectedWithoutSavingARecord() = runBlocking {
        val outside = environment.directory.resolve("outside").apply { mkdir() }
        outside.resolve("notes.txt").writeText("outside content")
        val container = environment.openContainer()

        listOf(environment.root, outside).forEach { directory ->
            val failure = runCatching { container.importLocalRepository(directory, initialize = true).await() }.exceptionOrNull()
            assertTrue("Unsafe import must be rejected: $directory", failure != null)
        }

        assertTrue(container.dao.repositories().isEmpty())
        assertEquals("outside content", outside.resolve("notes.txt").readText())
        assertFalse(outside.resolve(".git").exists())
        assertFalse(environment.root.resolve(".git").exists())
    }

    @Test
    fun deletingRootOrOutsideDirectoryIsRejectedWithoutRemovingAnyFiles() = runBlocking {
        val outside = environment.directory.resolve("outside").apply { mkdir() }
        val rootMarker = environment.root.resolve("root-note.txt").apply { writeText("root content") }
        val outsideMarker = outside.resolve("notes.txt").apply { writeText("outside content") }
        val container = environment.openContainer()
        val unsafe = listOf(repository("root", environment.root), repository("outside", outside))
        unsafe.forEach { container.dao.saveRepository(it) }

        unsafe.forEach { container.deleteRepositoryFiles(it).join() }

        unsafe.forEach { assertEquals(RepositoryStatus.FAILED, container.dao.repository(it.id)!!.status) }
        assertEquals("root content", rootMarker.readText())
        assertEquals("outside content", outsideMarker.readText())
    }

    @Test
    fun deletingARepositoryReplacedByASymlinkCannotDeleteAnotherRepository() = runBlocking {
        val container = environment.openContainer()
        val id = container.createLocalRepository("project").await()
        val repository = container.dao.repository(id)!!
        val originalDirectory = File(repository.localPath)
        val otherDirectory = environment.root.resolve("other").apply { mkdir() }
        val marker = otherDirectory.resolve("notes.txt").apply { writeText("another project") }
        assertTrue(originalDirectory.deleteRecursively())
        Files.createSymbolicLink(originalDirectory.toPath(), otherDirectory.toPath())

        container.deleteRepositoryFiles(repository).join()

        assertEquals(RepositoryStatus.FAILED, container.dao.repository(id)!!.status)
        assertEquals("another project", marker.readText())
        Files.delete(originalDirectory.toPath())
    }

    @Test
    fun deletingRepositoryFilesDoesNotFollowSymlinksInsideTheWorkingTree() = runBlocking {
        val container = environment.openContainer()
        val id = container.createLocalRepository("project").await()
        val repository = container.dao.repository(id)!!
        val outside = environment.directory.resolve("outside").apply { mkdir() }
        val marker = outside.resolve("notes.txt").apply { writeText("outside content") }
        Files.createSymbolicLink(File(repository.localPath, "external").toPath(), outside.toPath())

        container.deleteRepositoryFiles(repository).join()

        assertEquals(RepositoryStatus.MISSING, container.dao.repository(id)!!.status)
        assertFalse(File(repository.localPath).exists())
        assertEquals("outside content", marker.readText())
    }

    @Test
    fun failedCreationCannotCleanGitMetadataThroughAReplacedTopLevelSymlink() = runBlocking {
        val git = app.ti.git.GitService()
        val other = environment.root.resolve("other").apply { mkdir() }
        git.initialize(other)
        val configuration = other.resolve(".git/config").readText()
        other.resolve("notes.txt").writeText("another project")
        val container = environment.openContainer(git = git, initializeNewRepository = { directory ->
            check(directory.delete())
            Files.createSymbolicLink(directory.toPath(), other.toPath())
            error("Injected failure after replacing directory")
        })

        val failure = runCatching { container.createLocalRepository("project").await() }.exceptionOrNull()

        assertEquals("Injected failure after replacing directory", failure?.message)
        assertTrue("Cleanup must not remove the other repository's .git", git.isRepository(other))
        assertEquals(configuration, other.resolve(".git/config").readText())
        assertEquals("another project", other.resolve("notes.txt").readText())
        assertTrue(Files.isSymbolicLink(environment.root.resolve("project").toPath()))
        Files.delete(environment.root.resolve("project").toPath())
    }

    @Test
    fun failedCreationCannotCleanGitMetadataFromAReplacementDirectoryWithTheSamePath() = runBlocking {
        val git = app.ti.git.GitService()
        val container = environment.openContainer(git = git, initializeNewRepository = { directory ->
            check(directory.renameTo(environment.root.resolve("moved-original")))
            check(directory.mkdir())
            git.initialize(directory)
            directory.resolve("notes.txt").writeText("replacement project")
            error("Injected failure after directory replacement")
        })

        val failure = runCatching { container.createLocalRepository("project").await() }.exceptionOrNull()

        assertEquals("Injected failure after directory replacement", failure?.message)
        val replacement = environment.root.resolve("project")
        assertTrue("Cleanup must only touch the exact directory created by this operation", git.isRepository(replacement))
        assertEquals("replacement project", replacement.resolve("notes.txt").readText())
    }

    @Test
    fun staleDeleteConfirmationCannotDeleteANewRepositoryThatReusesTheOldPath() = runBlocking {
        val container = environment.openContainer()
        val oldId = container.createLocalRepository("project").await()
        val oldRepository = container.dao.repository(oldId)!!
        container.removeRepository(oldRepository).join()
        assertTrue(File(oldRepository.localPath).deleteRecursively())
        val newId = container.createLocalRepository("project").await()
        val newRepository = container.dao.repository(newId)!!
        val marker = File(newRepository.localPath, "notes.txt").apply { writeText("new project") }

        repeat(2) { container.deleteRepositoryFiles(oldRepository).join() }

        assertNull(container.dao.repository(oldId))
        assertEquals(RepositoryStatus.READY, container.dao.repository(newId)!!.status)
        assertTrue(container.git.isRepository(File(newRepository.localPath)))
        assertEquals("new project", marker.readText())
    }

    @Test
    fun deletingTheSameRepositoryFilesTwiceIsIdempotent() = runBlocking {
        val container = environment.openContainer()
        val id = container.createLocalRepository("project").await()
        val repository = container.dao.repository(id)!!

        repeat(2) { container.deleteRepositoryFiles(repository).join() }

        assertEquals(RepositoryStatus.MISSING, container.dao.repository(id)!!.status)
        assertEquals("Local files deleted", container.dao.repository(id)!!.progress)
        assertFalse(File(repository.localPath).exists())
    }

    @Test
    fun deleteConfirmationMustMatchTheCurrentRepositoryPathAndStorageType() = runBlocking {
        val container = environment.openContainer()
        val id = container.createLocalRepository("project").await()
        val otherId = container.createLocalRepository("other").await()
        val repository = container.dao.repository(id)!!
        val other = container.dao.repository(otherId)!!
        val staleConfirmations = listOf(
            repository.copy(storageType = RepositoryStorageType.SHARED_URI),
            repository.copy(localPath = other.localPath),
        )

        staleConfirmations.forEach { confirmation ->
            container.deleteRepositoryFiles(confirmation).join()
            assertEquals(RepositoryStatus.READY, container.dao.repository(id)!!.status)
            assertEquals(RepositoryStatus.READY, container.dao.repository(otherId)!!.status)
            assertTrue(container.git.isRepository(File(repository.localPath)))
            assertTrue(container.git.isRepository(File(other.localPath)))
        }
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
