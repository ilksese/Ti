package app.ti.llm

import app.ti.data.MessageEntity
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ChatResult(val content: String, val toolCalls: List<ToolCall>)

class OpenAiClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    suspend fun listModels(provider: ProviderEntity): List<String> = withContext(Dispatchers.IO) {
        val request = requestBuilder(provider, "${provider.baseUrl.trimEnd('/')}/models").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Models request failed (${response.code}): ${body.take(500)}" }
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .distinct()
                .sorted()
        }
    }

    suspend fun simpleChat(
        provider: ProviderEntity,
        model: ModelEntity,
        system: String,
        user: String,
        maxTokens: Int = 300,
    ): String = withContext(Dispatchers.IO) {
        val messages = buildJsonArray {
            add(messageJson("system", system))
            add(messageJson("user", user))
        }
        val body = baseBody(model, stream = false, maxTokensOverride = maxTokens).apply {
            put("messages", messages)
        }
        val request = postRequest(provider, body)
        val call = client.newCall(request)
        call.timeout().timeout(45, TimeUnit.SECONDS)
        call.execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Chat request failed (${response.code}): ${text.take(800)}" }
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        }
    }

    suspend fun streamChat(
        provider: ProviderEntity,
        model: ModelEntity,
        messages: List<MessageEntity>,
        systemPrompt: String,
        summary: String?,
        tools: JsonArray,
        onReasoning: () -> Unit,
        onDelta: (String) -> Unit,
    ): ChatResult = withContext(Dispatchers.IO) {
        val requestMessages = buildJsonArray {
            add(messageJson("system", systemPrompt))
            if (!summary.isNullOrBlank()) {
                add(messageJson("system", "Summary of earlier conversation:\n$summary"))
            }
            messages.filter { !it.compacted && it.role in setOf("user", "assistant", "tool") && it.status != "pending" }
                .forEach { message -> add(message.toApiJson()) }
        }
        val body = baseBody(model, stream = true).apply {
            put("messages", requestMessages)
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", JsonPrimitive("auto"))
            }
        }
        val request = postRequest(provider, body)
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "Chat request failed (${response.code}): ${response.body?.string().orEmpty().take(800)}"
            }
            val content = StringBuilder()
            val calls = linkedMapOf<Int, MutableToolCall>()
            val source = checkNotNull(response.body).source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val delta = json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: continue
                ((delta["reasoning"] ?: delta["reasoning_content"]) as? JsonPrimitive)
                    ?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { onReasoning() }
                delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                    content.append(it)
                    onDelta(it)
                }
                delta["tool_calls"]?.jsonArray?.forEach { element ->
                    val value = element.jsonObject
                    val index = value["index"]?.jsonPrimitive?.intOrNull ?: calls.size
                    val call = calls.getOrPut(index) { MutableToolCall() }
                    value["id"]?.jsonPrimitive?.contentOrNull?.let { call.id = it }
                    value["function"]?.jsonObject?.let { function ->
                        function["name"]?.jsonPrimitive?.contentOrNull?.let { call.name += it }
                        function["arguments"]?.jsonPrimitive?.contentOrNull?.let { call.arguments.append(it) }
                    }
                }
            }
            ChatResult(
                content = content.toString(),
                toolCalls = calls.values.map {
                    ToolCall(checkNotNull(it.id.ifBlank { null }) { "Tool call missing id" }, it.name, it.arguments.toString())
                },
            )
        }
    }

    private fun MessageEntity.toApiJson(): JsonObject = buildJsonObject {
        when (role) {
            "tool" -> {
                put("role", "tool")
                put("tool_call_id", checkNotNull(toolCallId))
                put("content", content)
            }
            "assistant" -> {
                put("role", "assistant")
                if (content.isBlank() && toolCallsJson != null) put("content", JsonNull) else put("content", content)
                toolCallsJson?.let { put("tool_calls", json.parseToJsonElement(it)) }
            }
            else -> {
                put("role", role)
                put("content", content)
            }
        }
    }

    private fun messageJson(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun baseBody(
        model: ModelEntity,
        stream: Boolean,
        maxTokensOverride: Int? = null,
    ) = buildJsonObject {
        put("model", model.modelId)
        put("stream", stream)
        model.temperature?.let { put("temperature", it) }
        model.topP?.let { put("top_p", it) }
        (maxTokensOverride ?: model.maxTokens)?.let { put("max_tokens", it) }
    }.toMutableMap()

    private fun postRequest(provider: ProviderEntity, body: Map<String, JsonElement>): Request {
        val payload = JsonObject(body).toString().toRequestBody(mediaType)
        return requestBuilder(provider, "${provider.baseUrl.trimEnd('/')}/chat/completions")
            .post(payload)
            .build()
    }

    private fun requestBuilder(provider: ProviderEntity, url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (provider.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${provider.apiKey}")
        provider.headers.lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) builder.header(line.take(separator).trim(), line.drop(separator + 1).trim())
        }
        return builder
    }

    private class MutableToolCall {
        var id = ""
        var name = ""
        val arguments = StringBuilder()
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
