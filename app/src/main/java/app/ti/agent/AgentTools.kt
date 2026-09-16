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

internal val TOOLS: JsonArray = buildJsonArray {
    add(tool("list_directory", "List up to 200 direct children of a directory", properties(
        "path" to stringProperty("Relative directory path; use empty string for root"),
        "offset" to intProperty("Number of entries to skip"),
    ), listOf("path")))
    add(tool("read_file", "Read a UTF-8 file with line numbers, up to 200KB", properties(
        "path" to stringProperty("Relative file path"),
        "start_line" to intProperty("One-based first line"),
    ), listOf("path")))
    add(tool("search_text", "Search literal text in UTF-8 repository files, up to 200 results", properties(
        "query" to stringProperty("Literal case-sensitive text"),
        "path" to stringProperty("Relative path to search; empty for root"),
        "offset" to intProperty("Number of matches to skip"),
    ), listOf("query", "path")))
    add(tool("write_file", "Create or replace a UTF-8 file", properties(
        "path" to stringProperty("Relative file path"),
        "content" to stringProperty("Complete file content"),
    ), listOf("path", "content")))
    add(tool("replace_text", "Replace one unique exact text occurrence", properties(
        "path" to stringProperty("Relative file path"),
        "old_text" to stringProperty("Unique exact text"),
        "new_text" to stringProperty("Replacement text"),
    ), listOf("path", "old_text", "new_text")))
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
