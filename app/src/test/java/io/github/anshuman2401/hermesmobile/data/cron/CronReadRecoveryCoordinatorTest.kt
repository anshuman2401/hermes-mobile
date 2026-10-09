package io.github.anshuman2401.hermesmobile.data.cron

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CronReadRecoveryCoordinatorTest {
    private class Repo(val read: suspend () -> CronResult<List<CronJob>>) : CronRepository {
        var writes = 0
        override suspend fun list() = read()
        override suspend fun runs(job: CronJob) = CronResult.Success(emptyList<CronRun>())
        override suspend fun save(original: CronJob?, profile: String, name: String, prompt: String, schedule: String): CronResult<Unit> {
            writes++
            return CronResult.Success(Unit)
        }
        override suspend fun act(job: CronJob, action: CronAction): CronResult<Unit> {
            writes++
            return CronResult.Success(Unit)
        }
    }

    @Test fun thrownReadFailureIsRetriedWithBoundedReadOnlyBackoff() = runTest {
        var calls = 0
        val startedAt = testScheduler.currentTime
        val times = mutableListOf<Long>()
        val attempts = mutableListOf<Int>()
        val repo = Repo {
            times += testScheduler.currentTime - startedAt
            if (++calls < 4) throw IOException("Synthetic unavailable read")
            CronResult.Success(emptyList())
        }
        val result = CronReadRecoveryCoordinator(repo).list({ true }, { attempts += it })
        assertEquals(CronResult.Success(emptyList<CronJob>()), result)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), times)
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(0, repo.writes)
    }

    @Test fun cancellationIsPropagatedWithoutRetry() = runTest {
        var attempts = 0
        val repo = Repo { throw CancellationException("Synthetic cancellation") }
        try {
            CronReadRecoveryCoordinator(repo).list({ true }, { attempts++ })
            fail("Cancellation must escape")
        } catch (_: CancellationException) {
            assertEquals(0, attempts)
            assertEquals(0, repo.writes)
        }
    }

    @Test fun lostAdmissionDuringBackoffPreventsNextExchange() = runTest {
        var allowed = true
        var calls = 0
        val repo = Repo { calls++; CronResult.Failure(CronFailure.UNAVAILABLE) }
        val result = CronReadRecoveryCoordinator(repo).list({ allowed }, { allowed = false })
        assertEquals(CronResult.Failure(CronFailure.UNAVAILABLE), result)
        assertEquals(1, calls)
        assertEquals(0, repo.writes)
    }
}
