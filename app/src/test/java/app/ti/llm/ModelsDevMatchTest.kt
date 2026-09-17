package app.ti.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class ModelsDevMatchTest {
    private val catalog = mapOf(
        "deepseek/deepseek-v4-flash" to ModelInfo(
            contextTokens = 999,
            maxInputTokens = null,
            reasoningMode = true,
            reasoningLevels = listOf("low", "high", "max"),
            inputPrice = 0.15,
            outputPrice = 0.6,
        ),
        "deepseek-v4-flash" to ModelInfo(
            contextTokens = 1_000_000,
            maxInputTokens = null,
            reasoningMode = true,
            reasoningLevels = null,
            inputPrice = 0.15,
            outputPrice = 0.6,
        ),
    )

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun fullIdMatchWins() {
        assertEquals(999, matchModelInfo(catalog, "deepseek/deepseek-v4-flash")?.contextTokens)
    }

    @Test
    fun fallsBackToLastSegment() {
        assertEquals(1_000_000, matchModelInfo(catalog, "vendor/deepseek-v4-flash")?.contextTokens)
    }

    @Test
    fun plainIdMatches() {
        assertEquals(1_000_000, matchModelInfo(catalog, "deepseek-v4-flash")?.contextTokens)
    }

    @Test
    fun missReturnsNull() {
        assertNull(matchModelInfo(catalog, "unknown/model-x"))
        assertNull(matchModelInfo(emptyMap(), "deepseek-v4-flash"))
    }

    @Test
    fun trimsWhitespaceBeforeMatching() {
        assertEquals(1_000_000, matchModelInfo(catalog, "  vendor/deepseek-v4-flash  ")?.contextTokens)
    }

    @Test
    fun cachedParsesCatalogFromDisk() = runBlocking {
        val client = ModelsDevClient(cacheFile(catalogJson()))

        val (fetchedAt, parsed) = checkNotNull(client.cached())

        assertEquals(1_700_000_000_000L, fetchedAt)
        val flagship = checkNotNull(parsed["acme/smart-1"])
        assertEquals(200_000, flagship.contextTokens)
        assertEquals(150_000, flagship.maxInputTokens)
        assertTrue(flagship.reasoningMode == true)
        assertEquals(listOf("low", "high"), flagship.reasoningLevels)
        assertEquals(1.5, flagship.inputPrice!!, 0.0001)
        assertEquals(3.0, flagship.outputPrice!!, 0.0001)
        assertEquals(listOf("text", "image"), flagship.inputModalities)
        assertEquals(listOf("text"), flagship.outputModalities)

        val toggle = checkNotNull(parsed["acme/toggle"])
        assertTrue(toggle.reasoningMode == true)
        assertNull(toggle.reasoningLevels)

        val plain = checkNotNull(parsed["acme/plain"])
        assertFalse(plain.reasoningMode == true)
    }

    @Test
    fun cachedKeepsFirstOccurrenceAndFallsBackToKeyAsId() = runBlocking {
        val client = ModelsDevClient(cacheFile(catalogJson()))

        val parsed = checkNotNull(client.cached()).second

        assertEquals(200_000, checkNotNull(parsed["acme/smart-1"]).contextTokens)
        assertEquals(1_000, checkNotNull(parsed["bare"]).contextTokens)
    }

    @Test
    fun cachedReturnsNullForMissingOrCorruptCache() = runBlocking {
        assertNull(ModelsDevClient(File(temp.root, "missing.json")).cached())
        assertNull(ModelsDevClient(cacheFile("not json")).cached())
        assertNull(ModelsDevClient(rawCache("""{"body":{}}""")).cached())
        assertNull(ModelsDevClient(rawCache("""{"fetchedAt":1}""")).cached())
    }

    private fun cacheFile(body: String): File =
        rawCache("""{"fetchedAt":1700000000000,"body":$body}""")

    private fun rawCache(text: String): File =
        File(temp.root, "cache-${UUID.randomUUID()}.json").apply { writeText(text) }

    private fun catalogJson() = """
        {
          "acme": {
            "models": {
              "acme/smart-1": {
                "id": "acme/smart-1",
                "limit": {"context": 200000, "input": 150000},
                "cost": {"input": 1.5, "output": 3},
                "reasoning": true,
                "reasoning_options": [{"type": "effort", "values": ["low", "high"]}],
                "modalities": {"input": ["text", "image"], "output": ["text"]}
              },
              "acme/toggle": {
                "id": "acme/toggle",
                "reasoning_options": [{"type": "toggle"}]
              },
              "acme/plain": {"id": "acme/plain"}
            }
          },
          "other": {
            "models": {
              "acme/smart-1": {"id": "acme/smart-1", "limit": {"context": 999}},
              "bare": {"limit": {"context": 1000}}
            }
          }
        }
    """.trimIndent()
}