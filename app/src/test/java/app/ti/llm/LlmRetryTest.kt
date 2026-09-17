package app.ti.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun succeedsOnFirstAttemptWithoutDelayOrCallback() = runBlocking {
        var retries = 0

        val result = withLlmRetry(
            onRetry = { _, _, _ -> retries++ },
            sleeper = { error("should not sleep") },
        ) { attempt ->
            assertEquals(0, attempt)
            "first"
        }

        assertEquals("first", result)
        assertEquals(0, retries)
    }

    @Test
    fun reportsEachRetryWithItsErrorAndDelay() = runBlocking {
        var attempts = 0
        val reported = mutableListOf<Triple<Int, Long, String>>()

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withLlmRetry(
                    onRetry = { retry, delayMs, throwable ->
                        reported += Triple(retry, delayMs, throwable.message.orEmpty())
                    },
                    sleeper = {},
                ) {
                    attempts++
                    error("boom $attempts")
                }
            }
        }

        assertEquals(6, attempts)
        assertEquals("boom 6", error.message)
        assertEquals(listOf(1, 2, 3, 4, 5), reported.map { it.first })
        assertEquals(listOf(0L, 5_000L, 12_000L, 20_000L, 30_000L), reported.map { it.second })
        assertEquals("boom 1", reported.first().third)
        assertEquals("boom 5", reported.last().third)
    }

    @Test
    fun retriesNonCancellationThrowablesIncludingErrors() = runBlocking {
        var attempts = 0

        val error = runCatching {
            withLlmRetry(sleeper = {}) {
                attempts++
                throw AssertionError("still retried")
            }
        }.exceptionOrNull()

        assertTrue(error is AssertionError)
        assertEquals(6, attempts)
    }
}