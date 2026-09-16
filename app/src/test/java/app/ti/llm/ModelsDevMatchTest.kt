package app.ti.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    }
}
