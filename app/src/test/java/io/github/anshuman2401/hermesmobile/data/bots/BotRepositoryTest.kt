package io.github.anshuman2401.hermesmobile.data.bots

import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotRepositoryTest {
    private val origin = CanonicalOrigin.parse("https://test.hermes.local").getOrThrow()
    private val target = BotTarget("local", origin.originUrl)
    private inner class Gateway : GatewayProtocolAdapter by FakeGatewayProtocolAdapter() {
        override val connectionStatus = kotlinx.coroutines.flow.MutableStateFlow(ConnectionStatus.CONNECTED)
        var generation = 1L
        var currentOrigin = origin
        override val connectionGeneration get() = generation
        val requested = mutableListOf<String>()
        var roster: suspend () -> GatewayResult<List<BotProfile>> = { GatewayResult.Success(listOf(BotProfile("first", "First", true), BotProfile("second", "Second", true))) }
        var asset: suspend (String) -> BotAvatar? = { null }
        override fun isScopedConnectionCurrent(origin: CanonicalOrigin, generation: Long) = connectionStatus.value == ConnectionStatus.CONNECTED && origin == currentOrigin && generation == this.generation
        override suspend fun listBotProfiles(expectedOrigin: CanonicalOrigin, expectedGeneration: Long) = roster()
        override suspend fun getBotAvatar(name: String, expectedOrigin: CanonicalOrigin, expectedGeneration: Long): BotAvatar? { requested += name; return asset(name) }
    }

    @Test fun invalidOfflineOrOtherOriginCaptureFailsClosed() {
        val gateway = Gateway()
        val repo = DefaultBotRepository(gateway)
        assertNull(repo.capture(BotTarget("", origin.originUrl)))
        assertNull(repo.capture(BotTarget("local", "invalid")))
        assertNull(repo.capture(BotTarget("local", "https://other.hermes.local")))
        gateway.connectionStatus.value = ConnectionStatus.OFFLINE
        assertNull(repo.capture(target))
    }

    @Test fun avatarFailureDoesNotFailRosterOrPreventNextAsset() = runTest {
        val gateway = Gateway()
        gateway.asset = { if (it == "first") error("synthetic failure") else BotAvatar(byteArrayOf(1), "image/png") }
        val repo = DefaultBotRepository(gateway)
        val rows = mutableListOf<List<BotRow>>()
        repo.load(repo.capture(target)!!, rows::add)
        assertEquals(listOf("first", "second"), gateway.requested)
        assertNull(rows.last()[0].avatar)
        assertNotNull(rows.last()[1].avatar)
    }

    @Test fun connectionChangeDuringRosterRejectsPublication() = runTest {
        for (changeOrigin in listOf(false, true)) {
            val gateway = Gateway()
            val release = CompletableDeferred<Unit>()
            gateway.roster = { release.await(); GatewayResult.Success(listOf(BotProfile("old", "Old", true))) }
            val repo = DefaultBotRepository(gateway)
            val rows = mutableListOf<List<BotRow>>()
            var rejected = false
            val job = launch { try { repo.load(repo.capture(target)!!, rows::add) } catch (_: IllegalStateException) { rejected = true } }
            runCurrent()
            if (changeOrigin) gateway.currentOrigin = CanonicalOrigin.parse("https://other.hermes.local").getOrThrow() else gateway.generation++
            release.complete(Unit); job.join()
            assertTrue(rejected)
            assertTrue(rows.isEmpty())
            assertTrue(gateway.requested.isEmpty())
        }
    }

    @Test fun connectionChangeDuringAvatarRejectsLateAssetAndNextOldName() = runTest {
        for (changeOrigin in listOf(false, true)) {
            val gateway = Gateway()
            val release = CompletableDeferred<Unit>()
            gateway.asset = { release.await(); BotAvatar(byteArrayOf(1), "image/png") }
            val repo = DefaultBotRepository(gateway)
            val rows = mutableListOf<List<BotRow>>()
            var rejected = false
            val job = launch { try { repo.load(repo.capture(target)!!, rows::add) } catch (_: IllegalStateException) { rejected = true } }
            runCurrent()
            assertEquals(1, rows.size)
            if (changeOrigin) gateway.currentOrigin = CanonicalOrigin.parse("https://other.hermes.local").getOrThrow() else gateway.generation++
            release.complete(Unit); job.join()
            assertTrue(rejected)
            assertEquals(1, rows.size)
            assertEquals(listOf("first"), gateway.requested)
        }
    }

    @Test fun generationChangeBetweenAssetsPreventsNextRequest() = runTest {
        val gateway = Gateway()
        gateway.asset = { BotAvatar(byteArrayOf(1), "image/png") }
        val repo = DefaultBotRepository(gateway)
        var publications = 0
        try {
            repo.load(repo.capture(target)!!) { publications++; if (publications == 2) gateway.generation++ }
            fail("stale next read must be rejected")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("first"), gateway.requested)
        assertEquals(2, publications)
    }

    @Test fun cancellationPropagatesAndDoesNotContinueAssets() = runTest {
        val gateway = Gateway()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        gateway.asset = { entered.complete(Unit); try { release.await(); null } finally { cancelled = true } }
        val repo = DefaultBotRepository(gateway)
        val job = launch { repo.load(repo.capture(target)!!) { } }
        entered.await()
        job.cancel(); job.join()
        assertTrue(job.isCancelled)
        assertTrue(cancelled)
        assertEquals(listOf("first"), gateway.requested)
    }
    @Test fun fallbackRosterPublishesBeforeDelayedAvatarAndSkipsAbsentAssets() = runTest {
        val base = FakeGatewayProtocolAdapter()
        val avatar = CompletableDeferred<BotAvatar?>()
        val requested = mutableListOf<String>()
        val gateway = object : GatewayProtocolAdapter by base {
            override val connectionStatus = kotlinx.coroutines.flow.MutableStateFlow(ConnectionStatus.CONNECTED)
            override val connectionGeneration = 7L
            override fun isScopedConnectionCurrent(origin: CanonicalOrigin, generation: Long) = origin == this@BotRepositoryTest.origin && generation == 7L
            override suspend fun listBotProfiles(expectedOrigin: CanonicalOrigin, expectedGeneration: Long) = GatewayResult.Success(listOf(BotProfile("default", "Default", true), BotProfile("other", "Other", false)))
            override suspend fun getBotAvatar(name: String, expectedOrigin: CanonicalOrigin, expectedGeneration: Long): BotAvatar? {
                requested += name
                return avatar.await()
            }
        }
        val repo = DefaultBotRepository(gateway)
        val scope = repo.capture(target)
        assertNotNull(scope)
        val emissions = mutableListOf<List<BotRow>>()
        val job = launch { repo.load(scope!!, emissions::add) }
        runCurrent()
        assertEquals(listOf("default", "other"), emissions.single().map { it.profile.name })
        assertTrue(emissions.single().all { it.avatar == null })
        assertEquals(listOf("default"), requested)
        avatar.complete(BotAvatar(byteArrayOf(1), "image/png"))
        job.join()
        assertNotNull(emissions.last().first().avatar)
        assertEquals(listOf("default"), requested)
    }
}
