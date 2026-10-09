package com.hermes.bot.feature.bots

import com.hermes.bot.data.bots.*
import com.hermes.bot.data.gateway.BotProfile
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.botscreen.ScreenLease
import com.hermes.bot.data.botscreen.ScreenOverviewRepository
import com.hermes.bot.data.botscreen.ScreenStatus
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModelTest {
    private fun screen(running: Boolean, holder: ScreenLease.Holder = ScreenLease.Holder.BOT) =
        ScreenStatus(true, true, running, null, "1440x900", ScreenLease(holder, null, 1))
    internal class Repository : BotRepository {
        override val connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
        var generation = 1L
        var calls = 0
        var read: suspend (BotScope, (List<BotRow>) -> Unit) -> Unit = { _, publish -> publish(listOf(BotRow(BotProfile("default", "Default", false)))) }
        override fun capture(target: BotTarget): BotScope? = if (connectionStatus.value == ConnectionStatus.CONNECTED) BotScope(target, CanonicalOrigin.parse(target.normalizedOrigin).getOrThrow(), generation) else null
        override fun isCurrent(scope: BotScope) = connectionStatus.value == ConnectionStatus.CONNECTED && scope.generation == generation
        override suspend fun load(scope: BotScope, publish: (List<BotRow>) -> Unit) { calls++; read(scope, publish) }
    }
    @Test fun emptyFailureRetryOfflineAndReconnectHaveDistinctStates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository()
            repo.read = { _, publish -> publish(emptyList()) }
            val vm = BotsViewModel(repo)
            vm.enter("local", "https://test.hermes.local"); runCurrent()
            assertEquals(BotsUiState.Empty, vm.uiState.value)
            repo.read = { _, _ -> error("synthetic failure") }
            vm.retry(); runCurrent()
            assertEquals(BotsUiState.Error, vm.uiState.value)
            repo.read = { _, publish -> publish(listOf(BotRow(BotProfile("new", "New", false)))) }
            vm.retry(); runCurrent()
            assertEquals("new", (vm.uiState.value as BotsUiState.Content).rows.single().profile.name)
            repo.connectionStatus.value = ConnectionStatus.OFFLINE
            assertEquals(BotsUiState.Disconnected, vm.presentation(vm.uiState.value, "local", "https://test.hermes.local", true))
            runCurrent()
            assertEquals(BotsUiState.Disconnected, vm.uiState.value)
            repo.generation++
            repo.connectionStatus.value = ConnectionStatus.CONNECTED; runCurrent()
            assertTrue(vm.uiState.value is BotsUiState.Content)
            assertEquals(4, repo.calls)
            vm.leave()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun wrongTargetAndGenerationAbaAreMaskedBeforeEffectsAndReentryReadsFresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository()
            val vm = BotsViewModel(repo)
            vm.enter("local", "https://test.hermes.local"); runCurrent()
            val old = vm.uiState.value
            assertTrue(vm.presentation(old, "local", "https://test.hermes.local", true) is BotsUiState.Content)
            assertEquals(BotsUiState.Disconnected, vm.presentation(old, "other", "https://test.hermes.local", true))
            assertEquals(BotsUiState.Disconnected, vm.presentation(old, "local", "https://other.hermes.local", true))
            assertEquals(BotsUiState.Disconnected, vm.presentation(old, "local", "https://test.hermes.local", false))
            repo.generation += 2 // same-origin ABA without yielding a collector/effect
            assertEquals(BotsUiState.Disconnected, vm.presentation(old, "local", "https://test.hermes.local", true))
            vm.enter("local", "https://test.hermes.local"); runCurrent()
            assertEquals(2, repo.calls)
            assertEquals(BotsUiState.Disconnected, vm.presentation(old, "local", "https://test.hermes.local", true))
            vm.enter("local", "https://other.hermes.local")
            assertEquals(BotsUiState.Loading, vm.uiState.value)
            runCurrent()
            assertEquals("https://other.hermes.local", (vm.uiState.value as BotsUiState.Content).scope.target.normalizedOrigin)
            assertEquals(3, repo.calls)
            vm.leave()
            assertEquals(BotsUiState.Disconnected, vm.uiState.value)
            repo.connectionStatus.value = ConnectionStatus.OFFLINE; runCurrent()
            repo.connectionStatus.value = ConnectionStatus.CONNECTED; runCurrent()
            assertEquals(3, repo.calls) // collector is cancelled
            vm.enter("local", "https://other.hermes.local"); runCurrent()
            assertEquals(4, repo.calls)
            vm.leave()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun nonCooperativeCompletionCannotOverwriteChangedTargetOrExitedScreen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository()
            val release = CompletableDeferred<Unit>()
            repo.read = { scope, publish ->
                if (scope.target.normalizedOrigin == "https://test.hermes.local") kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    release.await(); publish(listOf(BotRow(BotProfile("old", "Old", false))))
                } else publish(listOf(BotRow(BotProfile("new", "New", false))))
            }
            val vm = BotsViewModel(repo)
            vm.enter("local", "https://test.hermes.local"); runCurrent()
            vm.enter("local", "https://other.hermes.local"); runCurrent()
            assertEquals("new", (vm.uiState.value as BotsUiState.Content).rows.single().profile.name)
            release.complete(Unit); runCurrent()
            assertEquals("new", (vm.uiState.value as BotsUiState.Content).rows.single().profile.name)
            val exitRelease = CompletableDeferred<Unit>()
            repo.read = { _, publish -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { exitRelease.await(); publish(listOf(BotRow(BotProfile("late", "Late", false)))) } }
            vm.retry(); runCurrent()
            vm.leave()
            exitRelease.complete(Unit); runCurrent()
            assertEquals(BotsUiState.Disconnected, vm.uiState.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun leaveCancelsInFlightReadAndReentryReadsExactlyOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository()
            val entered = CompletableDeferred<Unit>()
            var cancelled = 0
            repo.read = { _, _ -> entered.complete(Unit); try { CompletableDeferred<Unit>().await() } finally { cancelled++ } }
            val vm = BotsViewModel(repo)
            vm.enter("local", "https://test.hermes.local"); entered.await()
            vm.leave(); runCurrent()
            assertEquals(1, cancelled)
            repo.read = { _, publish -> publish(emptyList()) }
            vm.enter("local", "https://test.hermes.local"); runCurrent()
            vm.enter("local", "https://test.hermes.local"); runCurrent()
            assertEquals(2, repo.calls)
            vm.leave()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun entryLoadsOnceAndRetryWhileLoadingDoesNotDuplicate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository()
            val release = CompletableDeferred<Unit>()
            repo.read = { _, publish -> release.await(); publish(listOf(BotRow(BotProfile("default", "Default", false)))) }
            val vm = BotsViewModel(repo)
            vm.enter("local", "https://test.hermes.local")
            runCurrent()
            assertEquals(BotsUiState.Loading, vm.uiState.value)
            vm.enter("local", "https://test.hermes.local")
            vm.retry()
            runCurrent()
            assertEquals(1, repo.calls)
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.uiState.value is BotsUiState.Content)
            vm.leave()
            assertEquals(BotsUiState.Disconnected, vm.uiState.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun screenStopMakesOneWriteAndReconcilesAmbiguousReply() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val bots = Repository()
            var currentStatus = screen(true)
            var writes = 0
            val overview = object : ScreenOverviewRepository {
                override suspend fun status(scope: BotScope, profile: String): ScreenStatus = currentStatus
                override suspend fun stop(scope: BotScope, profile: String): ScreenStatus? {
                    writes++
                    currentStatus = screen(false)
                    return null // Host stopped it; the write reply was lost.
                }
            }
            val vm = BotsViewModel(bots, overview)
            vm.enter("local", "https://test.hermes.local"); runCurrent()
            vm.selectScreen("default"); vm.refreshScreenStatus(); runCurrent()
            assertTrue(vm.screenState.value.status!!.running)
            vm.stopScreen(); vm.stopScreen(); runCurrent()
            assertEquals(1, writes)
            assertFalse(vm.screenState.value.status!!.running)
            assertFalse(vm.screenState.value.stopping)
            vm.leave()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun lateScreenStatusCannotAppearForNewSelectedBot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val bots = Repository()
            bots.read = { _, publish -> publish(listOf(BotRow(BotProfile("first", "First", false)),
                BotRow(BotProfile("second", "Second", false)))) }
            val release = CompletableDeferred<Unit>()
            val overview = object : ScreenOverviewRepository {
                override suspend fun status(scope: BotScope, profile: String): ScreenStatus {
                    if (profile == "first") kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                    return screen(profile == "first")
                }
                override suspend fun stop(scope: BotScope, profile: String): ScreenStatus? = null
            }
            val vm = BotsViewModel(bots, overview)
            vm.enter("local", "https://test.hermes.local"); runCurrent()
            vm.selectScreen("first"); vm.refreshScreenStatus(); runCurrent()
            vm.selectScreen("second"); vm.refreshScreenStatus(); runCurrent()
            assertEquals("second", vm.screenState.value.profile)
            assertFalse(vm.screenState.value.status!!.running)
            release.complete(Unit); runCurrent()
            assertEquals("second", vm.screenState.value.profile)
            assertFalse(vm.screenState.value.status!!.running)
            vm.leave()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun humanHeldScreenCannotBeStoppedFromBots() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val bots = Repository()
            var writes = 0
            val overview = object : ScreenOverviewRepository {
                override suspend fun status(scope: BotScope, profile: String): ScreenStatus =
                    screen(true, ScreenLease.Holder.HUMAN)
                override suspend fun stop(scope: BotScope, profile: String): ScreenStatus? {
                    writes++
                    return null
                }
            }
            val vm = BotsViewModel(bots, overview)
            vm.enter("local", "https://test.hermes.local"); runCurrent()
            vm.selectScreen("default"); vm.refreshScreenStatus(); runCurrent()
            vm.stopScreen(); runCurrent()
            assertEquals(0, writes)
            assertEquals(ScreenLease.Holder.HUMAN, vm.screenState.value.status!!.lease.holder)
            vm.leave()
        } finally { Dispatchers.resetMain() }
    }
}
