package app.ti.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ModelInfo(
    val contextTokens: Int? = null,
    val maxInputTokens: Int? = null,
    val reasoningMode: Boolean? = null,
    val reasoningLevels: List<String>? = null,
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
)

private const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L
private val catalogJson = Json { ignoreUnknownKeys = true }

class ModelsDevClient(private val cacheFile: File) {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var memory: Pair<Long, Map<String, ModelInfo>>? = null

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("https://models.dev/api.json").build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "models.dev request failed (${response.code})" }
                val body = response.body?.string().orEmpty()
                catalogJson.parseToJsonElement(body)
                cacheFile.writeText("""{"fetchedAt":${System.currentTimeMillis()},"body":$body}""")
            }
            memory = null
            true
        }.getOrDefault(false)
    }

    suspend fun refreshIfStale(maxAgeMs: Long = TWO_HOURS_MS) {
        val fetchedAt = cached()?.first
        if (fetchedAt == null || System.currentTimeMillis() - fetchedAt >= maxAgeMs) refresh()
    }

    suspend fun cached(): Pair<Long, Map<String, ModelInfo>>? {
        memory?.let { return it }
        return withContext(Dispatchers.IO) {
            val text = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()
                ?: return@withContext null
            val envelope = runCatching { catalogJson.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return@withContext null
            val fetchedAt = envelope["fetchedAt"]?.jsonPrimitive?.longOrNull ?: return@withContext null
            val body = envelope["body"] ?: return@withContext null
            (fetchedAt to parseCatalog(body)).also { memory = it }
        }
    }
}

internal fun matchModelInfo(catalog: Map<String, ModelInfo>, modelId: String): ModelInfo? {
    val id = modelId.trim()
    return catalog[id] ?: catalog[id.substringAfterLast('/')]
}

private fun parseCatalog(root: JsonElement): Map<String, ModelInfo> {
    val providers = root as? JsonObject ?: return emptyMap()
    val catalog = linkedMapOf<String, ModelInfo>()
    providers.values.forEach { providerElement ->
        val provider = providerElement as? JsonObject ?: return@forEach
        val models = provider["models"] as? JsonObject ?: return@forEach
        models.forEach { (key, value) ->
            val model = value as? JsonObject ?: return@forEach
            val id = model["id"]?.jsonPrimitive?.contentOrNull ?: key
            if (id !in catalog) catalog[id] = parseModelInfo(model)
        }
    }
    return catalog
}

private fun parseModelInfo(model: JsonObject): ModelInfo {
    var toggle = false
    var levels: List<String>? = null
    (model["reasoning_options"] as? JsonArray)?.forEach { option ->
        val value = option.jsonObject
        when (value["type"]?.jsonPrimitive?.contentOrNull) {
            "toggle" -> toggle = true
            "effort" -> levels = (value["values"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
    }
    val modalities = model["modalities"] as? JsonObject
    return ModelInfo(
        contextTokens = model["limit"]?.jsonObject?.get("context")?.jsonPrimitive?.intOrNull,
        maxInputTokens = model["limit"]?.jsonObject?.get("input")?.jsonPrimitive?.intOrNull,
        reasoningMode = model["reasoning"]?.jsonPrimitive?.booleanOrNull == true || toggle,
        reasoningLevels = levels?.takeIf { it.isNotEmpty() },
        inputPrice = model["cost"]?.jsonObject?.get("input")?.jsonPrimitive?.doubleOrNull,
        outputPrice = model["cost"]?.jsonObject?.get("output")?.jsonPrimitive?.doubleOrNull,
        inputModalities = modalities?.list("input"),
        outputModalities = modalities?.list("output"),
    )
}

private fun JsonObject.list(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.takeIf { it.isNotEmpty() }
