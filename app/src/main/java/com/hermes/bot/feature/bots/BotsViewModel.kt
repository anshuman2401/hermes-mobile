package com.hermes.bot.feature.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.bots.BotRepository
import com.hermes.bot.data.bots.BotRow
import com.hermes.bot.data.bots.BotScope
import com.hermes.bot.data.bots.BotTarget
import com.hermes.bot.data.botscreen.ScreenLease
import com.hermes.bot.data.botscreen.ScreenOverviewRepository
import com.hermes.bot.data.botscreen.ScreenStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.launch

sealed interface BotsUiState {
    data object Disconnected : BotsUiState
    data object Loading : BotsUiState
    data object Empty : BotsUiState
    data object Error : BotsUiState
    data class Content(val scope: BotScope, val rows: List<BotRow>, val refreshing: Boolean = false,
        val stale: Boolean = false, val refreshFailed: Boolean = false) : BotsUiState
}

data class BotScreenOverviewState(
    val profile: String? = null,
    val loading: Boolean = false,
    val status: ScreenStatus? = null,
    val stopping: Boolean = false,
    val message: String? = null
)

class BotsViewModel(internal val configuredRepository: BotRepository,
                    private val screenOverview: ScreenOverviewRepository? = null,
                    internal val thumbnails: BotThumbnailCache<ImageBitmap>? = null) : ViewModel() {
    private val mutableState = MutableStateFlow<BotsUiState>(BotsUiState.Disconnected)
    val uiState = mutableState.asStateFlow()
    data class FavouritesState(val names: List<String> = emptyList(), val loading: Boolean = false,
        val saving: Boolean = false, val available: Boolean = false, val error: String? = null)
    private val favouritesState = MutableStateFlow(FavouritesState())
    val favourites = favouritesState.asStateFlow()
    private val screenOverviewState = MutableStateFlow(BotScreenOverviewState())
    val screenState = screenOverviewState.asStateFlow()
    private var screenScope: BotScope? = null
    private var screenEpoch = 0L
    private var screenReadJob: Job? = null
    private var screenStopJob: Job? = null
    private var savedFavourites: com.hermes.bot.data.local.BotFavourites? = null
    private var favouriteJob: Job? = null
    private var target: BotTarget? = null
    private var visible = false
    private var epoch = 0L
    private var activeScope: BotScope? = null
    private var initialFavouriteAttempted = false
    private var loadJob: Job? = null
    private var connectionJob: Job? = null
    private var avatarJob: Job? = null
    private var avatarEpoch = 0L
    private val avatarDemand = MutableStateFlow<List<String>>(emptyList())

    fun enter(localId: String?, normalizedOrigin: String?) {
        val next = if (!localId.isNullOrBlank() && !normalizedOrigin.isNullOrBlank()) BotTarget(localId, normalizedOrigin) else null
        if (visible && next == target) {
            reconcile()
            return
        }
        leave()
        visible = true
        target = next
        reconcile()
        connectionJob = viewModelScope.launch {
            combine(configuredRepository.connectionStatus, configuredRepository.invalidation) { _, _ -> Unit }.collect { reconcile() }
        }
    }

    fun leave() {
        visible = false
        target = null
        connectionJob?.cancel()
        connectionJob = null
        invalidate()
    }

    private fun invalidate() {
        epoch++
        avatarEpoch++
        avatarJob?.cancel(); avatarJob = null
        avatarDemand.value = emptyList()
        screenEpoch++
        screenReadJob?.cancel(); screenReadJob = null
        screenStopJob?.cancel(); screenStopJob = null
        screenScope = null
        screenOverviewState.value = BotScreenOverviewState()
        initialFavouriteAttempted = false
        favouriteJob?.cancel()
        savedFavourites = null
        favouritesState.value = FavouritesState()
        loadJob?.cancel()
        loadJob = null
        activeScope = null
        mutableState.value = BotsUiState.Disconnected
    }

    fun selectScreen(profile: String?) {
        val content = mutableState.value as? BotsUiState.Content
        val scope = activeScope?.takeIf { it == content?.scope && configuredRepository.isCurrent(it) }
        val name = profile?.takeIf { value -> content?.rows?.any { it.profile.name == value } == true }
        if (scope == screenScope && name == screenOverviewState.value.profile) return
        screenEpoch++
        screenReadJob?.cancel(); screenReadJob = null
        screenStopJob?.cancel(); screenStopJob = null
        screenScope = scope
        screenOverviewState.value = BotScreenOverviewState(profile = name, loading = name != null)
    }

    fun refreshScreenStatus() {
        val scope = screenScope ?: return
        val profile = screenOverviewState.value.profile ?: return
        val overview = screenOverview ?: return
        if (!visible || !configuredRepository.isCurrent(scope) || screenOverviewState.value.stopping ||
            screenReadJob?.isActive == true) return
        val operation = screenEpoch
        screenOverviewState.value = screenOverviewState.value.copy(loading = true)
        screenReadJob = viewModelScope.launch {
            val status = try { overview.status(scope, profile) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            if (ownsScreen(scope, profile, operation)) {
                screenOverviewState.value = screenOverviewState.value.copy(loading = false, status = status)
            }
        }
    }

    fun stopScreen() {
        val scope = screenScope ?: return
        val current = screenOverviewState.value
        val profile = current.profile ?: return
        val overview = screenOverview ?: return
        if (!visible || !configuredRepository.isCurrent(scope) || current.stopping ||
            current.loading || current.status?.supported != true || !current.status.installed ||
            !current.status.running || current.status.lease.holder != ScreenLease.Holder.BOT) return
        screenEpoch++
        val operation = screenEpoch
        screenReadJob?.cancel(); screenReadJob = null
        screenOverviewState.value = current.copy(stopping = true, message = null)
        screenStopJob = viewModelScope.launch {
            val stopped = try { overview.stop(scope, profile) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            // The write may have reached Hermes even when its reply was lost. Read once; never resend it.
            val confirmed = try { overview.status(scope, profile) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            if (ownsScreen(scope, profile, operation)) {
                screenOverviewState.value = BotScreenOverviewState(profile = profile,
                    status = confirmed ?: stopped,
                    message = if (confirmed?.running == false || (confirmed == null && stopped?.running == false)) null
                        else "Open screen to check whether it stopped before trying again.")
            }
        }
    }

    private fun ownsScreen(scope: BotScope, profile: String, operation: Long): Boolean =
        visible && screenEpoch == operation && screenScope == scope &&
            screenOverviewState.value.profile == profile && configuredRepository.isCurrent(scope)

    private fun reconcile() {
        if (!visible) return
        val captured = target?.let(configuredRepository::capture)
        if (captured == null) {
            invalidate()
        } else if (captured != activeScope) {
            startLoad(captured)
        }
    }

    fun retry() {
        if (!visible || loadJob?.isActive == true) return
        val captured = target?.let(configuredRepository::capture)
        if (captured == null) invalidate()
        else if (captured != activeScope) startLoad(captured)
        else {
            if (favouriteJob?.isActive != true && !favouritesState.value.saving) loadFavourites(captured, epoch)
            loadRoster(captured, force = true)
        }
    }

    private fun owns(scope: BotScope, operation: Long): Boolean = visible && operation == epoch &&
        target == scope.target && activeScope == scope && configuredRepository.isCurrent(scope)

    private fun startLoad(scope: BotScope) {
        invalidate()
        activeScope = scope
        val cached = configuredRepository.cached(scope)
        mutableState.value = cached?.rows?.takeIf { it.isNotEmpty() }?.let {
            BotsUiState.Content(scope, it, stale = !configuredRepository.isFresh(scope))
        } ?: BotsUiState.Loading
        loadFavourites(scope, epoch)
        loadRoster(scope, force = false)
    }

    private fun loadFavourites(scope: BotScope, operation: Long) {
        favouritesState.value = favouritesState.value.copy(loading = true, error = null)
        favouriteJob = viewModelScope.launch {
            try {
                val record = configuredRepository.favourites(scope)
                if (owns(scope, operation)) {
                    savedFavourites = record
                    favouritesState.value = FavouritesState(record?.names ?: favouritesState.value.names, available = record != null,
                        error = if (record == null) "Favourites unavailable" else null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (owns(scope, operation)) {
                savedFavourites = null
                favouritesState.value = favouritesState.value.copy(loading = false, available = false,
                    error = "Couldn't load favourites. Retry bots to try again.")
            } }
        }
    }

    private fun loadRoster(scope: BotScope, force: Boolean) {
        val operation = epoch
        val content = mutableState.value as? BotsUiState.Content
        if (content != null) mutableState.value = content.copy(refreshing = true, stale = !configuredRepository.isFresh(scope), refreshFailed = false)
        else mutableState.value = BotsUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val publish: (List<BotRow>) -> Unit = { rows ->
                    if (owns(scope, operation)) {
                        val previous = (mutableState.value as? BotsUiState.Content)?.rows.orEmpty()
                        mutableState.value = if (rows.isEmpty()) BotsUiState.Empty else BotsUiState.Content(scope, withVisibleAvatars(rows, previous))
                        avatarEpoch++
                        val avatarOperation = avatarEpoch
                        avatarJob?.cancel()
                        avatarJob = viewModelScope.launch {
                            try {
                                configuredRepository.avatars(scope, avatarDemand) { updated ->
                                    if (avatarOperation == avatarEpoch && owns(scope, operation)) {
                                        val current = mutableState.value as? BotsUiState.Content
                                        if (current != null) mutableState.value = current.copy(rows = withVisibleAvatars(updated, current.rows))
                                    }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Roster and initial-letter images remain usable. */ }
                        }
                    }
                }
                if (force) configuredRepository.refresh(scope, publish) else configuredRepository.load(scope, publish)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owns(scope, operation)) {
                    val retained = mutableState.value as? BotsUiState.Content
                    mutableState.value = retained?.copy(refreshing = false, refreshFailed = true) ?: BotsUiState.Error
                } else if (operation == epoch) mutableState.value = BotsUiState.Disconnected
            }
        }
    }

    /** Visible images may outlive encoded-cache eviction; removed visibility releases these references. */
    private fun withVisibleAvatars(rows: List<BotRow>, previous: List<BotRow>): List<BotRow> {
        val visibleNames = avatarDemand.value.toSet()
        val oldImages = previous.associate { it.profile.name to it.avatar }
        return rows.map { row ->
            if (row.avatar == null && row.profile.hasAvatar && row.profile.name in visibleNames)
                row.copy(avatar = oldImages[row.profile.name]) else row
        }
    }

    /** UI reports ordered visibility; scheduling and reads stay behind the repository. */
    fun demandAvatars(selected: String?, favourites: List<String>, picker: List<String>) {
        avatarDemand.value = (listOfNotNull(selected) + favourites + picker).distinct()
    }

    fun dismissScreenMessage() { screenOverviewState.value = screenOverviewState.value.copy(message = null) }

    /** A structured foreground operation; each delay begins after the previous read completes. */
    suspend fun pollScreenStatus() {
        if (screenScope == null || screenOverviewState.value.profile == null) return
        var ownedRead: Job? = null
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                refreshScreenStatus()
                ownedRead = screenReadJob
                ownedRead?.join()
                delay(if (screenOverviewState.value.status?.running == true) 15_000L else 60_000L)
            }
        } finally {
            ownedRead?.cancel()
            if (screenReadJob === ownedRead) {
                screenReadJob = null
                screenOverviewState.value = screenOverviewState.value.copy(loading = false)
            }
        }
    }

    fun setFavourite(name: String, favourite: Boolean) {
        val content = mutableState.value as? BotsUiState.Content ?: return
        val scope = activeScope ?: return
        val record = savedFavourites ?: return
        if (!visible || favouritesState.value.saving || !configuredRepository.isCurrent(scope) ||
            content.rows.none { it.profile.name == name }) return
        val operation = epoch
        favouritesState.value = favouritesState.value.copy(saving = true, error = null)
        favouriteJob = viewModelScope.launch {
            try {
                val updated = configuredRepository.setFavourite(scope, record, name, favourite)
                if (visible && operation == epoch && configuredRepository.isCurrent(scope)) {
                    savedFavourites = updated
                    favouritesState.value = FavouritesState(updated.names, available = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (visible && operation == epoch && configuredRepository.isCurrent(scope))
                    favouritesState.value = favouritesState.value.copy(saving = false, error = "Couldn't save favourite. Please retry.")
            }
        }
    }

    /** Admission happens during rendering too: effects/collectors must never be the only stale-host barrier. */
    fun presentation(state: BotsUiState, localId: String?, normalizedOrigin: String?, connected: Boolean): BotsUiState {
        val scope = activeScope
        if (!visible || !connected || target?.localId != localId || target?.normalizedOrigin != normalizedOrigin ||
            scope == null || !configuredRepository.isCurrent(scope)) return BotsUiState.Disconnected
        if (state is BotsUiState.Content && (state.scope != scope || !configuredRepository.isCurrent(state.scope))) return BotsUiState.Disconnected
        return state
    }

    /** Claims one initial selection intent; failures must be retried explicitly, not by recomposition. */
    fun takeInitialFavourite(state: BotsUiState, localId: String?, normalizedOrigin: String?,
        connected: Boolean, channel: String?, selectionAllowed: Boolean): BotRow? {
        if (channel != null || !selectionAllowed || initialFavouriteAttempted) return null
        val content = presentation(state, localId, normalizedOrigin, connected) as? BotsUiState.Content ?: return null
        val saved = favouritesState.value
        if (content.stale || !configuredRepository.isFresh(content.scope) || !saved.available || saved.loading || saved.saving) return null
        val first = saved.names.firstNotNullOfOrNull { name -> content.rows.firstOrNull { it.profile.name == name } }
            ?: return null
        initialFavouriteAttempted = true
        return first
    }

    override fun onCleared() { leave() }
}
