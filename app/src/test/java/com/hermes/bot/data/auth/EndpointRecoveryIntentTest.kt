package com.hermes.bot.data.auth

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.local.SignOutCacheChoice
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class EndpointRecoveryIntentTest {
    @Test fun backgroundFailureDoesNotConsumeConcurrentExplicitConnect() = runBlocking {
        val origin = CanonicalOrigin.parseOrThrow("http://fixture.invalid:9119")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var valid = false
        val intents = mutableListOf<Boolean>()
        val auth = object : AuthRepository {
            override suspend fun hasValidSession(origin: CanonicalOrigin?) = valid
            override suspend fun recoverSession(origin: CanonicalOrigin, explicitConnect: Boolean): Result<Unit> {
                intents.add(explicitConnect)
                if (!explicitConnect) {
                    entered.complete(Unit)
                    release.await()
                    return Result.failure(AuthSessionRejectedException())
                }
                valid = true
                return Result.success(Unit)
            }
            override suspend fun mintWebSocketTicket(origin: CanonicalOrigin) = Result.success(GatewayTicket("synthetic-ticket"))
            override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
            override suspend fun notifyAuthExpired() {}
        }
        val fake = FakeGatewayProtocolAdapter().apply { connectionStatus.value = ConnectionStatus.OFFLINE }
        val gateway = object : GatewayProtocolAdapter by fake {
            override suspend fun connect(origin: String, ticket: GatewayTicket, useQueryFallback: Boolean): Boolean {
                fake.connectionStatus.value = ConnectionStatus.CONNECTED
                return true
            }
        }
        val coordinator = ConnectionRecoveryCoordinator(auth, gateway)
        val background = async(Dispatchers.Unconfined) { coordinator.connect(origin) }
        entered.await()
        val explicit = async(Dispatchers.Unconfined) { coordinator.connect(origin, explicitConnect = true) }
        release.complete(Unit)
        assertFalse(background.await())
        assertTrue(explicit.await())
        assertEquals(listOf(false, true), intents)
        assertEquals(0, fake.submitPromptCallCount)
    }
}
