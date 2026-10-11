package com.hermes.bot.feature.settings

import com.hermes.bot.data.insights.InsightsScope
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.AuthCredentialLease
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.update.*
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HostUpdateViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val scope = InsightsScope("default", "synthetic", CanonicalOrigin.parse("http://example.invalid:8080").getOrThrow(),
        AuthCredentialLease("synthetic", "http://example.invalid:8080", "synthetic", AuthCredential("cookie_session", "basic", emptyMap())), 1)
    private val update = HostUpdateCheck("1.0", true, 2, true, "git", listOf("Synthetic change"))
    private val id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private inner class Repository : HostUpdateRepository {
        var check = update
        var startResult: HostUpdateResult<HostUpdateStart> = HostUpdateResult.Ready(HostUpdateStart(id))
        var statusValue = HostUpdateStatus(false, null, null)
        var checkGate: CompletableDeferred<Unit>? = null
        var statusFailure: HostUpdateFailure? = null
        var checkFailure: HostUpdateFailure? = null
        var statusException: Exception? = null
        var ownerCurrent = true
        var statuses = 0
        var startCalls = 0
        var checks = 0
        override suspend fun capture() = HostUpdateResult.Ready(scope)
        override suspend fun isCurrent(scope: InsightsScope) = ownerCurrent
        override suspend fun check(scope: InsightsScope): HostUpdateResult<HostUpdateCheck> { checks++; checkGate?.await(); return checkFailure?.let { HostUpdateResult.Failed(it) } ?: HostUpdateResult.Ready(check) }
        override suspend fun start(scope: InsightsScope): HostUpdateResult<HostUpdateStart> { startCalls++; return startResult }
        override suspend fun status(scope: InsightsScope): HostUpdateResult<HostUpdateStatus> =
            run { statuses++; statusException?.let { throw it }; statusFailure?.let { HostUpdateResult.Failed(it) } ?: HostUpdateResult.Ready(statusValue) }
    }

    @Test fun checkAndConfirmationGateExactlyOneStart() {
        val repo = Repository()
        val waiting = CompletableDeferred<Unit>()
        val vm = HostUpdateViewModel(repo, clock = { 42L }, waitForPoll = { waiting.await() })
        vm.open()
        assertTrue(vm.state.value.canStart)
        assertEquals(42L, vm.state.value.checkedAt)
        vm.confirmUpdate(); assertEquals(0, repo.startCalls)
        vm.requestUpdate(); assertTrue(vm.state.value.confirmationOpen)
        vm.dismissConfirmation(); assertEquals(0, repo.startCalls)
        vm.requestUpdate(); vm.confirmUpdate(); vm.confirmUpdate()
        assertEquals(1, repo.startCalls)
        assertEquals(HostUpdatePhase.WAITING, vm.state.value.phase)
        assertFalse(vm.state.value.canStart)
        vm.leave(); assertNull(vm.state.value.check)
    }

    @Test fun matchingReceiptConfirmsAndRefreshesInstalledState() = runBlocking {
        val repo = Repository()
        val waiting = CompletableDeferred<Unit>()
        val vm = HostUpdateViewModel(repo, waitForPoll = { waiting.await() })
        vm.open(); vm.requestUpdate(); vm.confirmUpdate()
        repo.statusValue = HostUpdateStatus(false, id, "success")
        repo.check = update.copy(installedVersion = "1.1", available = false, behind = 0)
        waiting.complete(Unit)
        assertEquals(HostUpdatePhase.COMPLETE, vm.state.value.phase)
        assertEquals("1.1", vm.state.value.check?.installedVersion)
        assertFalse(vm.state.value.canStart)
        assertEquals(1, repo.startCalls)
        vm.leave()
    }

    @Test fun unrelatedReceiptNeverClaimsSuccessOrRetries() = runBlocking {
        val repo = Repository()
        val waiting = CompletableDeferred<Unit>()
        val vm = HostUpdateViewModel(repo, waitForPoll = { waiting.await() })
        vm.open(); vm.requestUpdate(); vm.confirmUpdate()
        repo.statusValue = HostUpdateStatus(false, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "success")
        waiting.complete(Unit)
        assertEquals(HostUpdatePhase.UNKNOWN, vm.state.value.phase)
        assertFalse(vm.state.value.canStart)
        assertEquals(1, repo.startCalls)
        vm.leave()
    }

    @Test fun matchingMarkerWithoutFreshUpToDateCheckRemainsUnknown() = runBlocking {
        val repo = Repository()
        val waiting = CompletableDeferred<Unit>()
        val vm = HostUpdateViewModel(repo, waitForPoll = { waiting.await() })
        vm.open(); vm.requestUpdate(); vm.confirmUpdate()
        repo.statusValue = HostUpdateStatus(false, id, "success")
        waiting.complete(Unit)
        assertEquals(HostUpdatePhase.UNKNOWN, vm.state.value.phase)
        assertFalse(vm.state.value.canStart)
        assertEquals(1, repo.startCalls)
        vm.leave()
    }

    @Test fun uncertainStartStaysDisabledAndDoesNotRepeat() {
        val repo = Repository().apply { startResult = HostUpdateResult.Failed(HostUpdateFailure.UNKNOWN) }
        val vm = HostUpdateViewModel(repo)
        vm.open(); vm.requestUpdate(); vm.confirmUpdate()
        assertEquals(HostUpdatePhase.UNKNOWN, vm.state.value.phase)
        vm.requestUpdate(); vm.confirmUpdate()
        assertEquals(1, repo.startCalls)
        assertFalse(vm.state.value.canStart)
        vm.leave()
    }

    @Test fun externallyManagedHostHasNoUpdateAction() {
        val repo = Repository().apply { check = update.copy(installMethod = "apt", canApply = false) }
        val vm = HostUpdateViewModel(repo)
        vm.open(); vm.requestUpdate(); vm.confirmUpdate()
        assertFalse(vm.state.value.canStart)
        assertEquals(0, repo.startCalls)
        vm.leave()
    }

    @Test fun unavailableTrackingStillShowsVersionButCannotStartAndRefreshRecovers() {
        for (failure in listOf(HostUpdateFailure.TIMEOUT, HostUpdateFailure.UNAVAILABLE, HostUpdateFailure.MALFORMED, HostUpdateFailure.UNSUPPORTED)) {
            val repo = Repository().apply { statusFailure = failure }
            val vm = HostUpdateViewModel(repo)
            vm.open()
            assertEquals(update, vm.state.value.check)
            assertEquals(HostUpdateFailure.STATUS_UNAVAILABLE, vm.state.value.failure)
            assertFalse(vm.state.value.canStart)
            vm.requestUpdate(); vm.confirmUpdate()
            assertEquals(0, repo.startCalls)
            repo.statusFailure = null
            vm.refresh()
            assertTrue(vm.state.value.canStart)
            assertNull(vm.state.value.failure)
            repo.statusFailure = failure
            vm.requestUpdate()
            vm.refresh()
            assertFalse(vm.state.value.confirmationOpen)
            assertFalse(vm.state.value.canStart)
            assertEquals(update, vm.state.value.check)
            vm.leave()
        }
    }

    @Test fun secondaryReadOwnsLoadingAndLeavingCancelsItsPresentation() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val repo = Repository().apply { statusFailure = HostUpdateFailure.MALFORMED; checkGate = waiting }
        val vm = HostUpdateViewModel(repo)
        vm.open()
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.canStart)
        vm.refresh()
        assertEquals(1, repo.checks)
        vm.leave()
        waiting.complete(Unit)
        assertEquals(HostUpdateUiState(), vm.state.value)
    }

    @Test fun authorityFailureDoesNotFetchVersionOrRetainUpdatePermission() {
        for (failure in listOf(HostUpdateFailure.AUTH, HostUpdateFailure.ORIGIN, HostUpdateFailure.STALE)) {
            val repo = Repository().apply { statusFailure = failure }
            val vm = HostUpdateViewModel(repo)
            vm.open()
            assertEquals(0, repo.checks)
            assertEquals(failure, vm.state.value.failure)
            assertNull(vm.state.value.check)
            repo.statusFailure = null
            vm.refresh()
            assertTrue(vm.state.value.canStart)
            val checks = repo.checks
            repo.statusFailure = failure
            vm.refresh()
            assertEquals(checks, repo.checks)
            assertNull(vm.state.value.check)
            assertFalse(vm.state.value.canStart)
            vm.leave()
        }
    }

    @Test fun interruptedMatchingActionNeverClaimsSuccessOrResends() = runBlocking {
        val repo = Repository()
        val waiting = CompletableDeferred<Unit>()
        val vm = HostUpdateViewModel(repo, waitForPoll = { waiting.await() })
        vm.open(); vm.requestUpdate(); vm.confirmUpdate()
        repo.statusValue = HostUpdateStatus(false, id, "interrupted")
        waiting.complete(Unit)
        assertEquals(HostUpdatePhase.FAILED, vm.state.value.phase)
        assertEquals(HostUpdateFailure.INTERRUPTED, vm.state.value.failure)
        assertFalse(vm.state.value.canStart)
        assertEquals(1, repo.startCalls)
        vm.leave()
    }

    private fun advance(milliseconds: Long) {
        main.testDispatcher.scheduler.advanceTimeBy(milliseconds)
        main.testDispatcher.scheduler.runCurrent()
    }

    @Test fun failedChecksRetryAfterFiveSecondsAndStopOnSuccess() {
        val repo = Repository().apply { checkFailure = HostUpdateFailure.TIMEOUT }
        val vm = HostUpdateViewModel(repo)
        try {
            vm.open()
            assertTrue(vm.state.value.retryScheduled)
            assertEquals(1, repo.checks)
            advance(4_999); assertEquals(1, repo.checks)
            advance(1); assertEquals(2, repo.checks)
            repo.checkFailure = null
            advance(5_000)
            assertEquals(3, repo.checks)
            assertTrue(vm.state.value.canStart)
            assertFalse(vm.state.value.retryScheduled)
            assertNull(vm.state.value.failure)
            advance(15_000); assertEquals(3, repo.checks)
            assertEquals(0, repo.startCalls)
        } finally { vm.leave() }
    }

    @Test fun aNewTransientExceptionCanRetryAfterAnEarlierPermanentResponse() {
        val repo = Repository().apply { statusFailure = HostUpdateFailure.MALFORMED }
        val vm = HostUpdateViewModel(repo)
        try {
            vm.open()
            assertFalse(vm.state.value.retryScheduled)
            repo.statusException = IllegalStateException("Synthetic read failure")
            vm.refresh()
            assertEquals(HostUpdateFailure.UNAVAILABLE, vm.state.value.failure)
            assertTrue(vm.state.value.retryScheduled)
            repo.statusException = null
            repo.statusFailure = null
            advance(5_000)
            assertTrue(vm.state.value.canStart)
            assertFalse(vm.state.value.retryScheduled)
        } finally { vm.leave() }
    }

    @Test fun transientStatusFailureRetriesEvenWhenVersionReadSucceeded() {
        val repo = Repository().apply { statusFailure = HostUpdateFailure.UNAVAILABLE }
        val vm = HostUpdateViewModel(repo)
        try {
            vm.open()
            assertEquals(update, vm.state.value.check)
            assertTrue(vm.state.value.retryScheduled)
            assertFalse(vm.state.value.canStart)
            repo.statusFailure = null
            advance(5_000)
            assertEquals(2, repo.statuses)
            assertEquals(2, repo.checks)
            assertTrue(vm.state.value.canStart)
            assertFalse(vm.state.value.retryScheduled)
        } finally { vm.leave() }
    }

    @Test fun retriesNeverOverlapAnInFlightRead() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val repo = Repository().apply { checkFailure = HostUpdateFailure.UNAVAILABLE }
        val vm = HostUpdateViewModel(repo)
        try {
            vm.open()
            repo.checkGate = waiting
            advance(5_000)
            assertTrue(vm.state.value.loading)
            assertFalse(vm.state.value.retryScheduled)
            advance(20_000)
            assertEquals(2, repo.checks)
            repo.checkFailure = null
            waiting.complete(Unit)
            assertTrue(vm.state.value.canStart)
            advance(10_000); assertEquals(2, repo.checks)
        } finally { vm.leave() }
    }

    @Test fun permanentReadFailuresDoNotRetry() {
        for (failure in listOf(HostUpdateFailure.AUTH, HostUpdateFailure.ORIGIN, HostUpdateFailure.STALE,
            HostUpdateFailure.UNSUPPORTED, HostUpdateFailure.MALFORMED)) {
            val repo = Repository().apply { checkFailure = failure }
            val vm = HostUpdateViewModel(repo)
            try {
                vm.open(); advance(15_000)
                assertEquals(1, repo.checks)
                assertFalse(vm.state.value.retryScheduled)
                assertEquals(0, repo.startCalls)
            } finally { vm.leave() }
        }
        for (failure in listOf(HostUpdateFailure.UNSUPPORTED, HostUpdateFailure.MALFORMED)) {
            val repo = Repository().apply { statusFailure = failure }
            val vm = HostUpdateViewModel(repo)
            try {
                vm.open(); advance(15_000)
                assertEquals(1, repo.statuses)
                assertFalse(vm.state.value.retryScheduled)
            } finally { vm.leave() }
        }
    }

    @Test fun manualRefreshReplacesTheScheduledRetry() {
        val repo = Repository().apply { checkFailure = HostUpdateFailure.UNAVAILABLE }
        val vm = HostUpdateViewModel(repo)
        try {
            vm.open(); advance(2_500)
            repo.checkFailure = null
            vm.refresh()
            assertEquals(2, repo.checks)
            assertFalse(vm.state.value.retryScheduled)
            advance(15_000); assertEquals(2, repo.checks)
        } finally { vm.leave() }
    }

    @Test fun leavingOrChangingOwnerStopsRetriesBeforeAnotherRead() {
        val repo = Repository().apply { checkFailure = HostUpdateFailure.TIMEOUT }
        val vm = HostUpdateViewModel(repo)
        vm.open(); vm.leave(); advance(15_000)
        assertEquals(1, repo.checks)
        assertEquals(HostUpdateUiState(), vm.state.value)
        try {
            vm.open()
            repo.ownerCurrent = false
            advance(5_000)
            assertEquals(2, repo.checks)
            assertEquals(2, repo.statuses)
            assertEquals(HostUpdateFailure.STALE, vm.state.value.failure)
            assertFalse(vm.state.value.retryScheduled)
            advance(15_000); assertEquals(2, repo.checks)
        } finally { vm.leave() }
    }

    @Test fun retriesNeverResubmitAnUnknownUpdateAttempt() {
        val repo = Repository().apply { startResult = HostUpdateResult.Failed(HostUpdateFailure.UNKNOWN) }
        val vm = HostUpdateViewModel(repo)
        try {
            vm.open(); vm.requestUpdate(); vm.confirmUpdate()
            advance(15_000)
            assertEquals(1, repo.startCalls)
            assertEquals(1, repo.checks)
            assertEquals(HostUpdatePhase.UNKNOWN, vm.state.value.phase)
            assertFalse(vm.state.value.retryScheduled)
        } finally { vm.leave() }
    }

    @Test fun unavailableHostSourceRetriesButManagedInstallationsDoNot() {
        val repo = Repository().apply { check = update.copy(available = false, behind = null) }
        val vm = HostUpdateViewModel(repo)
        try {
            vm.open()
            assertTrue(vm.state.value.retryScheduled)
            repo.check = update.copy(available = false, behind = 0)
            advance(5_000)
            assertEquals(2, repo.checks)
            assertFalse(vm.state.value.retryScheduled)
            advance(10_000); assertEquals(2, repo.checks)
            repo.check = update.copy(available = false, behind = null, canApply = false, installMethod = "apt")
            vm.refresh(); advance(15_000)
            assertEquals(3, repo.checks)
            assertFalse(vm.state.value.retryScheduled)
        } finally { vm.leave() }
    }

    @Test fun unreachableUpdateSourceIsNeverPresentedAsUpToDate() {
        val unknown = HostUpdateUiState(check = update.copy(available = false, behind = null))
        assertEquals("Update check unavailable", statusTitle(unknown))
        val managed = HostUpdateUiState(check = update.copy(available = false, behind = null, canApply = false, installMethod = "apt"))
        assertEquals("Managed on host", statusTitle(managed))
    }
}
