package app.ti.agent

import app.ti.data.MessageEntity
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.RepositoryEntity
import app.ti.data.SessionEntity
import app.ti.data.TiDao
import app.ti.git.GitService
import app.ti.git.withGlobalGitToken
import app.ti.llm.OpenAiClient
import app.ti.llm.ToolCall
import app.ti.llm.withLlmRetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AgentRunState(
    val running: Boolean = false,
    val partial: String = "",
    val error: String? = null,
    val pendingToolCallId: String? = null,
    val retryText: String? = null,
)

class AgentRunManager(
    private val dao: TiDao,
    private val llm: OpenAiClient,
    private val files: RepoFiles,
    private val git: GitService,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val lastTimestamp = AtomicLong(System.currentTimeMillis())
    private val mutableStates = MutableStateFlow<Map<String, AgentRunState>>(emptyMap())
    val states: StateFlow<Map<String, AgentRunState>> = mutableStates.asStateFlow()

    fun send(sessionId: String, input: String) {
        if (input.isBlank() || jobs[sessionId]?.isActive == true) return
        jobs[sessionId] = scope.launch {
            setState(sessionId, AgentRunState(running = true))
            dao.updateSessionStatus(sessionId, "running")
            runCatching {
                if (input.trim() == "/compact") compact(sessionId, force = true)
                else runAgent(sessionId, input)
            }.onFailure { error ->
                val message = error.message ?: error::class.simpleName.orEmpty()
                dao.saveMessage(event(sessionId, "Error: $message", "error"))
                dao.updateSessionStatus(sessionId, "error")
                setState(sessionId, AgentRunState(error = message))
            }.onSuccess {
                dao.updateSessionStatus(sessionId, "idle")
                setState(sessionId, AgentRunState())
            }
        }
    }

    fun resolveApproval(toolCallId: String, approved: Boolean) {
        approvals.remove(toolCallId)?.complete(approved)
    }

    private suspend fun runAgent(sessionId: String, input: String) {
        var session = requireNotNull(dao.session(sessionId)) { "Session not found" }
        val repo = requireNotNull(dao.repository(session.repositoryId)) { "Repository not found" }
        val model = requireNotNull(dao.model(session.modelId)) { "Model not found" }
        val provider = requireNotNull(dao.provider(model.providerId)) { "Provider not found" }
        dao.saveMessage(message(sessionId, "user", input))

        if (!session.titleGenerated) generateTitle(sessionId, provider, model, input)

        repeat(20) {
            compact(sessionId, force = false)
            session = requireNotNull(dao.session(sessionId))
            var partial = ""
            setState(sessionId, AgentRunState(running = true))
            val result = withLlmRetry(
                onRetry = { retry, delayMs, _ ->
                    partial = ""
                    val wait = if (delayMs == 0L) "now" else "in ${delayMs / 1_000}s"
                    setState(sessionId, AgentRunState(running = true, retryText = "Retry $retry/5 $wait"))
                },
            ) { attempt ->
                partial = ""
                setState(
                    sessionId,
                    AgentRunState(
                        running = true,
                        retryText = if (attempt == 0) null else "Retry $attempt/5",
                    ),
                )
                llm.streamChat(
                    provider = provider,
                    model = model,
                    messages = dao.messages(sessionId),
                    systemPrompt = systemPrompt(repo),
                    summary = session.summary,
                    tools = TOOLS,
                    onReasoning = {
                        if (partial.isBlank()) setState(sessionId, AgentRunState(running = true, retryText = "Thinking..."))
                    },
                    onDelta = { delta ->
                        partial += delta
                        setState(sessionId, AgentRunState(running = true, partial = partial))
                    },
                )
            }
            val toolCallsJson = result.toolCalls.takeIf { it.isNotEmpty() }?.let(::encodeToolCalls)
            dao.saveMessage(
                message(
                    sessionId = sessionId,
                    role = "assistant",
                    content = result.content,
                    toolCallsJson = toolCallsJson,
                ),
            )
            if (result.toolCalls.isEmpty()) return
            result.toolCalls.forEach { call -> runTool(sessionId, repo, call) }
        }
        error("Agent stopped after 20 tool rounds")
    }

    private fun generateTitle(
        sessionId: String,
        provider: ProviderEntity,
        model: ModelEntity,
        input: String,
    ) {
        scope.launch {
            val title = runCatching {
                withLlmRetry {
                    llm.simpleChat(
                        provider,
                        model,
                        "Create a short title of at most six words. Return only the title.",
                        input,
                        40,
                    )
                }.lineSequence().firstOrNull().orEmpty().trim(' ', '"', '\'', '#').take(60)
            }.getOrDefault(input.lineSequence().firstOrNull().orEmpty().take(60))
            if (dao.session(sessionId)?.titleGenerated == false) {
                dao.updateSessionTitle(sessionId, title.ifBlank { "New session" })
            }
        }
    }

    private suspend fun runTool(sessionId: String, repo: RepositoryEntity, call: ToolCall) {
        val toolMessage = message(
            sessionId = sessionId,
            role = "tool",
            content = call.arguments,
            name = call.name,
            toolCallId = call.id,
            status = if (call.name in MUTATING_GIT_TOOLS) "pending" else "running",
        )
        dao.saveMessage(toolMessage)
        if (call.name in MUTATING_GIT_TOOLS) {
            val decision = CompletableDeferred<Boolean>()
            approvals[call.id] = decision
            dao.updateSessionStatus(sessionId, "waiting")
            setState(sessionId, AgentRunState(running = true, pendingToolCallId = call.id))
            if (!decision.await()) {
                dao.finishTool(toolMessage.id, "User rejected this Git operation", "rejected")
                dao.updateSessionStatus(sessionId, "running")
                return
            }
        }
        val result = runCatching { executeTool(repo, call) }
        dao.finishTool(
            toolMessage.id,
            result.getOrElse { "Error: ${it.message ?: it::class.simpleName}" }.take(200_000),
            if (result.isSuccess) "complete" else "error",
        )
        dao.updateSessionStatus(sessionId, "running")
    }

    private suspend fun executeTool(repo: RepositoryEntity, call: ToolCall): String {
        val args = json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject
        return when (call.name) {
            "list_directory" -> files.list(repo, args.string("path"), args.integer("offset"))
            "read_file" -> files.read(repo, args.string("path"), args.integer("start_line", 1))
            "search_text" -> files.search(repo, args.string("query"), args.string("path"), args.integer("offset"))
            "write_file" -> files.write(repo, args.string("path"), args.string("content"))
            "replace_text" -> files.replace(repo, args.string("path"), args.string("old_text"), args.string("new_text"))
            "delete_file" -> files.delete(repo, args.string("path"))
            "move_file" -> files.move(repo, args.string("from"), args.string("to"))
            "git_status" -> git.status(repo)
            "git_diff" -> git.diff(repo, args.optionalString("path"))
            "git_commit" -> {
                val authorName = dao.setting("git_author_name")?.value.orEmpty()
                val authorEmail = dao.setting("git_author_email")?.value.orEmpty()
                require(authorName.isNotBlank() && authorEmail.isNotBlank()) { "Git author is not configured" }
                "Committed ${git.commit(repo, args.string("message"), authorName, authorEmail)}"
            }
            "git_pull" -> git.pull(dao.withGlobalGitToken(repo))
            "git_push" -> git.push(dao.withGlobalGitToken(repo))
            "git_list_branches" -> git.branches(repo).joinToString("\n")
            "git_create_branch" -> "Created ${git.createBranch(repo, args.string("name"))}"
            "git_switch_branch" -> "Switched to ${git.switchBranch(repo, args.string("name"))}"
            "git_delete_branch" -> "Deleted ${git.deleteBranch(repo, args.string("name"))}"
            else -> error("Unknown tool: ${call.name}")
        }
    }

    private suspend fun compact(sessionId: String, force: Boolean) {
        val session = requireNotNull(dao.session(sessionId))
        val model = requireNotNull(dao.model(session.modelId))
        val provider = requireNotNull(dao.provider(model.providerId))
        val active = dao.messages(sessionId).filter { !it.compacted && it.role in setOf("user", "assistant", "tool") }
        if (!force && estimateTokens(active, session.summary) < model.contextTokens * 0.75) return
        val conversationPositions = active.withIndex().filter { it.value.role in setOf("user", "assistant") }
        if (conversationPositions.size <= 6) {
            if (force) dao.saveMessage(event(sessionId, "Nothing to compact"))
            return
        }
        var keepIndex = conversationPositions[conversationPositions.size - 6].index
        while (keepIndex > 0 && active[keepIndex].role != "user") keepIndex--
        val old = active.take(keepIndex)
        if (old.isEmpty()) return
        val transcript = buildString {
            session.summary?.let { append("Previous summary:\n$it\n\n") }
            old.forEach { append(it.role).append(": ").append(it.content.take(20_000)).append('\n') }
        }
        val summary = withLlmRetry(
            onRetry = { retry, delayMs, _ ->
                val wait = if (delayMs == 0L) "now" else "in ${delayMs / 1_000}s"
                setState(sessionId, AgentRunState(running = true, retryText = "Retry $retry/5 $wait"))
            },
        ) {
            llm.simpleChat(
                provider,
                model,
                "Summarize this coding session for another agent. Preserve user goals, decisions, files changed, errors, and pending work.",
                transcript,
                1_500,
            )
        }
        check(summary.isNotBlank()) { "Compaction returned an empty summary" }
        dao.compactMessages(old.map { it.id })
        dao.updateSessionSummary(sessionId, summary, old.maxOf { it.createdAt })
        dao.saveMessage(event(sessionId, "Context compacted"))
    }

    private fun estimateTokens(messages: List<MessageEntity>, summary: String?): Int {
        fun estimate(value: String): Int {
            var ascii = 0
            var other = 0
            value.forEach { if (it.code < 128) ascii++ else other++ }
            return ascii / 4 + other
        }
        val total = estimate(summary.orEmpty()) + messages.sumOf {
            estimate(it.content) + estimate(it.toolCallsJson.orEmpty()) + 12
        }
        return (total * 1.2).toInt()
    }

    private fun setState(sessionId: String, state: AgentRunState) {
        mutableStates.value = mutableStates.value.toMutableMap().apply { put(sessionId, state) }
    }

    private fun message(
        sessionId: String,
        role: String,
        content: String,
        name: String? = null,
        toolCallId: String? = null,
        toolCallsJson: String? = null,
        status: String = "complete",
    ) = MessageEntity(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        role = role,
        content = content,
        name = name,
        toolCallId = toolCallId,
        toolCallsJson = toolCallsJson,
        status = status,
        createdAt = nextTimestamp(),
    )

    private fun event(sessionId: String, content: String, status: String = "complete") =
        message(sessionId, "event", content, status = status)

    private fun nextTimestamp(): Long {
        while (true) {
            val previous = lastTimestamp.get()
            val next = maxOf(System.currentTimeMillis(), previous + 1)
            if (lastTimestamp.compareAndSet(previous, next)) return next
        }
    }

    private fun encodeToolCalls(calls: List<ToolCall>) = buildJsonArray {
        calls.forEach { call ->
            add(buildJsonObject {
                put("id", call.id)
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", call.name)
                    put("arguments", call.arguments)
                })
            })
        }
    }.toString()

    private fun systemPrompt(repo: RepositoryEntity) = """
        You are Ti, a code agent working in the repository ${repo.name}.
        Inspect relevant files before editing. Use tools instead of inventing file contents.
        Keep changes minimal and scoped to the user's request. You cannot run shell commands, builds, or tests.
        File edits execute immediately. Mutating Git tools require user approval.
        Paths are relative to the repository root and .git is inaccessible.
        Finish with a concise summary after the work is complete.
    """.trimIndent()

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull ?: error("Missing argument: $name")

    private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.integer(name: String, default: Int = 0): Int =
        this[name]?.jsonPrimitive?.intOrNull ?: default
}
