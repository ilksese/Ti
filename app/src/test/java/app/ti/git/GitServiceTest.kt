package app.ti.git

import app.ti.data.RepositoryEntity
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var repo: RepositoryEntity
    private val git = GitService()
    private val author = "Ti Tester" to "ti@example.com"

    private fun initRepo() {
        dir = temp.newFolder("repo")
        Git.init().setDirectory(dir).call().close()
        Git.open(dir).use { it.repository.config.apply { setString("user", null, "name", author.first); save() } }
        repo = RepositoryEntity(
            id = "r1",
            name = "repo",
            remoteUrl = "https://github.com/acme/project.git",
            localPath = dir.path,
            username = "",
            token = "",
            createdAt = 0,
        )
    }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private fun write(relative: String, content: String) {
        File(dir, relative).apply { parentFile?.mkdirs(); writeText(content) }
    }

    private fun commit(message: String): String = runBlocking { git.commit(repo, message, author.first, author.second) }

    private fun currentBranch(): String =
        runBlocking { git.branches(repo) }.first { it.startsWith("* ") }.removePrefix("* ")

    @Test
    fun rewritesOnlyGithubUrls() {
        val github = "https://github.com/acme/project.git"

        assertEquals(github, gitProxyUrl(github, "github"))
        assertEquals("https://gh-proxy.com/$github", gitProxyUrl(github, "gh-proxy"))
        assertEquals("https://gitclone.com/github.com/acme/project.git", gitProxyUrl(github, "gitclone"))
        assertEquals("https://gitlab.com/acme/project.git", gitProxyUrl("https://gitlab.com/acme/project.git", "gh-proxy"))
        assertEquals("not a url", gitProxyUrl("not a url", "gh-proxy"))
        assertEquals(github, gitProxyUrl(github, ""))
    }

    @Test
    fun statusReportsCleanUntrackedModifiedAndMissing() = run {
        initRepo()
        assertEquals("Working tree clean", git.status(repo))

        write("a.txt", "one")
        assertTrue(git.status(repo).lines().any { it == "?? a.txt" })

        commit("add a")
        assertEquals("Working tree clean", git.status(repo))

        write("a.txt", "two")
        assertTrue(git.status(repo).lines().any { it == " M a.txt" })

        File(dir, "a.txt").delete()
        assertTrue(git.status(repo).lines().any { it == " D a.txt" })
    }

    @Test
    fun commitStagesEverythingAndReturnsAbbreviatedHash() = run {
        initRepo()
        write("a.txt", "one")
        write("nested/b.txt", "two")

        val hash = commit("initial")

        assertEquals(8, hash.length)
        assertEquals("Working tree clean", git.status(repo))
        assertTrue(File(dir, "nested/b.txt").exists())
    }

    @Test
    fun commitRequiresMessage() = run {
        initRepo()
        write("a.txt", "one")
        val error = runCatching { commit("   ") }.exceptionOrNull()
        assertTrue("expected failure", error != null)
    }

    @Test
    fun diffIncludesTrackedAndUntrackedChanges() = run {
        initRepo()
        write("a.txt", "one")
        commit("initial")

        write("a.txt", "two")
        write("new.txt", "fresh")
        val diff = git.diff(repo)

        assertTrue(diff.contains("-one"))
        assertTrue(diff.contains("+two"))
        assertTrue(diff.contains("+fresh"))
        assertTrue(diff.contains("diff --git a/new.txt b/new.txt"))
    }

    @Test
    fun diffHonoursPathFilterAndEmptyState() = run {
        initRepo()
        write("a.txt", "one")
        write("b.txt", "two")
        commit("initial")

        assertEquals("No diff", git.diff(repo))

        write("a.txt", "changed")
        write("b.txt", "changed")
        val filtered = git.diff(repo, "a.txt")

        assertTrue(filtered.contains("a.txt"))
        assertFalse(filtered.contains("b.txt"))
    }

    @Test
    fun branchesMarkCurrentBranch() = run {
        initRepo()
        write("a.txt", "one")
        commit("initial")

        val branches = git.branches(repo)
        assertTrue(branches.any { it.startsWith("* ") })
        assertEquals(1, branches.size)
    }

    @Test
    fun createSwitchAndDeleteMergedBranch() = run {
        initRepo()
        write("a.txt", "one")
        commit("initial")
        val original = currentBranch()

        assertEquals("feature", git.createBranch(repo, "feature"))
        assertEquals("feature", git.switchBranch(repo, "feature"))
        assertTrue(git.branches(repo).contains("* feature"))

        assertEquals(original, git.switchBranch(repo, original))
        assertTrue(git.deleteBranch(repo, "feature").contains("feature"))
        assertFalse(git.branches(repo).any { it.contains("feature") })
    }

    @Test
    fun safeDeleteRefusesUnmergedBranch() = run {
        initRepo()
        write("a.txt", "one")
        commit("initial")
        git.createBranch(repo, "feature")
        git.switchBranch(repo, "feature")
        write("a.txt", "feature work")
        commit("unmerged")

        val error = runCatching { git.deleteBranch(repo, "feature") }.exceptionOrNull()

        assertTrue("expected safe delete to fail", error != null)
        assertTrue(git.branches(repo).contains("* feature"))
    }

    @Test
    fun configureRemoteRewritesFetchUrlButKeepsPushUrl() = run {
        initRepo()

        git.configureRemote(repo, "gh-proxy")

        Git.open(dir).use { handle ->
            val config = handle.repository.config
            assertEquals("https://gh-proxy.com/https://github.com/acme/project.git", config.getString("remote", "origin", "url"))
            assertEquals("https://github.com/acme/project.git", config.getString("remote", "origin", "pushurl"))
        }
    }

    @Test
    fun pullRequiresCleanWorkingTree() = run {
        initRepo()
        write("a.txt", "one")
        commit("initial")
        write("a.txt", "dirty")

        val error = runCatching { git.pull(repo) }.exceptionOrNull()

        assertTrue("expected pull to require a clean tree", error != null)
        assertTrue(error!!.message.orEmpty().contains("clean working tree"))
    }
}