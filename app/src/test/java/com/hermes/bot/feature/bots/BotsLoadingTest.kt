package com.hermes.bot.feature.bots

import com.hermes.bot.data.bots.*
import com.hermes.bot.data.botscreen.*
import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotsLoadingTest {
    @get:Rule val main = MainDispatcherRule()
    private val target = BotTarget("synthetic", "https://test.hermes.local")
    private class Gateway : GatewayProtocolAdapter by FakeGatewayProtocolAdapter() {
        override val connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
        override val connectionGeneration = 1L
        var calls = 0
        var read: suspend () -> List<BotProfile> = { listOf(BotProfile("bot", "Bot", false)) }
        override fun isScopedConnectionCurrent(origin: CanonicalOrigin, generation: Long) =
            origin.originUrl == "https://test.hermes.local" && generation == 1L && connectionStatus.value == ConnectionStatus.CONNECTED
        override suspend fun getBotAvatar(name: String, expectedOrigin: CanonicalOrigin, expectedGeneration: Long) =
            BotAvatar(byteArrayOf(1), "image/png")
        override suspend fun listBotProfiles(expectedOrigin: CanonicalOrigin, expectedGeneration: Long): GatewayResult<List<BotProfile>> {
            calls++; return GatewayResult.Success(read())
        }
    }
    private fun enter(vm: BotsViewModel) = vm.enter(target.localId, target.normalizedOrigin)
    private fun status(running: Boolean) = ScreenStatus(true, true, running, null, "1440x900", ScreenLease(ScreenLease.Holder.BOT, null, 1))

    @Test fun warmReturnUsesRosterImmediatelyAndExpiredReturnRetainsItDuringRefresh() = runTest {
        var now = 0L; val gateway = Gateway(); val repo = DefaultBotRepository(gateway, clock = { now })
        val first = BotsViewModel(repo); enter(first); runCurrent(); first.leave()
        val second = BotsViewModel(repo); enter(second)
        assertTrue(second.uiState.value is BotsUiState.Content)
        runCurrent(); assertEquals(1, gateway.calls); second.leave()
        now = 60_000
        val release = CompletableDeferred<Unit>(); gateway.read = { release.await(); listOf(BotProfile("new", "New", false)) }
        val third = BotsViewModel(repo); enter(third); runCurrent()
        val retained = third.uiState.value as BotsUiState.Content
        assertEquals("bot", retained.rows.single().profile.name); assertTrue(retained.refreshing); assertTrue(retained.stale)
        release.complete(Unit); runCurrent()
        val fresh = third.uiState.value as BotsUiState.Content
        assertEquals("new", fresh.rows.single().profile.name); assertFalse(fresh.stale); assertFalse(fresh.refreshing)
        assertEquals(2, gateway.calls); third.leave()
    }

    @Test fun forcedRefreshRetainsContentDeduplicatesAndHandlesFailureAndAuthoritativeEmpty() = runTest {
        val gateway = Gateway(); val vm = BotsViewModel(DefaultBotRepository(gateway)); enter(vm); runCurrent()
        val release = CompletableDeferred<Unit>(); gateway.read = { release.await(); error("Synthetic failure") }
        vm.retry(); runCurrent(); vm.retry(); runCurrent()
        assertEquals(2, gateway.calls)
        assertTrue((vm.uiState.value as BotsUiState.Content).refreshing)
        release.complete(Unit); runCurrent()
        val retained = vm.uiState.value as BotsUiState.Content
        assertEquals("bot", retained.rows.single().profile.name); assertTrue(retained.refreshFailed); assertFalse(retained.refreshing)
        gateway.read = { emptyList() }; vm.retry(); runCurrent()
        assertEquals(BotsUiState.Empty, vm.uiState.value); vm.leave()
    }

    @Test fun visibleImagesSurviveEncodedEvictionAndReleaseWhenVisibilityEnds() = runTest {
        val gateway = Gateway(); gateway.read = { listOf(BotProfile("first", "First", true), BotProfile("second", "Second", true)) }
        val repo = DefaultBotRepository(gateway, memory = BotMemoryCache(maxAvatarBytes = 1))
        val vm = BotsViewModel(repo); enter(vm); runCurrent()
        vm.demandAvatars("first", listOf("second"), emptyList()); runCurrent()
        val content = vm.uiState.value as BotsUiState.Content
        assertEquals(2, content.rows.count { it.avatar != null })
        assertEquals(1, repo.cached(content.scope)!!.rows.count { it.avatar != null })
        vm.demandAvatars("second", emptyList(), emptyList()); runCurrent()
        assertNull((vm.uiState.value as BotsUiState.Content).rows.first().avatar)
        vm.leave()
    }

    @Test fun cacheClearMasksOldContentBeforeCollectorAndLoadsAgain() = runTest {
        val gateway = Gateway(); val repo = DefaultBotRepository(gateway); val vm = BotsViewModel(repo)
        enter(vm); runCurrent(); val old = vm.uiState.value
        repo.clearCache()
        assertEquals(BotsUiState.Disconnected, vm.presentation(old, target.localId, target.normalizedOrigin, true))
        runCurrent(); assertEquals(2, gateway.calls); assertTrue(vm.uiState.value is BotsUiState.Content)
        vm.leave()
    }

    @Test fun nonCooperativeOldAvatarPublicationCannotReplaceRefreshedRoster() = runTest {
        val base = BotsViewModelTest.Repository()
        val release = CompletableDeferred<Unit>(); var attempts = 0
        val repo = object : BotRepository by base {
            override suspend fun avatars(scope: BotScope, demand: kotlinx.coroutines.flow.Flow<List<String>>, publish: (List<BotRow>) -> Unit) {
                if (++attempts == 1) withContext(NonCancellable) {
                    release.await(); publish(listOf(BotRow(BotProfile("old", "Old", true))))
                }
            }
        }
        val vm = BotsViewModel(repo); vm.enter("synthetic", target.normalizedOrigin); runCurrent()
        base.read = { _, publish -> publish(listOf(BotRow(BotProfile("new", "New", true)))) }
        vm.retry(); runCurrent(); release.complete(Unit); runCurrent()
        assertEquals("new", (vm.uiState.value as BotsUiState.Content).rows.single().profile.name); vm.leave()
    }

    @Test fun pollingWaitsAfterReadUsesBothIntervalsAndCancelsForegroundRead() = runTest {
        val repo = BotsViewModelTest.Repository(); var calls = 0; var running = true
        var gate: CompletableDeferred<Unit>? = null; var cancelled = 0
        val overview = object : ScreenOverviewRepository {
            override suspend fun status(scope: BotScope, profile: String): ScreenStatus {
                calls++
                try { gate?.await(); return status(running) } finally { if (gate?.isCompleted == false) cancelled++ }
            }
            override suspend fun stop(scope: BotScope, profile: String): ScreenStatus? = null
        }
        val vm = BotsViewModel(repo, overview); vm.enter("synthetic", target.normalizedOrigin); runCurrent(); vm.selectScreen("default")
        var polling = launch { vm.pollScreenStatus() }; runCurrent(); assertEquals(1, calls)
        advanceTimeBy(14_999); runCurrent(); assertEquals(1, calls)
        running = false; advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(59_999); runCurrent(); assertEquals(2, calls)
        gate = CompletableDeferred(); advanceTimeBy(1); runCurrent(); assertEquals(3, calls)
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, calls) // Never overlap an unfinished read.
        polling.cancelAndJoin(); assertEquals(1, cancelled)
        gate = null; polling = launch { vm.pollScreenStatus() }; runCurrent(); assertEquals(4, calls)
        polling.cancelAndJoin(); vm.leave()
    }

    @Test fun backgroundCancellationRejectsNonCooperativeStatusAfterResume() = runTest {
        val repo = BotsViewModelTest.Repository(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var reads = 0
        val overview = object : ScreenOverviewRepository {
            override suspend fun status(scope: BotScope, profile: String): ScreenStatus {
                if (++reads == 1) { entered.complete(Unit); withContext(NonCancellable) { release.await() }; return status(true) }
                return status(false)
            }
            override suspend fun stop(scope: BotScope, profile: String): ScreenStatus? = null
        }
        val vm = BotsViewModel(repo, overview); vm.enter("synthetic", target.normalizedOrigin); runCurrent(); vm.selectScreen("default")
        val first = launch { vm.pollScreenStatus() }; entered.await(); first.cancelAndJoin()
        val resumed = launch { vm.pollScreenStatus() }; runCurrent(); assertFalse(vm.screenState.value.status!!.running)
        release.complete(Unit); runCurrent(); assertFalse(vm.screenState.value.status!!.running)
        resumed.cancelAndJoin(); vm.leave()
    }

    @Test fun failedPollRemovesStopEligibilityAndStopFailureCanBeDismissed() = runTest {
        val repo = BotsViewModelTest.Repository(); var reads = 0; var writes = 0
        val stopRelease = CompletableDeferred<Unit>()
        val overview = object : ScreenOverviewRepository {
            override suspend fun status(scope: BotScope, profile: String): ScreenStatus? {
                reads++; return if (reads == 2) null else status(true)
            }
            override suspend fun stop(scope: BotScope, profile: String): ScreenStatus? { writes++; stopRelease.await(); return null }
        }
        val vm = BotsViewModel(repo, overview); vm.enter("synthetic", target.normalizedOrigin); runCurrent(); vm.selectScreen("default")
        vm.refreshScreenStatus(); runCurrent(); vm.refreshScreenStatus(); runCurrent()
        vm.stopScreen(); runCurrent(); assertEquals(0, writes)
        vm.refreshScreenStatus(); runCurrent(); vm.stopScreen(); vm.stopScreen(); runCurrent()
        assertEquals(1, writes); assertTrue(vm.screenState.value.stopping)
        stopRelease.complete(Unit); runCurrent(); assertNotNull(vm.screenState.value.message)
        vm.dismissScreenMessage(); assertNull(vm.screenState.value.message); vm.leave()
    }
}
