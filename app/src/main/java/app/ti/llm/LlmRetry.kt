package app.ti.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal val LLM_RETRY_DELAYS_MS = listOf(0L, 5_000L, 12_000L, 20_000L, 30_000L)

internal suspend fun <T> withLlmRetry(
    onRetry: (retry: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    sleeper: suspend (Long) -> Unit = { delay(it) },
    block: suspend (attempt: Int) -> T,
): T {
    var lastError: Throwable? = null
    repeat(LLM_RETRY_DELAYS_MS.size + 1) { attempt ->
        if (attempt > 0) {
            val delayMs = LLM_RETRY_DELAYS_MS[attempt - 1]
            onRetry(attempt, delayMs, checkNotNull(lastError))
            sleeper(delayMs)
        }
        try {
            return block(attempt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
        }
    }
    throw checkNotNull(lastError)
}
