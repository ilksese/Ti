package app.ti.agent

import app.ti.data.RepositoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.PatternSyntaxException
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class RepoFiles {
    suspend fun list(repo: RepositoryEntity, path: String, offset: Int = 0): String = withContext(Dispatchers.IO) {
        val directory = resolve(repo, path)
        require(directory.isDirectory()) { "Not a directory: $path" }
        val rootPath = root(repo)
        val ignore = IgnoreFilter(rootPath)
        Files.list(directory).use { entries ->
            entries.filter { entry -> !ignore.isIgnored(rootPath.relativize(entry), Files.isDirectory(entry)) }
                .sorted()
                .skip(offset.toLong())
                .limit(200)
                .map { entry ->
                    val relative = rootPath.relativize(entry).asPosix()
                    if (Files.isDirectory(entry)) "$relative/" else "$relative (${Files.size(entry)} bytes)"
                }
                .collect(Collectors.toList())
                .joinToString("\n")
                .ifBlank { "Empty directory" }
        }
    }

    suspend fun read(repo: RepositoryEntity, path: String, startLine: Int = 1, limit: Int = Int.MAX_VALUE): String = withContext(Dispatchers.IO) {
        val file = resolve(repo, path)
        require(Files.isRegularFile(file)) { "Not a file: $path" }
        val result = StringBuilder()
        var bytes = 0
        Files.newBufferedReader(file, StandardCharsets.UTF_8).useLines { lines ->
            lines.drop((startLine - 1).coerceAtLeast(0)).take(limit.coerceAtLeast(0)).forEachIndexed { index, line ->
                val rendered = "${startLine + index}: $line\n"
                bytes += rendered.toByteArray().size
                if (bytes > 200_000) return@useLines
                result.append(rendered)
            }
        }
        result.toString().ifBlank { "Empty file or start_line past end" }
    }

    suspend fun grep(
        repo: RepositoryEntity,
        pattern: String,
        path: String,
        offset: Int = 0,
        include: String? = null,
    ): String = withContext(Dispatchers.IO) {
        require(pattern.isNotEmpty()) { "Search pattern is required" }
        val regex = try {
            Regex(pattern)
        } catch (e: PatternSyntaxException) {
            error("Invalid regex: ${e.message}")
        }
        val base = resolve(repo, path)
        val rootPath = root(repo)
        val ignore = IgnoreFilter(rootPath)
        val includeMatcher = include?.takeIf { it.isNotBlank() }?.let { GlobMatcher(it) }
        val candidates = mutableListOf<Path>()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != base && ignore.isIgnored(rootPath.relativize(dir), true)) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = rootPath.relativize(file)
                if (Files.isRegularFile(file) && Files.size(file) <= 2_000_000) {
                    if (!ignore.isIgnored(rel, false) && (includeMatcher == null || includeMatcher.matches(base.relativize(file).asPosix()))) candidates.add(file)
                }
                return FileVisitResult.CONTINUE
            }
        })
        var skipped = 0
        var more = false
        val matches = mutableListOf<String>()
        for (file in candidates.sortedBy { rootPath.relativize(it).asPosix() }) {
            if (more) break
            val rel = rootPath.relativize(file)
            runCatching {
                InputStreamReader(Files.newInputStream(file), strictUtf8()).buffered().useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (!regex.containsMatchIn(line)) return@forEachIndexed
                        if (matches.size < 200) {
                            if (skipped++ < offset) return@forEachIndexed
                            matches.add("${rel.asPosix()}:${index + 1}: ${line.take(500)}")
                        } else {
                            more = true
                            return@forEachIndexed
                        }
                    }
                }
            }
        }
        val body = matches.joinToString("\n")
        when {
            body.isEmpty() -> "No matches"
            more -> "$body\ntruncated: more matches, continue with offset=${offset + matches.size}"
            else -> body
        }
    }

    suspend fun glob(
        repo: RepositoryEntity,
        pattern: String,
        path: String,
        offset: Int = 0,
    ): String = withContext(Dispatchers.IO) {
        require(pattern.isNotEmpty()) { "Glob pattern is required" }
        val base = resolve(repo, path)
        val rootPath = root(repo)
        val ignore = IgnoreFilter(rootPath)
        val matcher = GlobMatcher(pattern)
        val results = mutableListOf<String>()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != base && ignore.isIgnored(rootPath.relativize(dir), true)) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!Files.isRegularFile(file)) return FileVisitResult.CONTINUE
                val rel = rootPath.relativize(file)
                if (ignore.isIgnored(rel, false)) return FileVisitResult.CONTINUE
                if (matcher.matches(base.relativize(file).asPosix())) results += rel.asPosix()
                return FileVisitResult.CONTINUE
            }
        })
        results.sorted().drop(offset).take(200).joinToString("\n").ifBlank { "No matches" }
    }

    suspend fun write(repo: RepositoryEntity, path: String, content: String): String = withContext(Dispatchers.IO) {
        val file = resolve(repo, path, allowMissing = true)
        Files.createDirectories(checkNotNull(file.parent))
        Files.write(file, content.toByteArray(StandardCharsets.UTF_8))
        "Wrote ${content.toByteArray().size} bytes to $path"
    }

    suspend fun replace(
        repo: RepositoryEntity,
        path: String,
        old: String,
        new: String,
        replaceAll: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        require(old.isNotEmpty()) { "old_text cannot be empty" }
        val file = resolve(repo, path)
        val content = strictUtf8().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString()
        val first = content.indexOf(old)
        require(first >= 0) { "old_text not found in content" }
        val unique = content.indexOf(old, first + old.length) < 0
        require(replaceAll || unique) {
            "Found multiple matches for old_text. Provide more surrounding text to make it unique, or set replace_all=true."
        }
        val updated = if (replaceAll) content.replace(old, new) else content.replaceRange(first, first + old.length, new)
        Files.write(file, updated.toByteArray(StandardCharsets.UTF_8))
        "Replaced text in $path"
    }

    suspend fun delete(repo: RepositoryEntity, path: String): String = withContext(Dispatchers.IO) {
        val file = resolve(repo, path)
        require(!file.isDirectory()) { "delete_file only deletes files" }
        Files.delete(file)
        "Deleted $path"
    }

    suspend fun move(repo: RepositoryEntity, from: String, to: String): String = withContext(Dispatchers.IO) {
        val source = resolve(repo, from)
        val target = resolve(repo, to, allowMissing = true)
        require(!target.exists()) { "Target already exists: $to" }
        Files.createDirectories(checkNotNull(target.parent))
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        "Moved $from to $to"
    }

    private class GlobMatcher(pattern: String) {
        private val regex: Regex

        init {
            var t = pattern.trimEnd('/')
            if (t.startsWith("/")) t = t.substring(1)
            val anchored = t.contains("/")
            regex = Regex((if (anchored) "^" else "^(?:.*/)?") + globToRegex(t) + "$")
        }

        fun matches(relativeToBase: String): Boolean = regex.matches(relativeToBase)
    }

    private fun root(repo: RepositoryEntity): Path = Paths.get(repo.localPath).toRealPath()

    private fun resolve(repo: RepositoryEntity, value: String, allowMissing: Boolean = false): Path {
        val root = root(repo)
        val relative = Paths.get(value.ifBlank { "." })
        require(!relative.isAbsolute) { "Absolute paths are not allowed" }
        require(relative.none { it.toString() == ".git" }) { "Access to .git is not allowed" }
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root)) { "Path escapes the repository" }
        if (!allowMissing) require(target.exists()) { "Path not found: $value" }
        if (!allowMissing || target.exists()) {
            require(target.toRealPath().startsWith(root)) { "Path escapes the repository" }
        } else {
            var parent = target.parent
            while (parent != null && !parent.exists()) parent = parent.parent
            require(parent?.toRealPath()?.startsWith(root) == true) { "Path escapes the repository" }
        }
        return target
    }

    private fun strictUtf8() = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
}
