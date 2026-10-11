package com.hermes.bot.data.local

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.bot.data.cache.ConversationCacheReadResult
import com.hermes.bot.data.cache.ConversationCacheWriteResult
import com.hermes.bot.data.cache.VaultConversationCacheRepository
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Isolated synthetic storage/key only. The runner never opens the production graph or vault. */
class ConversationCacheReadDeviceTest {
    @Test fun fiveCacheReadsSurviveCookieRenewalWithAndroidKeystore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nonce = UUID.randomUUID().toString()
        val folder = File(context.cacheDir, "synthetic-cache-preview-$nonce").apply { mkdirs() }
        val alias = "synthetic-cache-preview-$nonce"
        val origin = "http://example.test:9711"
        fun credential(cookie: String) = AuthCredential("cookie_session", "basic", mapOf("session" to cookie), origin)
        try {
            val vault = EncryptedVault(folder, AesGcmCipherEngine(AndroidKeyStoreMasterKeyProvider(alias)))
            vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin, "Synthetic"))
            vault.saveAuthCredential(credential("synthetic-cookie"))
            assertTrue(vault.prepareLocalLogin(origin))
            vault.saveSelectedContextProfile("default")
            val cache = VaultConversationCacheRepository(vault)
            val messages = List(100) { SessionMessage(if (it % 2 == 0) "user" else "assistant",
                "Synthetic cached message ".repeat(20)) }
            assertEquals(ConversationCacheWriteResult.Written, cache.replaceRemoteSnapshot("synthetic-session",
                SessionResumeResult("synthetic-runtime", "synthetic-session", messages.size, messages), System.currentTimeMillis()))
            val durations = mutableListOf<Long>()
            repeat(5) { attempt ->
                val started = SystemClock.elapsedRealtime()
                val owner = requireNotNull(cache.captureReadOwner())
                val lease = requireNotNull(vault.readAuthCredentialLease(origin))
                assertTrue(vault.mutateAuthCredentialIfCurrent(lease, credential("synthetic-renewal-$attempt"), false)
                    is AuthCredentialMutationResult.Accepted)
                val snapshot = cache.loadForReadOwner("synthetic-session", owner) as? ConversationCacheReadResult.Snapshot
                assertNotNull(snapshot)
                assertEquals(messages.size, snapshot!!.value.rows.size)
                assertTrue(cache.isReadOwnerCurrent(owner))
                durations.add(SystemClock.elapsedRealtime() - started)
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("synthetic_cache_read_and_renewal_ms", durations.joinToString(","))
            })
            vault.clearAllCacheRecords()
            assertTrue(cache.load("synthetic-session") is ConversationCacheReadResult.Miss)
        } finally {
            folder.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
