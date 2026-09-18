package app.ti.agent

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal fun Path.asPosix(): String = toString().replace('\\', '/')

internal fun globToRegex(pattern: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        when {
            pattern.startsWith("**/", i) -> {
                out.append("(?:.*/)?")
                i += 3
            }
            pattern.startsWith("**", i) -> {
                out.append(".*")
                i += 2
            }
            pattern[i] == '*' -> {
                out.append("[^/]*")
                i++
            }
            pattern[i] == '?' -> {
                out.append("[^/]")
                i++
            }
            pattern[i] == '[' -> {
                val first = if (pattern.length > i + 1 && (pattern[i + 1] == '!' || pattern[i + 1] == '^')) i + 2 else i + 1
                val end = pattern.indexOf(']', first)
                if (end > first) {
                    out.append('[').append(pattern.substring(i + 1, end).replaceFirst("!", "^")).append(']')
                    i = end + 1
                } else {
                    out.append(Regex.escape("["))
                    i++
                }
            }
            else -> {
                out.append(Regex.escape(pattern[i].toString()))
                i++
            }
        }
    }
    return out.toString()
}

internal class IgnoreFilter(private val root: Path) {
    companion object {
        val HARD_SKIP = setOf(".git", "build", ".gradle", ".kotlin", ".idea", ".vscode", "node_modules", "out")
    }

    private class Rule(val regex: Regex, val dirOnly: Boolean, val negate: Boolean)

    private val rulesByDir = mutableMapOf<Path, List<Rule>>()

    fun isIgnored(rel: Path, isDirectory: Boolean): Boolean {
        if (rel.nameCount == 0) return false
        if (rel.fileName.toString() in HARD_SKIP) return true
        var matched = false
        var ignored = false
        for (d in 0..rel.nameCount - 1) {
            val dir = if (d == 0) root else root.resolve(rel.subpath(0, d))
            for (rule in rules(dir)) {
                if (rule.dirOnly && !isDirectory) continue
                if (rule.regex.matches(rel.subpath(d, rel.nameCount).asPosix())) {
                    matched = true
                    ignored = !rule.negate
                }
            }
        }
        return matched && ignored
    }

    private fun rules(dir: Path): List<Rule> = rulesByDir.getOrPut(dir) {
        val file = dir.resolve(".gitignore")
        if (!Files.isRegularFile(file)) return@getOrPut emptyList()
        runCatching {
            Files.readAllLines(file, StandardCharsets.UTF_8).mapNotNull { parse(it) }
        }.getOrDefault(emptyList())
    }

    private fun parse(line: String): Rule? {
        var text = line.trimEnd()
        if (text.isEmpty() || text.startsWith("#")) return null
        var negate = false
        if (text.startsWith("!")) {
            negate = true
            text = text.substring(1)
        }
        var dirOnly = false
        if (text.endsWith("/")) {
            dirOnly = true
            text = text.dropLast(1)
        }
        if (text.isEmpty()) return null
        val anchored = text.startsWith("/") || text.contains("/")
        if (text.startsWith("/")) text = text.substring(1)
        val body = globToRegex(text)
        return Rule(Regex(if (anchored) "^$body$" else "^(?:.*/)?$body$"), dirOnly, negate)
    }
}
