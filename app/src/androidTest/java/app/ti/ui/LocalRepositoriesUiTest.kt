package app.ti.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ti.AppContainer
import app.ti.data.RepositorySource
import app.ti.data.RepositoryEntity
import app.ti.data.RepositoryStatus
import app.ti.testing.RepositoryTestEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.eclipse.jgit.api.Git
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LocalRepositoriesUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var environment: RepositoryTestEnvironment
    private lateinit var container: AppContainer
    private var showContent by mutableStateOf(true)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        environment = RepositoryTestEnvironment(context)
        container = environment.openContainer()
        rule.setContent { if (showContent) TiApp(container) }
        awaitDescription("Add repository")
    }

    @After
    fun tearDown() {
        rule.runOnIdle { showContent = false }
        rule.waitForIdle()
        if (::environment.isInitialized) runBlocking { environment.close() }
    }

    @Test
    fun createsLocalRepositoryAndOpensItsDetails() {
        openNewLocalRepository()
        rule.onNode(hasText("Repository name") and hasSetTextAction()).performTextInput("local-project")
        awaitEnabled("Create")
        rule.onNode(hasText("Create") and hasClickAction()).performClick()

        awaitDescription("New session")
        rule.onNodeWithText("local-project").assertIsDisplayed()
        rule.onNodeWithText("Local repository").assertIsDisplayed()
        rule.onNodeWithText(File(environment.root, "local-project").canonicalPath).assertIsDisplayed()
        awaitText("Working tree clean")
        val repository = runBlocking { container.dao.repositories().single() }
        assertEquals(RepositorySource.LOCAL, repository.source)
        assertEquals(RepositoryStatus.READY, repository.status)
        assertTrue(File(repository.localPath, ".git").isDirectory)
    }

    @Test
    fun blocksInvalidExistingAndRetainedRepositoryNamesBeforeSubmission() {
        File(environment.root, "existing").mkdir()
        runBlocking {
            container.dao.saveRepository(
                RepositoryEntity(
                    id = "retained",
                    name = "Retained project",
                    remoteUrl = null,
                    localPath = File(environment.root, "retained").canonicalPath,
                    username = "",
                    token = "",
                    createdAt = 1,
                    status = RepositoryStatus.MISSING,
                    source = RepositorySource.LOCAL,
                ),
            )
        }
        openNewLocalRepository()
        val create = rule.onNode(hasText("Create") and hasClickAction())
        val name = rule.onNode(hasText("Repository name") and hasSetTextAction())
        create.assertIsNotEnabled()
        name.performTextInput("../escape")
        create.assertIsNotEnabled()
        name.performTextReplacement("existing")
        awaitText("A directory or repository with this name already exists")
        create.assertIsNotEnabled()
        name.performTextReplacement("retained")
        awaitText("A directory or repository with this name already exists")
        create.assertIsNotEnabled()
        assertEquals(listOf("retained"), runBlocking { container.dao.repositories().map { it.id } })
    }

    @Test
    fun keepsTheDirectoryNameSeparateFromTheDisplayName() {
        openNewLocalRepository()
        rule.onNode(hasText("Repository name") and hasSetTextAction()).performTextInput("project-folder")
        rule.onNode(hasText("Display name (optional)") and hasSetTextAction()).performTextInput("My project")
        awaitEnabled("Create")
        clickButton("Create")

        awaitDescription("New session")
        rule.onNodeWithText("My project").assertIsDisplayed()
        val repository = runBlocking { container.dao.repositories().single() }
        assertEquals("My project", repository.name)
        assertEquals("project-folder", File(repository.localPath).name)
    }

    @Test
    fun importsOrdinaryDirectoryOnlyAfterExplicitConfirmation() {
        val directory = File(environment.root, "existing-project").apply { mkdir() }
        val readme = File(directory, "README.md").apply { writeText("keep these files\n") }
        selectExistingDirectory(directory.name)
        awaitText("Initialize Git repository?")
        clickButton("Cancel")
        assertFalse(File(directory, ".git").exists())
        assertTrue(runBlocking { container.dao.repositories().isEmpty() })

        selectExistingDirectory(directory.name)
        awaitText("Initialize Git repository?")
        clickButton("Initialize")
        awaitDescription("New session")
        assertEquals("keep these files\n", readme.readText())
        assertTrue(File(directory, ".git").isDirectory)
        awaitText("?? README.md")
    }

    @Test
    fun importsExistingGitWithoutChangingItsConfigurationAndRejectsDuplicates() {
        val directory = File(environment.root, "git-project").apply { mkdir() }
        Git.init().setDirectory(directory).call().use { git ->
            git.repository.config.apply { setString("ti", null, "marker", "keep this config"); save() }
        }
        val originalConfig = File(directory, ".git/config").readText()
        selectExistingDirectory(directory.name)
        awaitDescription("New session")
        assertEquals(originalConfig, File(directory, ".git/config").readText())
        rule.onNodeWithContentDescription("Back").performClick()
        selectExistingDirectory(directory.name)
        awaitText("This directory is already added")
        assertEquals(1, runBlocking { container.dao.repositories().size })
        assertEquals(originalConfig, File(directory, ".git/config").readText())
    }

    @Test
    fun removingPreservesFilesAndDeletingRequiresASeparateConfirmation() {
        runBlocking { container.createLocalRepository("protected-project").await() }
        val directory = File(environment.root, "protected-project")
        val readme = File(directory, "README.md").apply { writeText("valuable files") }
        awaitText("protected-project")
        rule.onNodeWithContentDescription("Repository actions").performClick()
        clickButton("Remove from list")
        awaitText("No repositories")
        assertEquals("valuable files", readme.readText())
        assertTrue(File(directory, ".git").isDirectory)

        selectExistingDirectory(directory.name)
        awaitDescription("New session")
        rule.onNodeWithContentDescription("Repository actions").performClick()
        clickButton("Delete local files")
        clickButton("Cancel")
        assertEquals("valuable files", readme.readText())

        rule.onNodeWithContentDescription("Repository actions").performClick()
        clickButton("Delete local files")
        clickButton("Delete")
        awaitDescription("Add repository")
        rule.waitUntil(15_000) {
            runBlocking { container.dao.repositories().single().status == RepositoryStatus.MISSING }
        }
        assertFalse(directory.exists())
        awaitText("Missing directory: Local files deleted")
    }

    @Test
    fun showsPersistentInitializationFailureWithoutDeletingUserFiles() {
        reopenContainer { directory ->
            File(directory, "README.md").writeText("preserve user data")
            File(directory, ".git").mkdir()
            throw IOException("Initialization failed for test")
        }
        openNewLocalRepository()
        rule.onNode(hasText("Repository name") and hasSetTextAction()).performTextInput("failed-project")
        awaitEnabled("Create")
        clickButton("Create")
        awaitText("Initialization failed for test")
        awaitText("Failed: Initialization failed for test")
        rule.onNodeWithContentDescription("Retry repository check").assertIsDisplayed()
        assertEquals("preserve user data", File(environment.root, "failed-project/README.md").readText())
        assertFalse(File(environment.root, "failed-project/.git").exists())
        assertEquals(RepositoryStatus.FAILED, runBlocking { container.dao.repositories().single().status })
    }

    @Test
    fun reopensTheDatabaseAndRecoversMissingRepositoryThroughRetry() {
        val id = runBlocking { container.createLocalRepository("recover-project").await() }
        val directory = File(environment.root, "recover-project")
        directory.deleteRecursively()
        reopenContainer()
        awaitText("Missing directory: Directory is missing")
        runBlocking { container.git.init(directory) }
        val retry = rule.onNodeWithContentDescription("Retry repository check").fetchSemanticsNode().boundsInWindow
        val actions = rule.onNodeWithContentDescription("Repository actions").fetchSemanticsNode().boundsInWindow
        assertTrue("Retry and repository actions must not overlap", retry.right <= actions.left || actions.right <= retry.left)
        rule.onNodeWithContentDescription("Retry repository check").performClick()
        awaitText("Ready: ${directory.canonicalPath}")
        assertEquals(RepositoryStatus.READY, runBlocking { container.dao.repository(id)!!.status })
        rule.onNodeWithText("recover-project").performClick()
        awaitDescription("New session")
        awaitText("Working tree clean")
    }

    @Test
    fun importsALargeDirectoryAndKeepsNavigationResponsive() {
        repeat(250) { File(environment.root, "folder-${it.toString().padStart(3, '0')}").mkdir() }
        val directory = File(environment.root, "zzz-large-project").apply { mkdir() }
        val files = File(directory, "files").apply { mkdir() }
        repeat(1_500) { File(files, "file-${it.toString().padStart(4, '0')}.txt").writeText("sample $it\n") }
        rule.onNodeWithContentDescription("Add existing local repository").performClick()
        rule.onNodeWithTag("local-repository-directories").performScrollToNode(hasText(directory.name))
        clickButton(directory.name)
        awaitText("Initialize Git repository?")
        clickButton("Initialize")
        awaitDescription("New session")
        awaitText("?? files/file-0000.txt")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNode(hasText("Chat") and hasClickAction()).performClick()
        awaitDescription("New chat")
        rule.onNode(hasText("Repositories") and hasClickAction()).performClick()
        awaitText("zzz-large-project")
        assertEquals(1_500, files.listFiles()!!.size)
        assertEquals(RepositoryStatus.READY, runBlocking { container.dao.repositories().single().status })
    }

    @Test
    fun acceptsManualDirectoryPathsOnlyWithinTheAppRepositoryRoot() {
        val outside = File(environment.directory, "outside").apply { mkdir() }
        val directory = File(environment.root, "manual-project").apply { mkdir() }
        runBlocking { container.git.initialize(directory) }
        rule.onNodeWithContentDescription("Add existing local repository").performClick()
        val path = rule.onNode(hasText("Directory path") and hasSetTextAction())
        path.performTextInput(outside.path)
        clickButton("Check directory")
        awaitText("Only directories under the app repository root can be imported")
        assertTrue(runBlocking { container.dao.repositories().isEmpty() })
        assertFalse(File(outside, ".git").exists())
        path.performTextReplacement(directory.path)
        clickButton("Check directory")
        awaitDescription("New session")
        assertEquals(directory.canonicalPath, runBlocking { container.dao.repositories().single().localPath })
    }

    @Test
    fun remoteCloneKeepsItsHttpsValidationAndCanBeCancelledWithoutNetworkAccess() {
        rule.onNodeWithContentDescription("Add repository").performClick()
        clickButton("Clone remote repository")
        val clone = rule.onNode(hasText("Clone") and hasClickAction())
        val url = rule.onNode(hasText("HTTPS URL") and hasSetTextAction())
        clone.assertIsNotEnabled()
        url.performTextInput("http://example.test/project.git")
        clone.assertIsNotEnabled()
        url.performTextReplacement("https://example.test/project.git")
        awaitEnabled("Clone")
        clickButton("Cancel")
        assertTrue(runBlocking { container.dao.repositories().isEmpty() })
    }

    @Test
    fun aNewSubmissionKeepsItsNavigationWhenThePreviousOperationFinishes() {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        reopenContainer { directory ->
            if (directory.name == "first-project") {
                firstStarted.complete(Unit)
                releaseFirst.await()
            } else {
                secondStarted.complete(Unit)
                releaseSecond.await()
            }
            container.git.init(directory)
        }
        try {
            createThroughUi("first-project")
            rule.waitUntil(15_000) { firstStarted.isCompleted }
            createThroughUi("second-project")
            rule.waitForIdle()

            releaseFirst.complete(Unit)
            rule.waitUntil(15_000) { secondStarted.isCompleted }
            releaseSecond.complete(Unit)

            awaitDescription("New session")
            rule.onNodeWithText("second-project").assertIsDisplayed()
            val repositories = runBlocking { container.dao.repositories() }
            assertEquals(2, repositories.size)
            assertTrue(repositories.all { it.status == RepositoryStatus.READY })
        } finally {
            releaseFirst.complete(Unit)
            releaseSecond.complete(Unit)
        }
    }

    private fun openNewLocalRepository() {
        rule.onNodeWithContentDescription("Add repository").performClick()
        rule.onNode(hasText("New local repository") and hasClickAction()).performClick()
    }

    private fun createThroughUi(name: String) {
        openNewLocalRepository()
        rule.onNode(hasText("Repository name") and hasSetTextAction()).performTextInput(name)
        awaitEnabled("Create")
        clickButton("Create")
    }

    private fun selectExistingDirectory(name: String) {
        rule.onNodeWithContentDescription("Add existing local repository").performClick()
        awaitEnabled(name)
        clickButton(name)
    }

    private fun clickButton(text: String) {
        rule.onNode(hasText(text) and hasClickAction()).performClick()
    }

    private fun reopenContainer(initialize: (suspend (File) -> Unit)? = null) {
        rule.runOnIdle { showContent = false }
        rule.waitForIdle()
        runBlocking { environment.closeContainer() }
        container = if (initialize == null) environment.openContainer()
        else environment.openContainer(initializeNewRepository = initialize)
        rule.runOnIdle { showContent = true }
        awaitDescription("Add repository")
    }

    private fun awaitDescription(description: String) {
        try {
            rule.waitUntil(timeoutMillis = 15_000) {
                rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            throw AssertionError("Waiting for $description\n${semanticsTree()}", error)
        }
    }

    private fun awaitText(text: String) {
        try {
            rule.waitUntil(timeoutMillis = 15_000) {
                rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            throw AssertionError("Waiting for $text\n${semanticsTree()}", error)
        }
    }

    private fun awaitEnabled(text: String) {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodes(hasText(text) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun semanticsTree(): String {
        val roots = rule.onAllNodes(isRoot())
        return roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() }
    }
}
