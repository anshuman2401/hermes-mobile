package com.hermes.bot.data.bots

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotRepositoryTest {
    private val origin = CanonicalOrigin.parse("https://test.hermes.local").getOrThrow()
    private val target = BotTarget("local", origin.originUrl)
    private fun image(value: Int = 1) = BotAvatar(byteArrayOf(value.toByte()), "image/png")
    private inner class Gateway : GatewayProtocolAdapter by FakeGatewayProtocolAdapter() {
        override val connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
        var generation = 1L
        var currentOrigin = origin
        override val connectionGeneration get() = generation
        val requested = mutableListOf<String>()
        var rosterCalls = 0
        var profiles = listOf(BotProfile("first", "First", true), BotProfile("second", "Second", true))
        var roster: suspend () -> GatewayResult<List<BotProfile>> = { GatewayResult.Success(profiles) }
        var asset: suspend (String) -> BotAvatar? = { image() }
        override fun isScopedConnectionCurrent(origin: CanonicalOrigin, generation: Long) =
            connectionStatus.value == ConnectionStatus.CONNECTED && origin == currentOrigin && generation == this.generation
        override suspend fun listBotProfiles(expectedOrigin: CanonicalOrigin, expectedGeneration: Long): GatewayResult<List<BotProfile>> {
            rosterCalls++; return roster()
        }
        override suspend fun getBotAvatar(name: String, expectedOrigin: CanonicalOrigin, expectedGeneration: Long): BotAvatar? {
            requested += name; return asset(name)
        }
    }

    @Test fun invalidOfflineOrOtherOriginCaptureFailsClosed() {
        val gateway = Gateway(); val repo = DefaultBotRepository(gateway)
        assertNull(repo.capture(BotTarget("", origin.originUrl)))
        assertNull(repo.capture(BotTarget("local", "invalid")))
        assertNull(repo.capture(BotTarget("local", "https://other.hermes.local")))
        gateway.connectionStatus.value = ConnectionStatus.OFFLINE
        assertNull(repo.capture(target))
    }

    @Test fun rosterPublishesWithoutFetchingHiddenAvatars() = runTest {
        val gateway = Gateway(); val repo = DefaultBotRepository(gateway)
        val rows = mutableListOf<List<BotRow>>()
        repo.load(repo.capture(target)!!, rows::add)
        assertEquals(listOf("first", "second"), rows.single().map { it.profile.name })
        assertTrue(rows.single().all { it.avatar == null })
        assertTrue(gateway.requested.isEmpty())
    }

    @Test fun warmEntrySkipsRosterUntilExactExpiryAndForceBypassesFreshness() = runTest {
        var now = 100L
        val gateway = Gateway(); val repo = DefaultBotRepository(gateway, clock = { now })
        val scope = repo.capture(target)!!
        repo.load(scope) {}; now += 59_999; repo.load(scope) {}
        assertEquals(1, gateway.rosterCalls)
        assertTrue(repo.isFresh(scope))
        now++; assertFalse(repo.isFresh(scope)); repo.load(scope) {}
        assertEquals(2, gateway.rosterCalls)
        repo.refresh(scope) {}; assertEquals(3, gateway.rosterCalls)
        now = -1; assertFalse(repo.isFresh(scope))
    }

    @Test fun concurrentRosterRefreshesShareOneRead() = runTest {
        val gateway = Gateway(); val repo = DefaultBotRepository(gateway)
        val scope = repo.capture(target)!!; repo.load(scope) {}
        val release = CompletableDeferred<Unit>()
        gateway.roster = { release.await(); GatewayResult.Success(gateway.profiles) }
        val first = launch { repo.refresh(scope) {} }; runCurrent()
        val second = launch { repo.refresh(scope) {} }; runCurrent()
        assertEquals(2, gateway.rosterCalls)
        release.complete(Unit); first.join(); second.join()
        assertEquals(2, gateway.rosterCalls)
    }

    @Test fun avatarPriorityAndThreeRequestLimitFollowVisibleDemand() = runTest {
        val gateway = Gateway()
        gateway.profiles = (1..6).map { BotProfile("bot$it", "Bot", true) }
        val release = CompletableDeferred<Unit>(); var active = 0; var peak = 0
        val cancelled = mutableListOf<String>()
        gateway.asset = { name ->
            active++; peak = maxOf(peak, active)
            try { release.await(); image() } finally { active--; if (!release.isCompleted) cancelled += name }
        }
        val repo = DefaultBotRepository(gateway); val scope = repo.capture(target)!!; repo.load(scope) {}
        val demand = MutableStateFlow(listOf("bot3", "bot1", "bot2", "bot4", "bot1"))
        val job = launch { repo.avatars(scope, demand) {} }; runCurrent()
        assertEquals(listOf("bot3", "bot1", "bot2"), gateway.requested)
        assertEquals(3, peak)
        demand.value = listOf("bot3", "bot4"); runCurrent()
        assertEquals(setOf("bot1", "bot2"), cancelled.toSet())
        assertEquals(listOf("bot3", "bot1", "bot2", "bot4"), gateway.requested)
        release.complete(Unit); runCurrent()
        assertNotNull(repo.cached(scope)!!.rows.first { it.profile.name == "bot4" }.avatar)
        assertFalse(gateway.requested.contains("bot5"))
        job.cancelAndJoin()
    }

    @Test fun avatarFailureDoesNotFailRosterOrOtherAssetsAndIsNotRetriedByDemandChanges() = runTest {
        val gateway = Gateway()
        gateway.asset = { if (it == "first") error("synthetic failure") else image() }
        val repo = DefaultBotRepository(gateway); val scope = repo.capture(target)!!; repo.load(scope) {}
        val demand = MutableStateFlow(listOf("first", "second"))
        val job = launch { repo.avatars(scope, demand) {} }; runCurrent()
        val rows = repo.cached(scope)!!.rows
        assertNull(rows[0].avatar); assertNotNull(rows[1].avatar)
        demand.value = emptyList(); runCurrent(); demand.value = listOf("first"); runCurrent()
        assertEquals(1, gateway.requested.count { it == "first" })
        job.cancelAndJoin()
    }

    @Test fun parallelDemandAndReentryDeduplicateSameAvatar() = runTest {
        val gateway = Gateway(); val release = CompletableDeferred<Unit>()
        gateway.asset = { release.await(); image() }
        val repo = DefaultBotRepository(gateway); val scope = repo.capture(target)!!; repo.load(scope) {}
        val demand = MutableStateFlow(listOf("first", "first"))
        val first = launch { repo.avatars(scope, demand) {} }
        val second = launch { repo.avatars(scope, demand) {} }; runCurrent()
        assertEquals(listOf("first"), gateway.requested)
        release.complete(Unit); runCurrent(); first.cancelAndJoin(); second.cancelAndJoin()
        val revisit = launch { repo.avatars(scope, demand) {} }; runCurrent()
        assertEquals(listOf("first"), gateway.requested)
        revisit.cancelAndJoin()
    }

    @Test fun refreshRevalidatesAvatarWithoutBlankingItAndRemovesAbsentAssets() = runTest {
        val gateway = Gateway(); val repo = DefaultBotRepository(gateway); val scope = repo.capture(target)!!
        repo.load(scope) {}
        var job = launch { repo.avatars(scope, MutableStateFlow(listOf("first", "second"))) {} }
        runCurrent(); job.cancelAndJoin()
        val old = repo.cached(scope)!!.rows.first().avatar
        gateway.profiles = listOf(BotProfile("first", "Renamed", true), BotProfile("second", "Second", false))
        repo.refresh(scope) {}
        assertSame(old, repo.cached(scope)!!.rows.first().avatar)
        assertNull(repo.cached(scope)!!.rows.last().avatar)
        gateway.asset = { image(2) }
        job = launch { repo.avatars(scope, MutableStateFlow(listOf("first", "second"))) {} }; runCurrent()
        assertEquals(2, gateway.requested.count { it == "first" })
        assertEquals(1, gateway.requested.count { it == "second" })
        assertEquals(2.toByte(), repo.cached(scope)!!.rows.first().avatar!!.bytes.single())
        job.cancelAndJoin()
    }

    @Test fun encodedCacheEvictionAllowsRefetchOnRevisit() = runTest {
        val gateway = Gateway(); val repo = DefaultBotRepository(gateway, memory = BotMemoryCache(maxAvatarBytes = 1))
        val scope = repo.capture(target)!!; repo.load(scope) {}
        var job = launch { repo.avatars(scope, MutableStateFlow(listOf("first", "second"))) {} }
        runCurrent(); job.cancelAndJoin()
        assertEquals(1, repo.cached(scope)!!.rows.count { it.avatar != null })
        val missing = repo.cached(scope)!!.rows.first { it.avatar == null }.profile.name
        job = launch { repo.avatars(scope, MutableStateFlow(listOf(missing))) {} }; runCurrent()
        assertEquals(2, gateway.requested.count { it == missing }); job.cancelAndJoin()
    }

    @Test fun scrollingBackRefetchesEvictedAvatarWithoutRestartingCoordinator() = runTest {
        val gateway = Gateway(); val repo = DefaultBotRepository(gateway, memory = BotMemoryCache(maxAvatarBytes = 1))
        val scope = repo.capture(target)!!; repo.load(scope) {}
        val demand = MutableStateFlow(listOf("first"))
        val job = launch { repo.avatars(scope, demand) {} }; runCurrent()
        demand.value = listOf("second"); runCurrent()
        assertNull(repo.cached(scope)!!.rows.first().avatar)
        demand.value = listOf("first"); runCurrent()
        assertEquals(listOf("first", "second", "first"), gateway.requested); job.cancelAndJoin()
    }

    @Test fun cancellationDoesNotNegativeCacheAvatar() = runTest {
        val gateway = Gateway(); val release = CompletableDeferred<Unit>(); var cancelled = 0
        gateway.asset = { try { release.await(); image() } finally { if (!release.isCompleted) cancelled++ } }
        val repo = DefaultBotRepository(gateway); val scope = repo.capture(target)!!; repo.load(scope) {}
        var job = launch { repo.avatars(scope, MutableStateFlow(listOf("first"))) {} }; runCurrent()
        job.cancelAndJoin(); assertEquals(1, cancelled)
        release.complete(Unit)
        job = launch { repo.avatars(scope, MutableStateFlow(listOf("first"))) {} }; runCurrent()
        assertEquals(listOf("first", "first"), gateway.requested); job.cancelAndJoin()
    }

    @Test fun connectionChangeDuringRosterRejectsPublication() = runTest {
        for (changeOrigin in listOf(false, true)) {
            val gateway = Gateway(); val release = CompletableDeferred<Unit>()
            gateway.roster = { release.await(); GatewayResult.Success(gateway.profiles) }
            val repo = DefaultBotRepository(gateway); val rows = mutableListOf<List<BotRow>>()
            var rejected = false
            val job = launch { try { repo.load(repo.capture(target)!!, rows::add) } catch (_: IllegalStateException) { rejected = true } }
            runCurrent()
            if (changeOrigin) gateway.currentOrigin = CanonicalOrigin.parse("https://other.hermes.local").getOrThrow() else gateway.generation++
            release.complete(Unit); job.join()
            assertTrue(rejected); assertTrue(rows.isEmpty()); assertTrue(gateway.requested.isEmpty())
        }
    }

    @Test fun clearRejectsNonCooperativeRosterAndAvatarCompletions() = runTest {
        for (duringRoster in listOf(true, false)) {
            val gateway = Gateway(); val release = CompletableDeferred<Unit>(); val repo = DefaultBotRepository(gateway)
            val scope = repo.capture(target)!!; val rows = mutableListOf<List<BotRow>>()
            val job = if (duringRoster) {
                gateway.roster = { withContext(NonCancellable) { release.await() }; GatewayResult.Success(gateway.profiles) }
                launch { try { repo.load(scope, rows::add) } catch (_: IllegalStateException) {} }
            } else {
                repo.load(scope) {}
                gateway.asset = { withContext(NonCancellable) { release.await() }; image() }
                launch { repo.avatars(scope, MutableStateFlow(listOf("first")), rows::add) }
            }
            runCurrent(); val before = rows.size
            repo.clearCache(); assertFalse(repo.isCurrent(scope)); assertNull(repo.cached(scope))
            release.complete(Unit); runCurrent(); job.cancelAndJoin()
            assertEquals(before, rows.size)
            val newScope = repo.capture(target)!!
            assertNull(repo.cached(newScope)); assertTrue(repo.isCurrent(newScope))
        }
    }

    @Test fun sameOriginReconnectAndDifferentConnectionNeverReuseRoster() = runTest {
        val gateway = Gateway(); val repo = DefaultBotRepository(gateway)
        val oldScope = repo.capture(target)!!; repo.load(oldScope) {}
        gateway.generation += 2
        val current = repo.capture(target)!!
        assertNull(repo.cached(oldScope)); assertNull(repo.cached(current)); repo.load(current) {}
        val other = repo.capture(target.copy(localId = "other"))!!
        assertNull(repo.cached(other)); assertFalse(repo.isCurrent(current))
    }
}
