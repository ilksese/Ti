package app.ti.agent

import app.ti.data.RepositoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class RepoFiles {
    suspend fun list(repo: RepositoryEntity, path: String, offset: Int = 0): String = withContext(Dispatchers.IO) {
        val directory = resolve(repo, path)
        require(directory.isDirectory()) { "Not a directory: $path" }
        Files.list(directory).use { entries ->
            entries.filter { it.fileName.toString() != ".git" }.sorted().skip(offset.toLong()).limit(200).map { entry ->
                val relative = root(repo).relativize(entry).toString()
                if (Files.isDirectory(entry)) "$relative/" else "$relative (${Files.size(entry)} bytes)"
            }.collect(Collectors.toList()).joinToString("\n").ifBlank { "Empty directory" }
        }
    }

    suspend fun read(repo: RepositoryEntity, path: String, startLine: Int = 1): String = withContext(Dispatchers.IO) {
        val file = resolve(repo, path)
        require(Files.isRegularFile(file)) { "Not a file: $path" }
        val result = StringBuilder()
        var bytes = 0
        Files.newBufferedReader(file, StandardCharsets.UTF_8).useLines { lines ->
            lines.drop((startLine - 1).coerceAtLeast(0)).forEachIndexed { index, line ->
                val rendered = "${startLine + index}: $line\n"
                bytes += rendered.toByteArray().size
                if (bytes > 200_000) return@useLines
                result.append(rendered)
            }
        }
        result.toString().ifBlank { "Empty file or start_line past end" }
    }

    suspend fun search(
        repo: RepositoryEntity,
        query: String,
        path: String,
        offset: Int = 0,
    ): String = withContext(Dispatchers.IO) {
        require(query.isNotEmpty()) { "Search query is required" }
        val base = resolve(repo, path)
        var skipped = 0
        val matches = mutableListOf<String>()
        Files.walk(base).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext() && matches.size < 200) {
                val file = iterator.next()
                if (!Files.isRegularFile(file) || Files.size(file) > 2_000_000) continue
                if (root(repo).relativize(file).any { it.toString() == ".git" }) continue
                runCatching {
                    InputStreamReader(Files.newInputStream(file), strictUtf8()).buffered().useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            if (query in line) {
                                if (skipped++ < offset) return@forEachIndexed
                                if (matches.size < 200) {
                                    matches += "${root(repo).relativize(file)}:${index + 1}: ${line.take(500)}"
                                }
                            }
                        }
                    }
                }
            }
        }
        matches.joinToString("\n").ifBlank { "No matches" }
    }

    suspend fun write(repo: RepositoryEntity, path: String, content: String): String = withContext(Dispatchers.IO) {
        val file = resolve(repo, path, allowMissing = true)
        Files.createDirectories(checkNotNull(file.parent))
        Files.write(file, content.toByteArray(StandardCharsets.UTF_8))
        "Wrote ${content.toByteArray().size} bytes to $path"
    }

    suspend fun replace(repo: RepositoryEntity, path: String, old: String, new: String): String =
        withContext(Dispatchers.IO) {
            require(old.isNotEmpty()) { "old_text cannot be empty" }
            val file = resolve(repo, path)
            val content = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            val first = content.indexOf(old)
            require(first >= 0) { "old_text not found in $path" }
            require(content.indexOf(old, first + old.length) < 0) { "old_text is not unique in $path" }
            Files.write(file, content.replaceRange(first, first + old.length, new).toByteArray(StandardCharsets.UTF_8))
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

    private fun root(repo: RepositoryEntity): Path = Paths.get(repo.localPath).toRealPath()

    private fun resolve(repo: RepositoryEntity, value: String, allowMissing: Boolean = false): Path {
        val root = root(repo)
        val relative = Paths.get(value.ifBlank { "." })
        require(!relative.isAbsolute) { "Absolute paths are not allowed" }
        require(relative.none { it.toString() == ".git" }) { "Access to .git is not allowed" }
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root)) { "Path escapes the repository" }
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
