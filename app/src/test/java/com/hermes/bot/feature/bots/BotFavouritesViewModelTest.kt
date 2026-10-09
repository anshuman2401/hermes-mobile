package com.hermes.bot.feature.bots

import com.hermes.bot.data.bots.*
import com.hermes.bot.data.gateway.BotProfile
import com.hermes.bot.data.local.*
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotFavouritesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val origin = "http://example.invalid:8080"
    private class Repo : BotRepository by BotsViewModelTest.Repository() {
        val base = BotsViewModelTest.Repository()
        var names = listOf("Beta", "missing-bot", "Alpha")
        var fail = false
        var favouritesGate: CompletableDeferred<Unit>? = null
        var rosterGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        override val connectionStatus = base.connectionStatus
        override fun capture(target: BotTarget) = base.capture(target)
        override fun isCurrent(scope: BotScope) = base.isCurrent(scope)
        override suspend fun load(scope: BotScope, publish: (List<BotRow>) -> Unit) {
            rosterGate?.await()
            publish(listOf("Alpha", "Beta").map { BotRow(BotProfile(it,it,false)) })
        }
        private fun record(scope: BotScope) = BotFavourites(scope.target.localId, scope.target.normalizedOrigin, names,
            AuthCredentialLease(scope.target.localId, scope.target.normalizedOrigin, "synthetic-epoch",
                AuthCredential(driverKind = "cookie_session", providerId = "password", canonicalOrigin = scope.target.normalizedOrigin, cookies = mapOf("session_id" to "synthetic-cookie"))))
        override suspend fun favourites(scope: BotScope): BotFavourites {
            favouritesGate?.await()
            return record(scope)
        }
        override suspend fun setFavourite(scope: BotScope, expected: BotFavourites, name: String, favourite: Boolean): BotFavourites {
            saveGate?.let { withContext(NonCancellable) { it.await() } }
            if (fail) error("Synthetic failure")
            names = if (favourite) (names + name).distinct() else names - name
            return record(scope)
        }
    }
    private fun initial(vm: BotsViewModel, channel: String? = null, allowed: Boolean = true,
        localId: String = "synthetic-profile", connected: Boolean = true) =
        vm.takeInitialFavourite(vm.uiState.value, localId, origin, connected, channel, allowed)

    @Test fun initialFavouriteUsesSavedOrderOnceAndSkipsUnavailableNames() = runTest {
        val repo = Repo(); repo.names = listOf("missing-bot", "Beta", "Alpha")
        val vm = BotsViewModel(repo); vm.enter("synthetic-profile", origin); runCurrent()
        assertEquals("Beta", initial(vm)?.profile?.name)
        assertNull(initial(vm)) // same state/effect, including a failed selection, cannot loop
        vm.leave(); vm.enter("synthetic-profile", origin); runCurrent()
        assertEquals("Beta", initial(vm)?.profile?.name)
        assertEquals(repo.names, vm.favourites.value.names)
        vm.leave()
    }

    @Test fun initialFavouriteWaitsForBothLoadsRegardlessOfCompletionOrder() = runTest {
        for (favouritesFirst in listOf(true, false)) {
            val repo = Repo(); repo.favouritesGate = CompletableDeferred(); repo.rosterGate = CompletableDeferred()
            val vm = BotsViewModel(repo); vm.enter("synthetic-profile", origin); runCurrent()
            assertNull(initial(vm))
            (if (favouritesFirst) repo.favouritesGate else repo.rosterGate)!!.complete(Unit); runCurrent()
            assertNull(initial(vm))
            (if (favouritesFirst) repo.rosterGate else repo.favouritesGate)!!.complete(Unit); runCurrent()
            assertEquals("Beta", initial(vm)?.profile?.name)
            vm.leave()
        }
    }

    @Test fun initialFavouritePreservesSelectionAndDefersWhileSwitchBlockedOrSaving() = runTest {
        val repo = Repo(); val vm = BotsViewModel(repo); vm.enter("synthetic-profile", origin); runCurrent()
        assertNull(initial(vm, channel = "Alpha"))
        assertNull(initial(vm, channel = "missing-selected-bot"))
        assertNull(initial(vm, allowed = false))
        repo.saveGate = CompletableDeferred(); vm.setFavourite("Alpha", false); runCurrent()
        assertNull(initial(vm))
        repo.saveGate!!.complete(Unit); runCurrent()
        assertEquals("Beta", initial(vm)?.profile?.name)
        vm.leave()
    }

    @Test fun initialFavouriteRejectsEmptyMissingAndStaleConnectionOrExitedPage() = runTest {
        val repo = Repo(); repo.names = emptyList()
        val vm = BotsViewModel(repo); vm.enter("synthetic-profile", origin); runCurrent()
        assertNull(initial(vm))
        repo.names = listOf("missing-bot"); vm.retry(); runCurrent(); assertNull(initial(vm))
        repo.names = listOf("Beta"); vm.retry(); runCurrent()
        assertNull(initial(vm, localId = "other-profile"))
        assertNull(initial(vm, connected = false))
        assertNull(vm.takeInitialFavourite(vm.uiState.value, "synthetic-profile", "http://other.invalid", true, null, true))
        repo.base.generation++; assertNull(initial(vm))
        vm.enter("synthetic-profile", origin); runCurrent()
        assertEquals("Beta", initial(vm)?.profile?.name)
        val old = vm.uiState.value; vm.leave()
        assertNull(vm.takeInitialFavourite(old, "synthetic-profile", origin, true, null, true))
    }

    @Test fun savedOrderMissingNamesAndFailedSaveRemainHonest() = runTest {
        val repo = Repo(); val vm = BotsViewModel(repo); vm.enter("synthetic-profile", origin); runCurrent()
        assertEquals(repo.names, vm.favourites.value.names)
        repo.fail = true; vm.setFavourite("Alpha", false); runCurrent()
        assertEquals(repo.names, vm.favourites.value.names); assertNotNull(vm.favourites.value.error)
        repo.fail = false; vm.setFavourite("Alpha", false); runCurrent()
        assertEquals(listOf("Beta", "missing-bot"), vm.favourites.value.names)
        vm.setFavourite("Alpha", true); runCurrent(); assertEquals(listOf("Beta", "missing-bot", "Alpha"), vm.favourites.value.names)
        vm.setFavourite("not-in-roster", true); runCurrent(); assertEquals(3, vm.favourites.value.names.size)
        vm.leave()
    }
    @Test fun lateSaveCannotPublishIntoAnotherConnectionOrExitedPage() = runTest {
        for (exit in listOf(false,true)) {
            val repo = Repo(); val vm = BotsViewModel(repo); vm.enter("synthetic-profile", origin); runCurrent()
            repo.saveGate = CompletableDeferred(); vm.setFavourite("Alpha",false); runCurrent()
            if (exit) vm.leave() else vm.enter("other-profile", origin)
            runCurrent(); val before = vm.favourites.value
            repo.saveGate!!.complete(Unit); runCurrent(); assertEquals(before, vm.favourites.value)
            vm.leave()
        }
    }
}
