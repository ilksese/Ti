package app.ti.agent

import app.ti.data.RepositoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepoFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var repo: RepositoryEntity
    private val files = RepoFiles()

    private fun setupRepo() {
        root = temp.newFolder("repo")
        repo = RepositoryEntity(
            id = "r1",
            name = "repo",
            remoteUrl = "https://example.com/repo.git",
            localPath = root.absolutePath,
            username = "",
            token = "",
            createdAt = 0,
        )
    }

    private fun file(relative: String, content: String) {
        val target = File(root, relative)
        target.parentFile?.mkdirs()
        target.writeText(content)
    }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun listShowsDirectoriesAndFilesWithoutGit() = run {
        setupRepo()
        File(root, "sub").mkdirs()
        file("a.txt", "hello")
        File(root, ".git").mkdirs()
        file(".git/config", "secret")

        val listing = files.list(repo, "")

        assertTrue(listing.lines().any { it == "sub/" })
        assertTrue(listing.lines().any { it == "a.txt (5 bytes)" })
        assertFalse(listing.contains(".git"))
        assertFalse(listing.contains("config"))
    }

    @Test
    fun listRespectsOffset() = run {
        setupRepo()
        file("a.txt", "1")
        file("b.txt", "2")
        file("c.txt", "3")

        val listing = files.list(repo, "", offset = 2)

        assertEquals(1, listing.lines().count { it.isNotBlank() })
        assertTrue(listing.startsWith("c.txt"))
    }

    @Test
    fun listRejectsFileAndMissingPath() = run {
        setupRepo()
        file("a.txt", "1")

        assertThrowsMessage("Not a directory: a.txt") { files.list(repo, "a.txt") }
        assertThrowsMessage("nope") { files.list(repo, "nope") }
    }

    @Test
    fun readAddsOneBasedLineNumbers() = run {
        setupRepo()
        file("a.txt", "one\ntwo\nthree\n")

        assertEquals("1: one\n2: two\n3: three\n", files.read(repo, "a.txt"))
    }

    @Test
    fun readHonoursStartLine() = run {
        setupRepo()
        file("a.txt", "one\ntwo\nthree\n")

        assertEquals("2: two\n3: three\n", files.read(repo, "a.txt", startLine = 2))
    }

    @Test
    fun readPastEndAndDirectory() = run {
        setupRepo()
        file("a.txt", "one\n")
        File(root, "sub").mkdirs()

        assertEquals("Empty file or start_line past end", files.read(repo, "a.txt", startLine = 9))
        assertThrowsMessage("Not a file: sub") { files.read(repo, "sub") }
    }

    @Test
    fun searchReturnsPathLineAndText() = run {
        setupRepo()
        file("src/a.txt", "alpha\nneedle here\n")
        file("b.txt", "nothing")

        val result = files.search(repo, "needle", "")

        assertEquals("src/a.txt:2: needle here", slashes(result))
    }

    @Test
    fun searchIsCaseSensitiveAndOffsetSkips() = run {
        setupRepo()
        file("a.txt", "hit\nhit\nhit\n")

        assertEquals("a.txt:1: hit\na.txt:2: hit\na.txt:3: hit", slashes(files.search(repo, "hit", "")))
        assertEquals("a.txt:2: hit\na.txt:3: hit", slashes(files.search(repo, "hit", "", offset = 1)))
        assertEquals("No matches", files.search(repo, "HIT", ""))
    }

    @Test
    fun searchRejectsBlankQueryAndSkipsBinary() = run {
        setupRepo()
        File(root, "bin.dat").writeBytes(byteArrayOf(0xC3.toByte(), 0x28, 0x0A))
        file("ok.txt", "needle")

        assertThrowsMessage("Search query is required") { files.search(repo, "", "") }
        assertEquals("ok.txt:1: needle", files.search(repo, "needle", ""))
    }

    @Test
    fun writeCreatesParentsAndOverwrites() = run {
        setupRepo()

        files.write(repo, "deep/nested/a.txt", "first")
        assertEquals("first", File(root, "deep/nested/a.txt").readText())

        files.write(repo, "deep/nested/a.txt", "second")
        assertEquals("second", File(root, "deep/nested/a.txt").readText())
        assertEquals("Wrote 6 bytes to deep/nested/a.txt", files.write(repo, "deep/nested/a.txt", "second"))
    }

    @Test
    fun replaceRequiresUniqueExactMatch() = run {
        setupRepo()
        file("a.txt", "the old value")

        assertEquals("Replaced text in a.txt", files.replace(repo, "a.txt", "old", "new"))
        assertEquals("the new value", File(root, "a.txt").readText())

        file("dup.txt", "x x")
        assertThrowsMessage("old_text is not unique in dup.txt") { files.replace(repo, "dup.txt", "x", "y") }
        assertThrowsMessage("old_text not found in a.txt") { files.replace(repo, "a.txt", "absent", "y") }
        assertThrowsMessage("old_text cannot be empty") { files.replace(repo, "a.txt", "", "y") }
    }

    @Test
    fun deleteOnlyDeletesFiles() = run {
        setupRepo()
        file("a.txt", "x")
        File(root, "sub").mkdirs()

        assertEquals("Deleted a.txt", files.delete(repo, "a.txt"))
        assertFalse(File(root, "a.txt").exists())
        assertThrowsMessage("delete_file only deletes files") { files.delete(repo, "sub") }
    }

    @Test
    fun moveRejectsExistingTarget() = run {
        setupRepo()
        file("a.txt", "x")

        assertEquals("Moved a.txt to dir/b.txt", files.move(repo, "a.txt", "dir/b.txt"))
        assertEquals("x", File(root, "dir/b.txt").readText())

        file("c.txt", "y")
        assertThrowsMessage("Target already exists: dir/b.txt") { files.move(repo, "c.txt", "dir/b.txt") }
    }

    @Test
    fun rejectsAbsolutePaths() = run {
        setupRepo()
        val outside = temp.newFile("outside.txt").apply { writeText("secret") }

        assertThrowsMessage("Absolute paths are not allowed") { files.read(repo, outside.absolutePath) }
        assertThrowsMessage("Absolute paths are not allowed") { files.read(repo, "C:\\Windows\\win.ini") }
    }

    @Test
    fun rejectsParentTraversal() = run {
        setupRepo()
        temp.newFile("outside.txt").apply { writeText("secret") }

        assertThrowsMessage("Path escapes the repository") { files.read(repo, "../outside.txt") }
        assertThrowsMessage("Path escapes the repository") { files.write(repo, "../escape.txt", "x") }
        assertThrowsMessage("Path escapes the repository") { files.read(repo, "a/../../outside.txt") }
    }

    @Test
    fun rejectsGitDirectory() = run {
        setupRepo()
        File(root, ".git").mkdirs()
        file(".git/config", "[core]")

        assertThrowsMessage("Access to .git is not allowed") { files.read(repo, ".git/config") }
        assertThrowsMessage("Access to .git is not allowed") { files.search(repo, "core", ".git") }
        assertThrowsMessage("Access to .git is not allowed") { files.write(repo, ".git/hook", "x") }
    }
}

private fun slashes(value: String) = value.replace('\\', '/')

private fun assertThrowsMessage(expected: String, block: suspend () -> Unit) {
    val error = runCatching { runBlocking { block() } }.exceptionOrNull()
    if (error == null) throw AssertionError("Expected failure: $expected")
    val message = error.message.orEmpty()
    if (expected.isNotEmpty()) {
        assertTrue("Expected \"$expected\" but was \"$message\"", message.contains(expected))
    }
}