package com.hermes.bot.data.local

import com.hermes.bot.data.cache.ConversationCacheReadResult
import com.hermes.bot.data.cache.ConversationCacheWriteResult
import com.hermes.bot.data.cache.VaultConversationCacheRepository
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real encrypted vault with test keys, no network or platform-security emulation. */
class ConversationCacheReadOwnerTest {
    @get:Rule val temp = TemporaryFolder()
    private val origin = CanonicalOrigin.parse("http://example.test:9711").getOrThrow()
    private fun credential(value: String = "synthetic-cookie") =
        AuthCredential("cookie_session", "basic", mapOf("session" to value), origin.originUrl)

    private suspend fun vault(): EncryptedVault = EncryptedVault(temp.newFolder(),
        AesGcmCipherEngine(InMemoryMasterKeyProvider())).also {
        it.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
        it.saveAuthCredential(credential())
        assertTrue(it.prepareLocalLogin(origin.originUrl))
        it.saveSelectedContextProfile("default")
    }

    private suspend fun seed(cache: VaultConversationCacheRepository) {
        assertEquals(ConversationCacheWriteResult.Written, cache.replaceRemoteSnapshot("synthetic-session",
            SessionResumeResult("synthetic-runtime", "synthetic-session", 1,
                listOf(SessionMessage("assistant", "Synthetic cached answer"))), System.currentTimeMillis()))
    }

    @Test fun readOwnerSurvivesCookieRenewalWhileNetworkLeaseIsRevoked() = runBlocking {
        val vault = vault()
        val cache = VaultConversationCacheRepository(vault)
        seed(cache)
        val owner = requireNotNull(cache.captureReadOwner())
        val lease = requireNotNull(vault.readAuthCredentialLease(origin.originUrl))
        assertTrue(vault.mutateAuthCredentialIfCurrent(lease, credential("synthetic-renewed"), false)
            is AuthCredentialMutationResult.Accepted)
        assertTrue(cache.isReadOwnerCurrent(owner))
        assertTrue(cache.loadForReadOwner("synthetic-session", owner) is ConversationCacheReadResult.Snapshot)
        assertEquals(ConversationCacheReadResult.Unavailable,
            cache.loadScoped("synthetic-session", lease, "default"))
    }

    @Test fun revocationRejectsPreviouslyCapturedReadOwnerAndSnapshot() = runBlocking {
        for (mutation in listOf("login", "signout", "workspace", "profile", "origin", "cache", "conversation", "all")) {
            val vault = vault()
            val cache = VaultConversationCacheRepository(vault)
            seed(cache)
            val owner = requireNotNull(cache.captureReadOwner())
            when (mutation) {
                "login" -> vault.saveAuthCredential(credential("synthetic-other-login"))
                "signout" -> vault.clearAuthCredential(reason = CacheEpochRotationReason.SIGNED_OUT)
                "workspace" -> vault.saveSelectedContextProfile("synthetic-other")
                "profile" -> {
                    vault.saveConnectionProfile(ConnectionProfile("synthetic-other", origin.originUrl, "Synthetic other"))
                    vault.setActiveProfileId("synthetic-other")
                }
                "origin" -> vault.saveConnectionProfile(ConnectionProfile("synthetic-local", "http://other.example.test:9711", "Synthetic"))
                "cache" -> { vault.clearAllCacheRecords(); seed(cache) }
                "conversation" -> { vault.clearConversationCache(); seed(cache) }
                else -> vault.clearAll()
            }
            assertFalse(mutation, cache.isReadOwnerCurrent(owner))
            assertEquals(mutation, ConversationCacheReadResult.Unavailable,
                cache.loadForReadOwner("synthetic-session", owner))
        }
    }

    @Test fun unreadableSelectionRevokesReadOwnerInsteadOfBecomingDefault() = runBlocking {
        val vault = vault()
        val cache = VaultConversationCacheRepository(vault)
        seed(cache)
        val owner = requireNotNull(cache.captureReadOwner())
        // The selection record must remain authoritative even for a local-only read.
        val folder = temp.root.listFiles()!!.single()
        java.io.File(folder, "context_profile_selection.enc").writeText("Synthetic damaged selection")
        assertNull(cache.captureReadOwner())
        assertFalse(cache.isReadOwnerCurrent(owner))
        assertEquals(ConversationCacheReadResult.Unavailable, cache.loadForReadOwner("synthetic-session", owner))
    }
    @Test fun endpointSelectionInvalidatesPreviewObserversAndReadOwner() = runBlocking {
        val vault = vault()
        val otherOrigin = "http://other.example.test:9711"
        vault.saveConnectionProfile(ConnectionProfile("synthetic-other", otherOrigin, "Synthetic other"))
        vault.setActiveProfileId("synthetic-local")
        val owner = requireNotNull(vault.captureConversationCacheReadOwner())
        var invalidations = 0
        val remove = vault.observeConversationPreviewInvalidation { invalidations++ }
        assertTrue(vault.selectConnectionForEndpoint(otherOrigin))
        assertEquals(1, invalidations)
        assertFalse(vault.withConversationPreviewOwner(owner) { fail("Stale publication") })
        assertFalse(vault.isConversationCacheReadOwnerCurrent(owner))
        remove()
    }

    @Test fun rememberedLoginPublicationInvalidatesPreviewAuthority() = runBlocking {
        val vault = vault()
        val owner = requireNotNull(vault.captureConversationCacheReadOwner())
        val loginOwner = requireNotNull(vault.captureLoginOwner(origin.originUrl))
        var invalidations = 0
        val remove = vault.observeConversationPreviewInvalidation { invalidations++ }
        assertTrue(vault.commitRememberedLogin(loginOwner, credential(), "synthetic-user",
            "synthetic-password".toCharArray(), remember = true))
        assertTrue(invalidations > 0)
        assertFalse(vault.withConversationPreviewOwner(owner) { fail("Stale publication") })
        remove()
    }

    @Test fun forgettingLoginInvalidatesPreviewOwnerButPreservesCookie() = runBlocking {
        val vault = vault()
        val owner = requireNotNull(vault.captureConversationCacheReadOwner())
        var invalidations = 0
        val remove = vault.observeConversationPreviewInvalidation { invalidations++ }
        vault.forgetRememberedLogin(origin.originUrl)
        assertEquals(1, invalidations)
        assertFalse(vault.withConversationPreviewOwner(owner) { fail("Stale publication") })
        assertNotNull(vault.getAuthCredential())
        remove()
    }

}
