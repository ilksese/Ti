package app.ti.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LlmRetryTest {
    @Test
    fun retriesFiveTimesWithConfiguredDelays() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val result = withLlmRetry(sleeper = { delays += it }) {
            attempts++
            if (attempts < 6) error("temporary")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(6, attempts)
        assertEquals(listOf(0L, 5_000L, 12_000L, 20_000L, 30_000L), delays)
    }

    @Test
    fun doesNotRetryCancellation() {
        var attempts = 0

        assertThrows(CancellationException::class.java) {
            runBlocking {
                withLlmRetry(sleeper = {}) {
                    attempts++
                    throw CancellationException("cancelled")
                }
            }
        }
        assertEquals(1, attempts)
    }
}
