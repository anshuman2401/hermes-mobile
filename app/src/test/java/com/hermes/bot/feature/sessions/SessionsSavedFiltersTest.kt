package com.hermes.bot.feature.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.hermes.bot.data.local.SessionFilters
import com.hermes.bot.data.local.SessionPinScope
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionsSavedFiltersTest {
    @get:Rule val main = MainDispatcherRule()
    private val scope = SessionPinScope("synthetic-profile", "synthetic-origin", null)
    private class FilterRepo(
        val delegate: SessionsViewModelTest.FakeSessionRepository,
        var stored: SessionFilters
    ) : SessionRepository by delegate {
        var failRead = false
        var failWrite = false
        var reads = 0
        var writes = 0
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var cached: SessionListOutcome? = null
        override suspend fun getSessionFilters(): SessionFilters {
            reads++
            readGate?.await()
            if (failRead) error("Synthetic read failure")
            return stored
        }
        override suspend fun loadCachedSessions(): Result<SessionListOutcome?> = Result.success(cached)
        override suspend fun saveSessionFilters(filters: SessionFilters): SessionFilters {
            writes++
            writeGate?.await()
            if (failWrite) error("Synthetic write failure")
            return filters.also { stored = it }
        }
    }
    private fun cleanup(vm: SessionsViewModel) = ViewModelStore().apply { put("sessions", vm); clear() }

    @Test fun coldLaunchUsesDurableChoiceBeforeRows_andFilterChangesDoNotFetch() {
        val delegate = SessionsViewModelTest.FakeSessionRepository()
        val repo = FilterRepo(delegate, SessionFilters(scope, true, setOf("tui")))
        val stale = SavedStateHandle(mapOf("showPinnedOnly" to false, "selectedSources" to arrayListOf("android")))
        val vm = SessionsViewModel(repo, stale)
        assertFalse(vm.uiState.value.filtersRestoring)
        assertTrue(vm.uiState.value.showPinnedOnly)
        assertEquals(setOf("tui"), vm.uiState.value.selectedSources)
        assertTrue(vm.uiState.value.sessions.isNotEmpty())
        val calls = delegate.listSessionsCallCount
        vm.onPinnedFilterChange(false)
        vm.onAllSourcesChange()
        vm.onAllSourcesChange()
        assertEquals(calls, delegate.listSessionsCallCount)
        assertEquals(SessionFilters(scope, false, emptySet()), repo.stored)
        vm.onSearchQueryChange("temporary")
        cleanup(vm)
        val restarted = SessionsViewModel(repo, SavedStateHandle())
        assertEquals(emptySet<String>(), restarted.uiState.value.selectedSources)
        assertEquals("", restarted.uiState.value.searchQuery)
        cleanup(restarted)
    }

    @Test fun readFailureShowsAllWithWarning_andRetryIsLocalOnly() {
        val delegate = SessionsViewModelTest.FakeSessionRepository()
        val repo = FilterRepo(delegate, SessionFilters(scope, true, setOf("tui")))
        repo.failRead = true
        val vm = SessionsViewModel(repo)
        assertFalse(vm.uiState.value.showPinnedOnly)
        assertNull(vm.uiState.value.selectedSources)
        assertEquals("Couldn't load saved filters. Showing all sessions.", vm.uiState.value.filterFailure)
        assertTrue(vm.uiState.value.sessions.isNotEmpty())
        val calls = delegate.listSessionsCallCount
        repo.failRead = false
        vm.retrySavedFilters()
        assertEquals(calls, delegate.listSessionsCallCount)
        assertTrue(vm.uiState.value.showPinnedOnly)
        assertEquals(setOf("tui"), vm.uiState.value.selectedSources)
        assertNull(vm.uiState.value.filterFailure)
        cleanup(vm)
    }

    @Test fun cachedRowsWaitForSavedFilterBeforePublication() {
        val repo = FilterRepo(SessionsViewModelTest.FakeSessionRepository(),
            SessionFilters(scope, false, setOf("tui")))
        repo.readGate = CompletableDeferred()
        repo.cached = SessionListOutcome(listOf(SessionSummary("s-1", "Synthetic", null, 1.0, 1, "android")), true)
        val vm = SessionsViewModel(repo, deferInitialLoad = true)
        vm.restoreSavedLoginSessions()
        assertTrue(vm.uiState.value.filtersRestoring)
        assertTrue(vm.uiState.value.sessions.isEmpty())
        repo.readGate!!.complete(Unit)
        assertFalse(vm.uiState.value.filtersRestoring)
        assertEquals(setOf("tui"), vm.uiState.value.selectedSources)
        assertEquals(1, vm.uiState.value.sessions.size)
        assertTrue(SessionsSourceFilterPolicy.filter(vm.uiState.value.sessions,
            vm.uiState.value.selectedSources).isEmpty())
        cleanup(vm)
    }

    @Test fun failedWriteRestoresLastSavedChoice_andWorkspaceSwitchReadsOwnChoice() {
        val repo = FilterRepo(SessionsViewModelTest.FakeSessionRepository(), SessionFilters(scope, false, setOf("android")))
        val vm = SessionsViewModel(repo)
        repo.failWrite = true
        vm.onPinnedFilterChange(true)
        assertFalse(vm.uiState.value.showPinnedOnly)
        assertEquals(setOf("android"), vm.uiState.value.selectedSources)
        assertEquals("Couldn't save filters. Previous choice restored.", vm.uiState.value.filterFailure)
        repo.failWrite = false
        vm.resetWorkspace()
        repo.stored = SessionFilters(scope.copy(workspace = "workspace-b"), true, emptySet())
        vm.loadSessions()
        assertTrue(vm.uiState.value.showPinnedOnly)
        assertEquals(emptySet<String>(), vm.uiState.value.selectedSources)
        cleanup(vm)
    }

    @Test fun rapidChangesPersistTheLatestChoice() {
        val repo = FilterRepo(SessionsViewModelTest.FakeSessionRepository(), SessionFilters(scope))
        val vm = SessionsViewModel(repo)
        repo.writeGate = CompletableDeferred()
        vm.onPinnedFilterChange(true)
        vm.onSourceToggle("android")
        vm.onPinnedFilterChange(false)
        assertEquals(SessionFilters(scope), repo.stored)
        repo.writeGate!!.complete(Unit)
        assertEquals(SessionFilters(scope, false, emptySet()), repo.stored)
        assertEquals(emptySet<String>(), vm.uiState.value.selectedSources)
        cleanup(vm)
    }
}
