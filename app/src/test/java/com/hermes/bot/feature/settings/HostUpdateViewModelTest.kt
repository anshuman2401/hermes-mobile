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
        var startCalls = 0
        var checks = 0
        override suspend fun capture() = HostUpdateResult.Ready(scope)
        override suspend fun isCurrent(scope: InsightsScope) = true
        override suspend fun check(scope: InsightsScope): HostUpdateResult<HostUpdateCheck> { checks++; return HostUpdateResult.Ready(check) }
        override suspend fun start(scope: InsightsScope): HostUpdateResult<HostUpdateStart> { startCalls++; return startResult }
        override suspend fun status(scope: InsightsScope) = HostUpdateResult.Ready(statusValue)
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

    @Test fun unreachableUpdateSourceIsNeverPresentedAsUpToDate() {
        val unknown = HostUpdateUiState(check = update.copy(available = false, behind = null))
        assertEquals("Update check unavailable", statusTitle(unknown))
        val managed = HostUpdateUiState(check = update.copy(available = false, behind = null, canApply = false, installMethod = "apt"))
        assertEquals("Managed on host", statusTitle(managed))
    }
}
