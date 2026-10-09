package com.hermes.bot.feature.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.context.ContextProfileRepository
import com.hermes.bot.data.context.ContextProfilesResult
import com.hermes.bot.data.context.RosterSource
import com.hermes.bot.data.cron.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

enum class CronStatusFilter { ALL, ACTIVE, PAUSED }
enum class SchedulePreset { HOURLY, DAILY, WEEKLY, ADVANCED }

data class CronDraft(
    val original: CronJob? = null,
    val profile: String = "",
    val name: String = "",
    val prompt: String = "",
    val preset: SchedulePreset = SchedulePreset.DAILY,
    val hour: String = "09",
    val minute: String = "00",
    val day: Int = 1,
    val custom: String = ""
) {
    fun schedule(): String = when (preset) {
        SchedulePreset.HOURLY -> "${minute.toIntOrNull() ?: minute} * * * *"
        SchedulePreset.DAILY -> "${minute.toIntOrNull() ?: minute} ${hour.toIntOrNull() ?: hour} * * *"
        SchedulePreset.WEEKLY -> "${minute.toIntOrNull() ?: minute} ${hour.toIntOrNull() ?: hour} * * $day"
        SchedulePreset.ADVANCED -> custom.trim()
    }

    fun valid(): Boolean {
        if (name.isBlank() || profile.isBlank() || profile.equals("all", true)) return false
        if (prompt.isBlank() && original == null) return false
        if (preset != SchedulePreset.ADVANCED) return minute.toIntOrNull() in 0..59 &&
            (preset == SchedulePreset.HOURLY || hour.toIntOrNull() in 0..23) && day in 0..6
        if (custom.isBlank()) return false
        if (custom.trim() == original?.schedule) return true // Preserve existing non-cron schedules.
        val parts = custom.trim().split(Regex("\\s+"))
        return parts.size == 5 && parts.all { it.matches(Regex("[A-Za-z0-9*/?,\\-]+")) }
    }

    fun summary(): String = when (preset) {
        SchedulePreset.HOURLY -> "Every hour at minute $minute"
        SchedulePreset.DAILY -> "Daily at $hour:$minute"
        SchedulePreset.WEEKLY -> "Every ${listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")[day.coerceIn(0, 6)]} at $hour:$minute"
        SchedulePreset.ADVANCED -> "Custom schedule: ${custom.trim()}"
    }
}

data class WorkCronsState(
    val online: Boolean = false,
    val accessGeneration: Long = 0,
    val loading: Boolean = false,
    val retryAttempt: Int = 0,
    val hasLoadedJobs: Boolean = false,
    val busy: Boolean = false,
    val jobs: List<CronJob> = emptyList(),
    val profiles: List<String> = emptyList(),
    val profile: String? = null,
    val status: CronStatusFilter = CronStatusFilter.ALL,
    val selected: CronJob? = null,
    val draft: CronDraft? = null,
    val runs: List<CronRun> = emptyList(),
    val runsLoading: Boolean = false,
    val runsError: Boolean = false,
    val failure: CronFailure? = null,
    val rosterUnavailable: Boolean = false,
    val rosterCached: Boolean = false,
    val rosterCapturedAtMillis: Long? = null,
    val needsReconciliation: Boolean = false,
    val deleteConfirmation: Boolean = false
) {
    val canManage: Boolean get() = online && hasLoadedJobs && !loading && !busy && !needsReconciliation &&
        failure !in listOf(CronFailure.UNAVAILABLE, CronFailure.OFFLINE, CronFailure.STALE,
            CronFailure.UNKNOWN, CronFailure.UNSUPPORTED, CronFailure.AUTH_REQUIRED)
    val visibleJobs: List<CronJob> get() = jobs.filter {
        (profile == null || it.profile == profile) && when (status) {
            CronStatusFilter.ALL -> true
            CronStatusFilter.ACTIVE -> it.enabled
            CronStatusFilter.PAUSED -> !it.enabled
        }
    }.sortedWith(compareBy<CronJob> { !it.enabled }.thenBy {
        try { it.nextRun?.let(OffsetDateTime::parse)?.toInstant() } catch (_: Exception) { null }
            ?: java.time.Instant.MAX
    }.thenBy { it.name.lowercase() })
}

