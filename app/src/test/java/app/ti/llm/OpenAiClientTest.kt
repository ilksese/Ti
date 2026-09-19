package app.ti.llm

import app.ti.agent.TOOLS
import app.ti.data.MessageEntity
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiClientTest {
    private lateinit var server: MockWebServer
    private val client = OpenAiClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val provider by lazy {
        ProviderEntity(
            id = "p1",
            name = "test",
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "sk-secret",
            headers = "",
            createdAt = 0,
        )
    }

    private val model = ModelEntity(id = "m1", providerId = "p1", modelId = "test-model", contextTokens = 1_000)

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun sse(vararg payloads: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(payloads.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun message(
        role: String,
        content: String,
        toolCallId: String? = null,
        toolCallsJson: String? = null,
        status: String = "complete",
        compacted: Boolean = false,
        id: String = role + content.length,
    ) = MessageEntity(
        id = id,
        sessionId = "s1",
        role = role,
        content = content,
        toolCallId = toolCallId,
        toolCallsJson = toolCallsJson,
        status = status,
        compacted = compacted,
        createdAt = 0,
    )

    private fun requestBody() = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun streamChatReassemblesContentReasoningAndFragmentedToolCalls() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"reasoning_content":"thinking"}}]}""",
                """{"choices":[{"delta":{"content":"Hel"}}]}""",
                """{"choices":[{"delta":{"content":"lo"}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"rea","arguments":"{\"pa"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"d","arguments":"th\":\"a.txt\"}"}}]}}]}""",
            ),
        )
        val reasoning = StringBuilder()
        val deltas = mutableListOf<String>()

        val result = client.streamChat(
            provider = provider,
            model = model,
            messages = emptyList(),
            systemPrompt = "sys",
            summary = null,
            tools = TOOLS,
            onReasoning = { reasoning.append(it) },
            onDelta = { deltas += it },
        )

        assertEquals("Hello", result.content)
        assertEquals(listOf("Hel", "lo"), deltas)
        assertEquals("thinking", reasoning.toString())
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_1", result.toolCalls[0].id)
        assertEquals("read", result.toolCalls[0].name)
        assertEquals("""{"path":"a.txt"}""", result.toolCalls[0].arguments)
    }

    @Test
    fun streamChatSendsSystemSummaryAndFiltersInactiveMessages() = runBlocking {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"}}]}"""))
        val toolCalls = """[{"id":"call_9","type":"function","function":{"name":"git_status","arguments":"{}"}}]"""

        client.streamChat(
            provider = provider,
            model = model,
            messages = listOf(
                message("user", "keep me"),
                message("assistant", "old", compacted = true),
                message("tool", "pending result", toolCallId = "t1", status = "pending"),
                message("assistant", "", toolCallsJson = toolCalls),
                message("tool", "done", toolCallId = "t2"),
            ),
            systemPrompt = "SYSTEM",
            summary = "EARLIER",
            tools = TOOLS,
            onReasoning = {},
            onDelta = {},
        )

        val body = requestBody()
        val messages = body["messages"]!!.jsonArray
        assertEquals(5, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("SYSTEM", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(messages[1].jsonObject["content"]!!.jsonPrimitive.content.contains("EARLIER"))
        assertEquals("keep me", messages[2].jsonObject["content"]!!.jsonPrimitive.content)

        val assistant = messages[3].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertTrue(assistant["content"] is JsonNull)
        assertEquals("call_9", assistant["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)

        val tool = messages[4].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("t2", tool["tool_call_id"]!!.jsonPrimitive.content)

        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(TOOLS.size, body["tools"]!!.jsonArray.size)
        assertTrue(body["tools"]!!.jsonArray.any {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "edit"
        })
    }

    @Test
    fun toolMessageRequiresToolCallId() = runBlocking {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"}}]}"""))
        val orphan = message("tool", "result")

        val error = runCatching {
            client.streamChat(provider, model, listOf(orphan), "sys", null, TOOLS, {}, {})
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun streamChatSurfacesHttpErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream exploded"))

        val error = runCatching {
            client.streamChat(provider, model, emptyList(), "sys", null, TOOLS, {}, {})
        }.exceptionOrNull()

        assertTrue(error!!.message!!.contains("Chat request failed (500)"))
        assertTrue(error.message!!.contains("upstream exploded"))
    }

    @Test
    fun streamChatFailsWhenToolCallHasNoId() = runBlocking {
        server.enqueue(sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"git_status","arguments":"{}"}}]}}]}"""))

        val error = runCatching {
            client.streamChat(provider, model, emptyList(), "sys", null, TOOLS, {}, {})
        }.exceptionOrNull()

        assertTrue(error!!.message!!.contains("Tool call missing id"))
    }

    @Test
    fun streamChatFailsWhenStreamEndsBeforeDone() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"partial\"}}]}\n\n"),
        )

        val error = runCatching {
            client.streamChat(provider, model, emptyList(), "sys", null, TOOLS, {}, {})
        }.exceptionOrNull()

        assertTrue(error!!.message!!.contains("Chat stream ended before [DONE]"))
    }

    @Test
    fun simpleChatUsesMaxTokensOverrideAndTrimsContent() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"  Short title  "}}]}""",
            ),
        )

        val title = client.simpleChat(provider, model, "system", "user", maxTokens = 40)

        assertEquals("Short title", title)
        val body = requestBody()
        assertFalse(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(40, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(2, body["messages"]!!.jsonArray.size)
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun listModelsSortsAndDeduplicates() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"data":[{"id":"b"},{"id":"a"},{"id":"b"},{"noid":"x"}]}"""),
        )

        assertEquals(listOf("a", "b"), client.listModels(provider))

        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer sk-secret", request.getHeader("Authorization"))
    }

    @Test
    fun authorizationHeaderOnlySentWhenApiKeyPresent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        client.listModels(provider.copy(apiKey = ""))

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun extraHeadersAreParsedFromMultilineConfig() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        client.listModels(provider.copy(headers = "X-Org: acme\nX-Trace: 42\nmalformed-line"))

        val request = server.takeRequest()
        assertEquals("acme", request.getHeader("X-Org"))
        assertEquals("42", request.getHeader("X-Trace"))
    }

    @Test
    fun modelSamplingParametersAreForwarded() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"content":"x"}}]}"""),
        )

        client.simpleChat(
            provider,
            model.copy(temperature = 0.2, topP = 0.9, maxTokens = 999),
            "system",
            "user",
            maxTokens = 123,
        )

        val body = requestBody()
        assertEquals(0.2, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals(0.9, body["top_p"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals(123, body["max_tokens"]!!.jsonPrimitive.int)
    }
}
