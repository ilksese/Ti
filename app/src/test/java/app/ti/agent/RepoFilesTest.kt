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
    fun readHonoursLimit() = run {
        setupRepo()
        file("a.txt", "one\ntwo\nthree\n")

        assertEquals("1: one\n", files.read(repo, "a.txt", limit = 1))
        assertEquals("2: two\n3: three\n", files.read(repo, "a.txt", startLine = 2, limit = 10))
        assertEquals("Empty file or start_line past end", files.read(repo, "a.txt", limit = 0))
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
    fun grepReturnsPathLineAndText() = run {
        setupRepo()
        file("src/a.txt", "alpha\nneedle here\n")
        file("b.txt", "nothing")

        val result = files.grep(repo, "needle", "")

        assertEquals("src/a.txt:2: needle here", slashes(result))
    }

    @Test
    fun grepFiltersByIncludeGlob() = run {
        setupRepo()
        file("src/a.kt", "needle in kt\n")
        file("src/b.txt", "needle in txt\n")
        file("sub/c.kt", "needle deep\n")

        val result = files.grep(repo, "needle", "", include = "*.kt")

        assertEquals("src/a.kt:1: needle in kt\nsub/c.kt:1: needle deep", slashes(result))
        assertEquals("No matches", files.grep(repo, "needle", "", include = "*.java"))
    }

    @Test
    fun grepMatchesRegex() = run {
        setupRepo()
        file("a.txt", "abc\nac\nxyz\n")

        val result = files.grep(repo, "a.c", "")

        assertEquals("a.txt:1: abc", slashes(result))
    }

    @Test
    fun grepRejectsInvalidRegex() = run {
        setupRepo()
        file("a.txt", "x")

        assertThrowsMessage("Invalid regex") { files.grep(repo, "([", "") }
    }

    @Test
    fun grepIsCaseSensitiveAndOffsetSkips() = run {
        setupRepo()
        file("a.txt", "hit\nhit\nhit\n")

        assertEquals("a.txt:1: hit\na.txt:2: hit\na.txt:3: hit", slashes(files.grep(repo, "hit", "")))
        assertEquals("a.txt:2: hit\na.txt:3: hit", slashes(files.grep(repo, "hit", "", offset = 1)))
        assertEquals("No matches", files.grep(repo, "HIT", ""))
    }

    @Test
    fun grepRejectsBlankPatternAndSkipsBinary() = run {
        setupRepo()
        File(root, "bin.dat").writeBytes(byteArrayOf(0xC3.toByte(), 0x28, 0x0A))
        file("ok.txt", "needle")

        assertThrowsMessage("Search pattern is required") { files.grep(repo, "", "") }
        assertEquals("ok.txt:1: needle", files.grep(repo, "needle", ""))
    }

    @Test
    fun grepRespectsGitignoreRules() = run {
        setupRepo()
        file(".gitignore", "# comment\n*.log\nbuild/\n!keep.log\n")
        file("a.log", "needle")
        file("sub/b.log", "needle")
        file("keep.log", "needle")
        file("src/c.txt", "needle")

        val lines = files.grep(repo, "needle", "").lines().sorted()
        assertEquals(listOf("keep.log:1: needle", "src/c.txt:1: needle"), lines)
    }

    @Test
    fun nestedGitignoreAppliesToSubtree() = run {
        setupRepo()
        file("sub/.gitignore", "secret.txt")
        file("sub/secret.txt", "needle")
        file("sub/open.txt", "needle")
        file("other/secret.txt", "needle")

        val lines = files.grep(repo, "needle", "").lines().sorted()
        assertEquals(listOf("other/secret.txt:1: needle", "sub/open.txt:1: needle"), lines)
    }

    @Test
    fun dirOnlyRuleIgnoresDirectoriesOnly() = run {
        setupRepo()
        file(".gitignore", "cache/\n")
        file("cache/data.txt", "needle")
        file("cache.txt", "needle")

        val lines = files.grep(repo, "needle", "").lines().sorted()
        assertEquals(listOf("cache.txt:1: needle"), lines)
    }

    @Test
    fun grepOrderIsStableAcrossFiles() = run {
        setupRepo()
        file("b.txt", "hit")
        file("a.txt", "hit\nhit")

        assertEquals("a.txt:2: hit\nb.txt:1: hit", slashes(files.grep(repo, "hit", "", offset = 1)))
    }

    @Test
    fun grepReportsTruncationAt200() = run {
        setupRepo()
        file("a.txt", (1..205).joinToString("\n") { "hit $it" })

        val lines = files.grep(repo, "hit", "").split("\n")
        assertEquals(201, lines.size)
        assertEquals("a.txt:1: hit 1", lines.first())
        assertEquals("a.txt:200: hit 200", lines[199])
        assertEquals("truncated: more matches, continue with offset=200", lines.last())

        assertEquals(
            "a.txt:201: hit 201\na.txt:202: hit 202\na.txt:203: hit 203\na.txt:204: hit 204\na.txt:205: hit 205",
            files.grep(repo, "hit", "", offset = 200),
        )
    }

    @Test
    fun grepReportsMissingPath() = run {
        setupRepo()
        file("a.txt", "x")

        assertThrowsMessage("Path not found: nope") { files.grep(repo, "x", "nope") }
    }

    @Test
    fun globMatchesFileNameAtAnyDepth() = run {
        setupRepo()
        file("a/one.kt", "x")
        file("b/c/two.kt", "x")
        file("b/three.txt", "x")

        assertEquals("a/one.kt\nb/c/two.kt", files.glob(repo, "*.kt", ""))
    }

    @Test
    fun globHonoursAnchoredPatternAndOffset() = run {
        setupRepo()
        file("src/main/A.kt", "x")
        file("src/test/B.kt", "x")
        file("other/C.kt", "x")

        assertEquals("src/main/A.kt\nsrc/test/B.kt", files.glob(repo, "src/**/*.kt", ""))
        assertEquals("src/test/B.kt", files.glob(repo, "src/**/*.kt", "", offset = 1))
    }

    @Test
    fun globMatchesCharacterClasses() = run {
        setupRepo()
        file("a.c", "x")
        file("b.h", "x")
        file("c.cpp", "x")
        file("d.txt", "x")

        assertEquals("a.c\nb.h", files.glob(repo, "*.[ch]", ""))
    }

    @Test
    fun globTreatsUnbalancedBracketsAsLiteral() = run {
        setupRepo()
        file("weird[.txt", "x")
        file("weird[]", "x")
        file("weird.txt", "x")

        assertEquals("weird[.txt", files.glob(repo, "weird[.txt", ""))
        assertEquals("weird[]", files.glob(repo, "weird[]", ""))
    }

    @Test
    fun globReturnsFilesOnlyAndSkipsIgnored() = run {
        setupRepo()
        file(".gitignore", "notes.txt\n")
        File(root, "build").mkdirs()
        file("build/output.txt", "x")
        File(root, "keep").mkdirs()
        file("keep/inside.txt", "x")
        file("notes.txt", "x")
        file("top.txt", "x")

        assertEquals(".gitignore\nkeep/inside.txt\ntop.txt", files.glob(repo, "*", ""))
    }

    @Test
    fun listHidesIgnoredEntries() = run {
        setupRepo()
        file(".gitignore", "*.tmp\n")
        File(root, "build").mkdirs()
        File(root, "keep").mkdirs()
        file("a.tmp", "x")
        file("b.txt", "x")

        val lines = files.list(repo, "").lines()
        assertTrue(lines.contains("keep/"))
        assertTrue(lines.contains("b.txt (1 bytes)"))
        assertFalse(lines.any { it.startsWith("build") })
        assertFalse(lines.any { it.startsWith("a.tmp") })
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
        assertThrowsMessage("Found multiple matches for old_text") { files.replace(repo, "dup.txt", "x", "y") }
        assertThrowsMessage("old_text not found in content") { files.replace(repo, "a.txt", "absent", "y") }
        assertThrowsMessage("old_text cannot be empty") { files.replace(repo, "a.txt", "", "y") }
    }

    @Test
    fun replaceAllReplacesEveryOccurrence() = run {
        setupRepo()
        file("dup.txt", "x x x")

        assertEquals("Replaced text in dup.txt", files.replace(repo, "dup.txt", "x", "y", replaceAll = true))
        assertEquals("y y y", File(root, "dup.txt").readText())

        assertThrowsMessage("old_text not found in content") { files.replace(repo, "dup.txt", "x", "y", replaceAll = true) }
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
        assertThrowsMessage("Access to .git is not allowed") { files.grep(repo, "core", ".git") }
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