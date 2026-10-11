package com.hermes.bot.feature.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.feature.sessions.SessionsUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import com.hermes.bot.feature.sessions.SessionsViewModel
import kotlinx.coroutines.launch

data class BotChannelUiState(
    val avatar: com.hermes.bot.data.gateway.BotAvatar? = null,
    val displayName: String? = null,
    val connectionId: String? = null,
    val normalizedOrigin: String? = null,
    val notice: String? = null,
    val channel: String? = null,
    val draft: String = "",
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val transferring: Boolean = false,
    val createdSessionId: String? = null
)

/** Presentation only: sessions remain owned by SessionsViewModel/repository. */
class BotChannelViewModel : ViewModel() {
    private val state = MutableStateFlow(BotChannelUiState())
    val uiState = state.asStateFlow()
    private val positions = mutableMapOf<String, Pair<Int, Int>>()
    fun saveScrollPosition(name: String, index: Int, offset: Int, connectionId: String?, origin: String?) {
        if (state.value.connectionId != connectionId || state.value.normalizedOrigin != origin) return
        positions[name] = index.coerceAtLeast(0) to offset.coerceAtLeast(0)
    }
    private val drafts = mutableMapOf<String, String>()
    private var generation = 0L
    private var navigationGeneration = 0L
    val routeGeneration: Long get() = navigationGeneration
    fun leaveChannel() { navigationGeneration++ }
    private var entryCounter = 0L
    private var entry: ChannelEntry? = null
    private val recovery = mutableMapOf<String, Pair<ChannelEntry, String>>()
    private var createJob: Job? = null
    data class ChannelEntry(val token: String, val profile: String, val text: String, val generation: Long)

    fun send(sessions: SessionsViewModel, connected: Boolean, open: (String, ChannelEntry) -> Boolean) {
        val current = state.value
        if (!connected || sessions.uiState.value.isOfflineCache || sessions.uiState.value.error != null || sessions.uiState.value.isCreatingSession || current.transferring || current.createdSessionId != null || current.draft.isBlank() ||
            !isChannelListReady(current.channel, sessions.uiState.value)) return
        val attempt = ChannelEntry("channel-${++entryCounter}", current.channel!!, current.draft, generation)
        entry = attempt
        state.update { it.copy(transferring = true, notice = null) }
        var successDelivered = false
        var failureWatcher: Job? = null
        sessions.createNewChat { id ->
            successDelivered = true
            failureWatcher?.cancel()
            if (createJob === failureWatcher) createJob = null
            if (!isCurrent(attempt)) return@createNewChat
            recovery[attempt.profile] = attempt to id
            state.update { it.copy(createdSessionId = id) }
            transfer(id, attempt, open)
        }
        // A synchronous success callback already owns this attempt; no waiter
        // should remain (including when opening the created child failed).
        if (successDelivered || !isCurrent(attempt)) return
        failureWatcher = viewModelScope.launch {
            val outcome = sessions.uiState.first { it.newChatFailure != null }
            if (isCurrent(attempt) && state.value.createdSessionId == null) {
                state.update { it.copy(transferring = false, notice = outcome.newChatFailure!!.message) }
                entry = null
            }
        }
        createJob = failureWatcher
    }

    fun openCreated(open: (String, ChannelEntry) -> Boolean) {
        val attempt = entry ?: return
        val id = state.value.createdSessionId ?: return
        if (!isCurrent(attempt) || state.value.transferring) return
        state.update { it.copy(transferring = true) }
        transfer(id, attempt, open)
    }

    private fun isCurrent(attempt: ChannelEntry) = entry == attempt && generation == attempt.generation && state.value.channel == attempt.profile

    private fun transfer(id: String, attempt: ChannelEntry, open: (String, ChannelEntry) -> Boolean) {
        val accepted = try { open(id, attempt) } catch (_: Exception) { false }
        if (!isCurrent(attempt)) return
        if (accepted) {
            recovery.remove(attempt.profile)
            drafts[attempt.profile] = ""
            entry = null
            state.update { it.copy(draft = "", transferring = false, createdSessionId = null, notice = null) }
        } else state.update { it.copy(transferring = false, notice = "Couldn't open the created conversation. Please retry.") }
    }

    fun enter(name: String, displayName: String, avatar: com.hermes.bot.data.gateway.BotAvatar?,
        connectionId: String? = null, normalizedOrigin: String? = null) {
        navigationGeneration++
        entry = recovery[name]?.first
        state.update { it.copy(channel = name, displayName = displayName, avatar = avatar,
            connectionId = connectionId, normalizedOrigin = normalizedOrigin,
            draft = drafts[name].orEmpty(), scrollIndex = positions[name]?.first ?: 0, scrollOffset = positions[name]?.second ?: 0, createdSessionId = recovery[name]?.second, notice = null) }
    }

    fun updateDraft(text: String) {
        if (state.value.transferring || state.value.createdSessionId != null) return
        val name = state.value.channel ?: return
        drafts[name] = text
        state.update { it.copy(draft = text) }
    }

    fun clearConnection() {
        generation++
        navigationGeneration++
        createJob?.cancel()
        entry = null
        positions.clear()
        drafts.clear()
        recovery.clear()
        state.value = BotChannelUiState()
    }
}

/** Matching retained rows may render during refresh; actions still require [isChannelListReady]. */
fun isChannelListVisible(channel: String?, sessions: SessionsUiState): Boolean =
    channel != null && channel == sessions.selectedProfileName && sessions.staleProfileNotice == null

/** Never expose the unscoped fallback list under a selected bot's title. */
fun isChannelListReady(channel: String?, sessions: SessionsUiState): Boolean =
    channel != null && channel == sessions.selectedProfileName && !sessions.isLoading &&
        sessions.staleProfileNotice == null

/** A retained channel is not proof that another tab has kept that workspace selected. */
fun needsBotSelection(name: String, channel: String?, selectedProfileName: String?): Boolean =
    name != channel || name != selectedProfileName
