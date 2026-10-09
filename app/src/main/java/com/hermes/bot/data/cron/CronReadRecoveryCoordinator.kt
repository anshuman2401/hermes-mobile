package com.hermes.bot.data.cron

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Read-only recovery. Writes never enter this retry lane. */
class CronReadRecoveryCoordinator(private val repository: CronRepository) {
    suspend fun list(
        canRetry: () -> Boolean,
        onRetry: (Int) -> Unit
    ): CronResult<List<CronJob>> {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val result = try {
                repository.list()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CronResult.Failure(CronFailure.UNAVAILABLE)
            }
            coroutineContext.ensureActive()
            if (result !is CronResult.Failure || result.reason != CronFailure.UNAVAILABLE ||
                attempt == RETRY_DELAYS.size || !canRetry()) return result
            onRetry(++attempt)
            delay(RETRY_DELAYS[attempt - 1])
            coroutineContext.ensureActive()
            if (!canRetry()) return result
        }
    }

    private companion object {
        val RETRY_DELAYS = listOf(1_000L, 2_000L, 4_000L)
    }
}
