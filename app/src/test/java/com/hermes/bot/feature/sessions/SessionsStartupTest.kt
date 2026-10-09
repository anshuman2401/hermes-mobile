package com.hermes.bot.feature.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.domain.LocalCacheException
import com.hermes.bot.domain.SessionError
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*

class SessionsStartupTest {
    @get:Rule val main = MainDispatcherRule()
    private val store = ViewModelStore()
    private val fake = SessionsViewModelTest.FakeSessionRepository()
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private var cache: Result<SessionListOutcome?> = Result.success(null)
    private var cacheGate: CompletableDeferred<Unit>? = null
    private var nonCooperative = false
    private var cacheReads = 0
    private val repo = object : SessionRepository by fake {
        override val listInvalidations = invalidations
        override suspend fun loadCachedSessions(): Result<SessionListOutcome?> {
            cacheReads++
            if (nonCooperative) withContext(NonCancellable) { cacheGate?.await() } else cacheGate?.await()
            return cache
        }
    }
    private fun vm() = SessionsViewModel(repo, SavedStateHandle(), deferInitialLoad = true).also { store.put("sessions", it) }
    private fun cached() = SessionListOutcome(listOf(SessionSummary("synthetic-cache", "Saved", null, 50.0, 1, "android")), true)
    @After fun close() { store.clear() }

    @Test fun constructionAndForegroundNeverReadNetworkBeforeAdmission() {
        val vm = vm()
        vm.resumePolling()
        vm.refreshSessions(coalesce = true)
        invalidations.tryEmit(Unit)
        assertEquals(0, fake.listSessionsCallCount)
        assertEquals(0, cacheReads)
        assertTrue(vm.uiState.value.isLoading)
    }
    @Test fun eligibleCachePaintsWithoutNetworkAndIsReplacedOnConnection() {
        cache = Result.success(cached())
        val vm = vm()
        vm.restoreSavedLoginSessions()
        vm.restoreSavedLoginSessions()
        assertEquals(1, cacheReads)
        assertEquals(0, fake.listSessionsCallCount)
        assertTrue(vm.uiState.value.isOfflineCache)
        assertEquals("synthetic-cache", vm.uiState.value.sessions.single().id)
        vm.finishStartupConnection()
        vm.finishStartupConnection()
        assertEquals(1, fake.listSessionsCallCount)
        assertFalse(vm.uiState.value.isOfflineCache)
        assertEquals("s-1", vm.uiState.value.sessions.single().id)
    }
    @Test fun missingCacheKeepsLoadingUntilConnectionAndStillLoadsLiveList() {
        val vm = vm()
        vm.restoreSavedLoginSessions()
        assertTrue(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.sessions.isEmpty())
        assertNull(vm.uiState.value.error)
        vm.finishStartupConnection()
        assertEquals(1, fake.listSessionsCallCount)
        assertFalse(vm.uiState.value.isLoading)
    }
    @Test fun cacheFailureDoesNotPreventAuthoritativeRefresh() {
        cache = Result.failure(LocalCacheException("synthetic storage failure"))
        val vm = vm()
        vm.restoreSavedLoginSessions()
        assertTrue(vm.uiState.value.error is SessionError.LocalCacheFailure)
        vm.finishStartupConnection()
        assertEquals(1, fake.listSessionsCallCount)
        assertNull(vm.uiState.value.error)
    }
    @Test fun lateCacheCannotOverwriteLiveList() {
        cache = Result.success(cached())
        cacheGate = CompletableDeferred()
        nonCooperative = true
        val vm = vm()
        vm.restoreSavedLoginSessions()
        vm.finishStartupConnection()
        cacheGate!!.complete(Unit)
        assertEquals(1, fake.listSessionsCallCount)
        assertEquals("s-1", vm.uiState.value.sessions.single().id)
        assertFalse(vm.uiState.value.isOfflineCache)
    }
    @Test fun revokedAccessClearsContentAndRejectsLateCacheAndLiveReads() {
        cache = Result.success(cached())
        cacheGate = CompletableDeferred()
        nonCooperative = true
        val vm = vm()
        vm.restoreSavedLoginSessions()
        vm.revokeSessionAccess()
        cacheGate!!.complete(Unit)
        vm.refreshSessions(coalesce = true)
        invalidations.tryEmit(Unit)
        assertTrue(vm.uiState.value.sessions.isEmpty())
        assertFalse(vm.uiState.value.isOfflineCache)
        assertEquals(0, fake.listSessionsCallCount)
    }
    @Test fun networkFailureBeforeMissingCacheCompletesNeverRestartsSpinner() {
        cacheGate = CompletableDeferred()
        val vm = vm()
        vm.restoreSavedLoginSessions()
        vm.setNetworkError("Host unreachable.")
        cacheGate!!.complete(Unit)
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.error is SessionError.NetworkFailure)
        assertTrue(vm.uiState.value.sessions.isEmpty())
    }
    @Test fun networkFailureWithCacheRetainsOfflineRowsAndStopsSpinner() {
        cache = Result.success(cached())
        val vm = vm()
        vm.restoreSavedLoginSessions()
        vm.setNetworkError("Host unreachable.")
        assertTrue(vm.uiState.value.isOfflineCache)
        assertEquals(1, vm.uiState.value.sessions.size)
        assertFalse(vm.uiState.value.isLoading)
    }
    @Test fun warmConnectionLossKeepsRowsReadableWhileCacheReadIsPending() {
        cache = Result.success(cached())
        val vm = vm()
        vm.restoreSavedLoginSessions()
        vm.finishStartupConnection()
        val displayed = vm.uiState.value.sessions
        assertTrue(displayed.isNotEmpty())
        cacheGate = CompletableDeferred()
        vm.beginConnectionRecovery()
        vm.restoreSavedLoginSessions()
        assertEquals(displayed, vm.uiState.value.sessions)
        assertTrue(vm.uiState.value.isOfflineCache)
        assertFalse(vm.uiState.value.isLoading)
        cacheGate!!.complete(Unit)
        assertEquals(cached().sessions, vm.uiState.value.sessions)
        vm.finishStartupConnection()
        assertFalse(vm.uiState.value.isOfflineCache)
    }

    @Test fun revokedOwnerCannotRetainRowsDuringRecovery() {
        cache = Result.success(cached())
        val vm = vm()
        vm.restoreSavedLoginSessions()
        vm.revokeSessionAccess()
        cache = Result.success(null)
        vm.beginConnectionRecovery()
        vm.restoreSavedLoginSessions()
        assertTrue(vm.uiState.value.sessions.isEmpty())
        assertFalse(vm.uiState.value.isOfflineCache)
    }

}
