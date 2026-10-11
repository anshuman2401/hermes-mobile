package com.hermes.bot.data.local

import com.hermes.bot.data.queue.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatQueueVaultTest {
    @get:Rule val temp = TemporaryFolder()
    private val cipher = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    @Test fun queueEncryptedConversationPartitionBoundAndRemovedByReset() = runBlocking {
        val dir = temp.newFolder(); val vault = EncryptedVault(dir, cipher, ioDispatcher = Dispatchers.Unconfined)
        vault.saveConnectionProfile(ConnectionProfile("synthetic-profile", "http://example.test:9119", "Synthetic"))
        vault.setActiveProfileId("synthetic-profile")
        vault.saveAuthCredential(AuthCredential(driverKind = AuthCredential.DRIVER_COOKIE_SESSION, providerId = "password",
            cookies = mapOf("session_id" to "synthetic-cookie"), canonicalOrigin = "http://example.test:9119"), "synthetic-profile")
        val store = VaultChatQueueStore(vault) { "synthetic-chat" }
        val queue = ChatQueueCoordinator(store); queue.restore()
        assertTrue(queue.enqueue(QueuePayload.Prompt("synthetic-private-marker")))
        val files = dir.resolve("drafts").listFiles()!!
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { !String(it.readBytes()).contains("synthetic-private-marker") && !it.name.contains("synthetic-chat") })
        val restart = ChatQueueCoordinator(VaultChatQueueStore(EncryptedVault(dir, cipher, ioDispatcher = Dispatchers.Unconfined)) { "synthetic-chat" })
        restart.restore(); assertEquals(1, restart.state.value.waiting.size)
        assertEquals(QueuePause.RESTORED, restart.state.value.pause)
        val other = ChatQueueCoordinator(VaultChatQueueStore(vault) { "other-chat" }); other.restore()
        assertTrue(other.state.value.waiting.isEmpty())
        // A second instance must not overwrite a newly accepted entry from the first.
        val stale = ChatQueueCoordinator(VaultChatQueueStore(vault) { "synthetic-chat" }); stale.restore()
        // Refresh the first owner after the restart instance's harmless restore write.
        val fresh = ChatQueueCoordinator(VaultChatQueueStore(vault) { "synthetic-chat" }); fresh.restore()
        assertTrue(fresh.enqueue(QueuePayload.Prompt("synthetic-newer")))
        assertFalse(stale.enqueue(QueuePayload.Prompt("synthetic-stale")))
        val readback = ChatQueueCoordinator(VaultChatQueueStore(vault) { "synthetic-chat" }); readback.restore()
        assertEquals(2, readback.state.value.waiting.size)
        vault.saveSelectedContextProfile("synthetic-workspace")
        assertFalse(store.isCurrent())
        val foreign = ChatQueueCoordinator(VaultChatQueueStore(vault) { "synthetic-chat" }); foreign.restore()
        assertTrue(foreign.state.value.waiting.isEmpty())
        vault.saveAuthCredential(AuthCredential(driverKind = AuthCredential.DRIVER_COOKIE_SESSION, providerId = "password",
            cookies = mapOf("session_id" to "replacement-cookie"), canonicalOrigin = "http://example.test:9119"), "synthetic-profile")
        assertFalse(store.isCurrent())
        assertFalse(queue.enqueue(QueuePayload.Prompt("foreign-write")))
        vault.clearAll()
        assertTrue(dir.resolve("drafts").listFiles()!!.isEmpty())
    }
}