class WorkCronsViewModel(private val repository: CronRepository, private val profileRepository: ContextProfileRepository) : ViewModel() {
    private val state = MutableStateFlow(WorkCronsState())
    val uiState: StateFlow<WorkCronsState> = state
    private val readRecovery = CronReadRecoveryCoordinator(repository)
    private var pageActive = false
    private var accessKey: String? = null
    private var epoch = 0L
    private var operation: Job? = null
    private var runsOperation: Job? = null
    private var runsGeneration = 0L

    fun pageStarted() {
        if (pageActive) return
        pageActive = true
        refreshAutomatically()
    }

    fun pageStopped() {
        pageActive = false
        if (state.value.loading) {
            epoch++
            operation?.cancel()
            state.value = state.value.copy(loading = false, retryAttempt = 0)
        }
        runsOperation?.cancel()
        runsGeneration++
        state.value = state.value.copy(runsLoading = false)
    }

    fun connectionChanged(online: Boolean, connectionKey: String?) {
        if (state.value.online == online && accessKey == connectionKey) return
        val changed = accessKey != connectionKey
        val uncertainWrite = state.value.busy || state.value.needsReconciliation
        accessKey = connectionKey
        epoch++
        operation?.cancel()
        runsOperation?.cancel()
        state.value = if (changed) WorkCronsState(online = online, accessGeneration = epoch) else state.value.copy(
            accessGeneration = epoch, draft = null,
            online = online, loading = false, retryAttempt = 0, busy = false, runsLoading = false,
            failure = if (online) null else CronFailure.OFFLINE,
            needsReconciliation = uncertainWrite
        )
        refreshAutomatically()
    }

    private fun refreshAutomatically() {
        if (!pageActive || state.value.needsReconciliation ||
            state.value.failure in listOf(CronFailure.AUTH_REQUIRED, CronFailure.UNSUPPORTED, CronFailure.INVALID, CronFailure.NOT_FOUND, CronFailure.STALE, CronFailure.UNKNOWN)) return
        readList(explicit = false)
    }

    /** Deliberate Refresh is the only read that may unlock an uncertain write. */
    fun refresh() = readList(explicit = true)

    private fun readList(explicit: Boolean) {
        if (!state.value.online || state.value.busy || state.value.loading) return
        val ticket = ++epoch
        operation?.cancel()
        runsOperation?.cancel()
        state.value = state.value.copy(loading = true, retryAttempt = 0, failure = null, runsLoading = false)
        val nextOperation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = readRecovery.list(
                canRetry = { pageActive && state.value.online && ticket == epoch },
                onRetry = { attempt -> if (ticket == epoch) state.value = state.value.copy(retryAttempt = attempt) }
            )
            if (ticket != epoch) return@launch
            when (result) {
                is CronResult.Failure -> state.value = state.value.copy(loading = false, retryAttempt = 0, failure = result.reason)
                is CronResult.Success -> {
                    val roster = try { profileRepository.getProfiles() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (ticket != epoch) return@launch
                    val profiles = ((roster as? ContextProfilesResult.Success)?.profiles?.map { it.name }.orEmpty() + result.value.map { it.profile })
                        .filter { it.isNotBlank() && !it.equals("all", true) }.distinct().sorted()
                    val selected = state.value.selected?.let { old -> result.value.find { it.id == old.id && it.profile == old.profile } }
                    state.value = state.value.copy(loading = false, retryAttempt = 0, hasLoadedJobs = true,
                        jobs = result.value, profiles = profiles,
                        selected = selected, runs = if (selected == null) emptyList() else state.value.runs,
                        profile = state.value.profile?.takeIf { it in profiles }, failure = null,
                        rosterUnavailable = roster !is ContextProfilesResult.Success,
                        rosterCached = (roster as? ContextProfilesResult.Success)?.source == RosterSource.CACHED,
                        rosterCapturedAtMillis = (roster as? ContextProfilesResult.Success)?.takeIf { it.source == RosterSource.CACHED }?.capturedAtMillis,
                        needsReconciliation = if (explicit) false else state.value.needsReconciliation)
                }
            }
        }
        operation = nextOperation
        nextOperation.start()
    }

