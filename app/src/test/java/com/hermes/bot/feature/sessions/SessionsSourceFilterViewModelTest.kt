package com.hermes.bot.feature.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.test.MainDispatcherRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionsSourceFilterViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private fun cleanup(vm: SessionsViewModel) = ViewModelStore().apply { put("sessions", vm); clear() }
    private fun row(id: String, source: String?) = SessionSummary(id, id, null, 100.0, 1, source)
    private fun repo() = SessionsViewModelTest.FakeSessionRepository().apply {
        listOutcome = Result.success(SessionListOutcome(listOf(row("a", "android"), row("b", "tui")), false))
    }

    @Test fun defaultsAndSelectionChangesNeverFetchOrMutateLoadedRows() {
        val repo = repo()
        val vm = SessionsViewModel(repo)
        val calls = repo.listSessionsCallCount
        val loaded = vm.uiState.value.sessions
        assertFalse(vm.uiState.value.showPinnedOnly)
        assertNull(vm.uiState.value.selectedSources)
        vm.onSourceToggle("tui")
        vm.onPinnedFilterChange(true)
        vm.onSearchQueryChange("a")
        assertEquals(setOf("android"), vm.uiState.value.selectedSources)
        assertSame(loaded, vm.uiState.value.sessions)
        assertEquals(calls, repo.listSessionsCallCount)
        vm.onSourceToggle("unknown")
        assertEquals(setOf("android"), vm.uiState.value.selectedSources)
        cleanup(vm)
    }

    @Test fun allSourcesToggleResetAndSavedStateDistinguishAllFromNone() {
        val saved = SavedStateHandle()
        val vm = SessionsViewModel(repo(), saved)
        vm.onAllSourcesChange()
        assertEquals(emptySet<String>(), vm.uiState.value.selectedSources)
        cleanup(vm)
        val restored = SessionsViewModel(repo(), saved)
        assertEquals(emptySet<String>(), restored.uiState.value.selectedSources)
        restored.onSourceToggle("android")
        restored.onPinnedFilterChange(true)
        restored.onSearchQueryChange("a")
        assertEquals(setOf("android"), restored.uiState.value.selectedSources)
        restored.resetFilters()
        assertNull(restored.uiState.value.selectedSources)
        assertFalse(restored.uiState.value.showPinnedOnly)
        assertEquals("", restored.uiState.value.searchQuery)
        restored.onAllSourcesChange()
        restored.onAllSourcesChange()
        assertNull(restored.uiState.value.selectedSources)
        cleanup(restored)
        val defaults = SessionsViewModel(null, saved)
        assertNull(defaults.uiState.value.selectedSources)
        assertFalse(defaults.uiState.value.showPinnedOnly)
        cleanup(defaults)
    }

    @Test fun subsetRestoresAndRemainsDuringRefreshFailureAndOfflineResults() {
        val saved = SavedStateHandle()
        val repo = repo()
        val vm = SessionsViewModel(repo, saved)
        vm.onSourceToggle("tui")
        cleanup(vm)
        val restored = SessionsViewModel(repo, saved)
        assertEquals(setOf("android"), restored.uiState.value.selectedSources)
        repo.listOutcome = Result.failure(IllegalStateException("Synthetic failure"))
        restored.refreshSessions()
        assertEquals(setOf("android"), restored.uiState.value.selectedSources)
        repo.listOutcome = Result.success(SessionListOutcome(listOf(row("c", "android"), row("d", "future")), true))
        restored.refreshSessions()
        assertTrue(restored.uiState.value.isOfflineCache)
        assertEquals(setOf("android"), restored.uiState.value.selectedSources)
        assertEquals(listOf("c"), SessionsSourceFilterPolicy.filter(restored.uiState.value.sessions,
            restored.uiState.value.selectedSources).map { it.id })
        cleanup(restored)
    }

    @Test fun workspaceResetClearsSourceSubsetInStateAndSavedState() {
        val saved = SavedStateHandle()
        val vm = SessionsViewModel(repo(), saved)
        vm.onSourceToggle("tui")
        vm.resetWorkspace()
        assertNull(vm.uiState.value.selectedSources)
        cleanup(vm)
        val restored = SessionsViewModel(null, saved)
        assertNull(restored.uiState.value.selectedSources)
        cleanup(restored)
    }
}
