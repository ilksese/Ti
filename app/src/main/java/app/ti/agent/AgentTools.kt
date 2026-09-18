package app.ti.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val MUTATING_GIT_TOOLS = setOf(
    "git_commit",
    "git_pull",
    "git_push",
    "git_create_branch",
    "git_switch_branch",
    "git_delete_branch",
)

internal val NO_TOOLS: JsonArray = buildJsonArray {}

internal val TOOLS: JsonArray = buildJsonArray {
    add(tool("read", "Read a UTF-8 file with line numbers, up to 200KB", properties(
        "path" to stringProperty("Relative file path"),
        "start_line" to intProperty("One-based first line"),
        "limit" to intProperty("Maximum number of lines to read"),
    ), listOf("path")))
    add(tool("edit", "Performs exact string replacements in an existing file that was read earlier in this session; it cannot create files", properties(
        "path" to stringProperty("Relative file path"),
        "old_text" to stringProperty("Exact text to replace"),
        "new_text" to stringProperty("Replacement text"),
        "replace_all" to boolProperty("Replace every occurrence instead of requiring a unique match (default false)"),
    ), listOf("path", "old_text", "new_text")))
    add(tool("list_dir", "List up to 200 direct children of a directory", properties(
        "path" to stringProperty("Relative directory path; use empty string for root"),
        "offset" to intProperty("Number of entries to skip"),
    ), listOf("path")))
    add(tool("grep", "Search file contents with a case-sensitive regular expression (ripgrep-style), up to 200 matches", properties(
        "pattern" to stringProperty("Regular expression; escape literal special characters"),
        "path" to stringProperty("Relative directory to search; empty for root"),
        "offset" to intProperty("Number of matches to skip"),
        "include" to stringProperty("Optional glob to filter files (e.g. *.kt); * matches within one path segment, a pattern without / matches the file name at any depth"),
    ), listOf("pattern", "path")))
    add(tool("glob", "Find files matching a glob pattern (fd-style, files only), up to 200 results", properties(
        "pattern" to stringProperty("Glob pattern; * matches within one path segment, ** crosses segments, a pattern without / matches the file name at any depth (e.g. *.kt)"),
        "path" to stringProperty("Relative directory to search; empty for root"),
        "offset" to intProperty("Number of results to skip"),
    ), listOf("pattern", "path")))
    add(tool("write_file", "Create or replace a UTF-8 file", properties(
        "path" to stringProperty("Relative file path"),
        "content" to stringProperty("Complete file content"),
    ), listOf("path", "content")))
    add(tool("delete_file", "Delete one file", properties("path" to stringProperty("Relative file path")), listOf("path")))
    add(tool("move_file", "Move or rename one file", properties(
        "from" to stringProperty("Existing relative path"),
        "to" to stringProperty("New relative path"),
    ), listOf("from", "to")))
    add(tool("git_status", "Show Git working tree status", properties(), emptyList()))
    add(tool("git_diff", "Show unified Git diff", properties("path" to stringProperty("Optional relative file path")), emptyList()))
    add(tool("git_commit", "Stage all changes and commit", properties("message" to stringProperty("Commit message")), listOf("message")))
    add(tool("git_pull", "Fast-forward pull; requires a clean working tree", properties(), emptyList()))
    add(tool("git_push", "Push current branch without force", properties(), emptyList()))
    add(tool("git_list_branches", "List local branches", properties(), emptyList()))
    add(tool("git_create_branch", "Create a local branch", properties("name" to stringProperty("Branch name")), listOf("name")))
    add(tool("git_switch_branch", "Switch local branch", properties("name" to stringProperty("Branch name")), listOf("name")))
    add(tool("git_delete_branch", "Safely delete a merged local branch", properties("name" to stringProperty("Branch name")), listOf("name")))
}

private fun tool(name: String, description: String, properties: JsonObject, required: List<String>) =
    buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                put("additionalProperties", false)
            })
        })
    }

private fun properties(vararg values: Pair<String, JsonObject>) = buildJsonObject {
    values.forEach { (name, value) -> put(name, value) }
}

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun intProperty(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", 0)
}

private fun boolProperty(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}