    fun filterProfile(profile: String?) { state.value = state.value.copy(profile = profile) }
    fun filterStatus(status: CronStatusFilter) { state.value = state.value.copy(status = status) }
    fun open(job: CronJob) {
        state.value = state.value.copy(selected = job, runs = emptyList(), runsError = false)
        loadRuns()
    }

    fun loadRuns() {
        val job = state.value.selected ?: return
        if (!state.value.online || state.value.busy || state.value.loading) return
        runsOperation?.cancel()
        val ticket = epoch
        val runTicket = ++runsGeneration
        state.value = state.value.copy(runsLoading = true, runsError = false)
        runsOperation = viewModelScope.launch {
            val result = safely { repository.runs(job) }
            if (ticket != epoch || runTicket != runsGeneration || state.value.selected?.let { it.id == job.id && it.profile == job.profile } != true) return@launch
            state.value = when (result) {
                is CronResult.Success -> state.value.copy(runs = result.value, runsLoading = false)
                is CronResult.Failure -> state.value.copy(runsLoading = false, runsError = true)
            }
        }
    }

    fun create() {
        if (state.value.canManage) state.value = state.value.copy(draft = CronDraft(profile = state.value.profile ?: ""), failure = null)
    }
    fun edit() {
        val job = state.value.selected ?: return
        if (state.value.canManage) state.value = state.value.copy(draft = CronDraft(job, job.profile, job.name, job.prompt,
            preset = SchedulePreset.ADVANCED, custom = job.schedule), failure = null)
    }
    fun updateDraft(draft: CronDraft) { if (!state.value.busy) state.value = state.value.copy(draft = draft) }
    fun confirmDelete() { if (state.value.canManage) state.value = state.value.copy(deleteConfirmation = true) }
    fun dismissDelete() { state.value = state.value.copy(deleteConfirmation = false) }
    fun back(): Boolean {
        if (state.value.busy) return true
        return when {
            state.value.deleteConfirmation -> { dismissDelete(); true }
            state.value.draft != null -> { state.value = state.value.copy(draft = null); true }
            state.value.selected != null -> { runsOperation?.cancel(); state.value = state.value.copy(selected = null, runsLoading = false); true }
            else -> false
        }
    }

    fun save() {
        val draft = state.value.draft ?: return
        if (!draft.valid() || !state.value.canManage) return
        mutate { repository.save(draft.original, draft.profile, draft.name.trim(), draft.prompt, draft.schedule()) }
    }
    fun act(action: CronAction) {
        val job = state.value.selected ?: return
        if (!state.value.canManage || (action == CronAction.DELETE && !state.value.deleteConfirmation)) return
        mutate { repository.act(job, action) }
    }

    private fun mutate(block: suspend () -> CronResult<Unit>) {
        val ticket = ++epoch
        runsOperation?.cancel()
        state.value = state.value.copy(busy = true, failure = null, deleteConfirmation = false, runsLoading = false)
        val nextOperation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = safely(write = true, block = block)
            if (ticket != epoch) return@launch
            when (result) {
                is CronResult.Success -> {
                    state.value = state.value.copy(busy = false, draft = null, hasLoadedJobs = false)
                    refreshAutomatically()
                }
                is CronResult.Failure -> state.value = state.value.copy(busy = false, failure = result.reason,
                    draft = if (result.reason in listOf(CronFailure.UNKNOWN, CronFailure.STALE)) null else state.value.draft,
                    needsReconciliation = result.reason in listOf(CronFailure.UNKNOWN, CronFailure.STALE))
            }
        }
        operation = nextOperation
        nextOperation.start()
    }

    private suspend fun <T> safely(write: Boolean = false, block: suspend () -> CronResult<T>): CronResult<T> = try {
        block()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { CronResult.Failure(if (write) CronFailure.UNKNOWN else CronFailure.UNAVAILABLE) }
}
