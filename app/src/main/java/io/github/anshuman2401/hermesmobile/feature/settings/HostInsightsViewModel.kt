package io.github.anshuman2401.hermesmobile.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.anshuman2401.hermesmobile.data.insights.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class HostInsightsPage(val title: String) { HEALTH("Health"), ANALYTICS("Analytics"), LOGS("Gateway logs") }
data class HostInsightsUiState(
    val profile: String? = null,
    val analyticsProfiles: List<String> = emptyList(),
    val analyticsRosterEmpty: Boolean = false,
    val loading: Boolean = false,
    val failure: InsightsFailure? = null,
    val health: HostHealth? = null,
    val usage: HostUsage? = null,
    val logs: HostLogTail? = null,
    val fetchedAt: Long? = null,
    val days: Int = 30,
    val file: HostLogFile = HostLogFile.GATEWAY,
    val level: HostLogLevel = HostLogLevel.ALL,
    val query: String = ""
) { override fun toString() = "HostInsightsUiState([private])" }
class HostInsightsViewModel(private val repository: HostInsightsRepository, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val mutable = MutableStateFlow(HostInsightsUiState())
    val state: StateFlow<HostInsightsUiState> = mutable
    private var job: Job? = null
    private var generation = 0L
    private var page: HostInsightsPage? = null
    private var analyticsRoster: AnalyticsRoster? = null
    fun open(page: HostInsightsPage) { this.page = page; refresh() }
    fun leave() {
        generation++; job?.cancel(); job = null
        analyticsRoster = null
        page = null
        mutable.value = HostInsightsUiState(days = mutable.value.days)
    }
    fun period(days: Int) { if (days !in listOf(7, 30, 90) || days == mutable.value.days) return; mutable.value = mutable.value.copy(days = days); if (page == HostInsightsPage.ANALYTICS) readAnalytics() else refresh() }
    fun analyticsProfile(profile: String) {
        if (page != HostInsightsPage.ANALYTICS || profile == mutable.value.profile || profile !in mutable.value.analyticsProfiles) return
        mutable.value = mutable.value.copy(profile = profile)
        readAnalytics()
    }
    fun file(file: HostLogFile) { if (file == mutable.value.file) return; mutable.value = mutable.value.copy(file = file, query = ""); refresh() }
    fun level(level: HostLogLevel) { if (level == mutable.value.level) return; mutable.value = mutable.value.copy(level = level, query = ""); refresh() }
    fun search(query: String) { mutable.value = mutable.value.copy(query = query) }
    fun refresh() {
        val page = page ?: return
        if (page == HostInsightsPage.ANALYTICS) { reloadAnalyticsRoster(); return }
        val request = ++generation
        job?.cancel()
        val choices = mutable.value
        mutable.value = choices.copy(loading = true, failure = null, health = null, usage = null, logs = null, fetchedAt = null)
        job = viewModelScope.launch {
            try {
                when (val capture = repository.capture()) {
                    is InsightsRead.Failed -> if (request == generation) mutable.value = mutable.value.copy(loading = false, failure = capture.reason, profile = null)
                    is InsightsRead.Ready -> {
                        val scope = capture.value
                        if (request != generation) return@launch
                        mutable.value = mutable.value.copy(profile = scope.profile)
                        val result = when (page) {
                            HostInsightsPage.HEALTH -> repository.health(scope)
                            HostInsightsPage.ANALYTICS -> error("Analytics uses its page-local roster")
                            HostInsightsPage.LOGS -> repository.logs(scope, choices.file, choices.level)
                        }
                        if (request != generation) return@launch
                        if (result is InsightsRead.Failed && result.reason == InsightsFailure.AUTH) {
                            mutable.value = HostInsightsUiState(failure = InsightsFailure.AUTH, days = choices.days, file = choices.file, level = choices.level)
                            return@launch
                        }
                        if (!repository.isCurrent(scope)) {
                            mutable.value = HostInsightsUiState(failure = InsightsFailure.STALE, days = choices.days, file = choices.file, level = choices.level)
                            return@launch
                        }
                        mutable.value = when (result) {
                            is InsightsRead.Failed -> mutable.value.copy(loading = false, failure = result.reason)
                            is InsightsRead.Ready -> mutable.value.copy(loading = false, fetchedAt = clock(),
                                health = result.value as? HostHealth, usage = result.value as? HostUsage, logs = result.value as? HostLogTail)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (request == generation) mutable.value = mutable.value.copy(loading = false, failure = InsightsFailure.UNAVAILABLE) }
        }
    }
    private fun reloadAnalyticsRoster() {
        val request = ++generation
        job?.cancel()
        val previous = mutable.value.profile
        analyticsRoster = null
        mutable.value = mutable.value.copy(loading = true, failure = null, profile = null,
            analyticsProfiles = emptyList(), analyticsRosterEmpty = false, usage = null, fetchedAt = null)
        job = viewModelScope.launch {
            try {
                when (val result = repository.analyticsRoster()) {
                    is InsightsRead.Failed -> if (request == generation) mutable.value = mutable.value.copy(loading = false, failure = result.reason)
                    is InsightsRead.Ready -> {
                        if (request != generation) return@launch
                        val roster = result.value
                        analyticsRoster = roster
                        val selected = previous?.takeIf { it in roster.profiles }
                            ?: roster.profiles.firstOrNull { it == "default" }
                            ?: roster.profiles.firstOrNull()
                        mutable.value = mutable.value.copy(analyticsProfiles = roster.profiles, profile = selected,
                            analyticsRosterEmpty = selected == null, loading = false)
                        if (selected != null) readAnalytics()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (request == generation) mutable.value = mutable.value.copy(loading = false, failure = InsightsFailure.UNAVAILABLE) }
        }
    }
    private fun readAnalytics() {
        val roster = analyticsRoster ?: return
        val selected = mutable.value.profile ?: return
        val request = ++generation
        job?.cancel()
        val days = mutable.value.days
        mutable.value = mutable.value.copy(loading = true, failure = null, usage = null, fetchedAt = null)
        job = viewModelScope.launch {
            try {
                val result = repository.analyticsUsage(roster, selected, days)
                if (request != generation) return@launch
                if (!repository.analyticsRosterCurrent(roster)) {
                    analyticsRoster = null
                    mutable.value = HostInsightsUiState(days = days, failure = InsightsFailure.STALE)
                    return@launch
                }
                mutable.value = when (result) {
                    is InsightsRead.Failed -> mutable.value.copy(loading = false, failure = result.reason)
                    is InsightsRead.Ready -> mutable.value.copy(loading = false, usage = result.value, fetchedAt = clock())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (request == generation) mutable.value = mutable.value.copy(loading = false, failure = InsightsFailure.UNAVAILABLE) }
        }
    }
    override fun onCleared() { leave() }
}
