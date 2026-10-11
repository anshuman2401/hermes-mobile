package com.hermes.bot.feature.bots

import com.hermes.bot.data.bots.*
import com.hermes.bot.data.gateway.BotProfile
import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class BotResetMemoryTest {
    @Test fun bothLocalResetPathsClearBeforeStorageAndAgainOnFailure() = runTest {
        for (fullReset in listOf(false, true)) {
            val memory = BotMemoryCache()
            val target = BotTarget("synthetic", "https://test.hermes.local")
            val origin = CanonicalOrigin.parse(target.normalizedOrigin).getOrThrow()
            memory.bind(target, 1)
            val scope = BotScope(target, origin, 1)
            memory.rememberRoster(scope, listOf(BotProfile("bot", "Bot", false)), 0) { true }
            var clears = 0; memory.observeClear { clears++ }
            // Only the reset contract is mocked; no auth or platform security is emulated.
            val vault = Proxy.newProxyInstance(VaultStorage::class.java.classLoader, arrayOf(VaultStorage::class.java)) { _, method, _ ->
                assertEquals(if (fullReset) "clearAll" else "clearCache", method.name)
                assertNull(memory.read(scope) { true }); assertEquals(1, clears)
                error("Synthetic storage failure")
            } as VaultStorage
            val coordinator = DefaultLocalDataResetCoordinator(vault) { memory.clear() }
            try {
                if (fullReset) coordinator.clearAllLocalData() else coordinator.clearOfflineCache()
                fail("The coordinator must preserve the storage failure")
            } catch (_: IllegalStateException) { }
            assertEquals(2, clears); assertNull(memory.read(scope) { true })
        }
    }

    @Test fun explicitTeardownBlocksOldConnectionUntilNewSocketGeneration() {
        val memory = BotMemoryCache(); val target = BotTarget("synthetic", "https://test.hermes.local")
        memory.bind(target, 1); memory.disconnect(1)
        assertFalse(memory.bind(target, 1)); assertFalse(memory.connectionAllowed(1))
        memory.clear() // Cache reset cannot reopen the signed-out socket generation.
        assertFalse(memory.bind(target, 1)); assertTrue(memory.bind(target, 2))
    }
}
