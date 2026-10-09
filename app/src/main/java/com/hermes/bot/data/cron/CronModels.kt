package com.hermes.bot.data.cron

data class CronJob(
    val id: String,
    val profile: String,
    val name: String,
    val prompt: String,
    val schedule: String,
    val scheduleDisplay: String,
    val enabled: Boolean,
    val nextRun: String?,
    val lastStatus: String?,
    val scriptOnly: Boolean = false
)

data class CronRun(val startedAt: Double?, val active: Boolean)

enum class CronFailure {
    OFFLINE, AUTH_REQUIRED, UNSUPPORTED, NOT_FOUND, INVALID, UNAVAILABLE, UNKNOWN, STALE
}

sealed interface CronResult<out T> {
    data class Success<T>(val value: T) : CronResult<T>
    data class Failure(val reason: CronFailure) : CronResult<Nothing>
}

enum class CronAction { PAUSE, RESUME, DELETE }

interface CronRepository {
    suspend fun list(): CronResult<List<CronJob>>
    suspend fun runs(job: CronJob): CronResult<List<CronRun>>
    suspend fun save(original: CronJob?, profile: String, name: String, prompt: String, schedule: String): CronResult<Unit>
    suspend fun act(job: CronJob, action: CronAction): CronResult<Unit>
}

/** Domain orchestration seam; transport and credentials stay in the adapter. */
class DefaultCronRepository(private val adapter: CronRepository) : CronRepository by adapter
