package com.hermes.bot.feature.chat

import com.hermes.bot.data.local.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatPreviewRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val origin = "http://example.test:9711"
    private fun credential(value: String = "synthetic-cookie") =
        AuthCredential("cookie_session", "basic", mapOf("session" to value), origin)
    private suspend fun vault() = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider())).also {
        it.saveConnectionProfile(ConnectionProfile("synthetic-local", origin, "Synthetic"))
        it.saveAuthCredential(credential())
        it.prepareLocalLogin(origin)
        it.saveSelectedContextProfile("default")
    }
    private fun rows(body: String = "Synthetic saved answer") = listOf(AssistantMessage("synthetic-row", body, timestamp = 0))
    private fun peek(memory: ChatPreviewRepository, id: String = "synthetic-session"): ChatPreviewSnapshot? {
        var result: ChatPreviewSnapshot? = null
        memory.publishIfPresent(id) { result = it }
        return result
    }

    @Test fun warmLookupUsesOnlyProcessAuthorityAndSurvivesCookieRenewal() = runBlocking {
        val vault = vault()
        val owner = requireNotNull(vault.captureConversationCacheReadOwner())
        val noDisk = object : VaultStorage by vault {
            override suspend fun captureConversationCacheReadOwner(): ConversationCacheReadOwner? = error("No warm IO")
            override suspend fun isConversationCacheReadOwnerCurrent(owner: ConversationCacheReadOwner): Boolean = error("No warm IO")
        }
        ChatPreviewRepository(noDisk).use { memory ->
            assertTrue(memory.remember(owner, "synthetic-session", rows(), System.currentTimeMillis(), null, false))
            val lease = requireNotNull(vault.readAuthCredentialLease(origin))
            assertTrue(vault.mutateAuthCredentialIfCurrent(lease, credential("synthetic-successor"), false) is AuthCredentialMutationResult.Accepted)
            assertNotNull(peek(memory))
        }
    }

    @Test fun allOwnershipAndDeletionMutationsRevokeMemoryIncludingFailedMutation() = runBlocking {
        for (mutation in listOf("login", "signout", "profile", "origin", "workspace", "clear-workspace", "cache", "delete", "reset", "failed-profile")) {
            val vault = vault()
            val owner = requireNotNull(vault.captureConversationCacheReadOwner())
            ChatPreviewRepository(vault).use { memory ->
                assertTrue(memory.remember(owner, "synthetic-session", rows(), System.currentTimeMillis(), null, false))
                when (mutation) {
                    "login" -> vault.saveAuthCredential(credential("synthetic-other"))
                    "signout" -> vault.clearAuthCredential(reason = CacheEpochRotationReason.SIGNED_OUT)
                    "profile" -> vault.saveConnectionProfile(ConnectionProfile("synthetic-other", origin, "Other"))
                    "origin" -> vault.saveConnectionProfile(ConnectionProfile("synthetic-local", "http://other.example.test:9711", "Other"))
                    "workspace" -> vault.saveSelectedContextProfile("other")
                    "clear-workspace" -> vault.clearSelectedContextProfile()
                    "cache" -> vault.clearAllCacheRecords()
                    "delete" -> vault.deleteConversationSnapshot(owner.partition, "synthetic-session")
                    "reset" -> vault.clearAll()
                    else -> assertFalse(vault.setActiveProfileId("synthetic-missing"))
                }
                assertNull(mutation, peek(memory))
                assertFalse(mutation, memory.remember(owner, "synthetic-session", rows(), System.currentTimeMillis(), null, false))
                // Recapture cannot resurrect entries discarded by invalidation.
                vault.captureConversationCacheReadOwner()
                assertNull(mutation, peek(memory))
            }
        }
    }

    @Test fun lruExpiryAndPayloadBudgetEvictWithoutPartialRows() = runBlocking {
        val vault = vault()
        val owner = requireNotNull(vault.captureConversationCacheReadOwner())
        var now = 1000L
        ChatPreviewRepository(vault) { now }.use { memory ->
            repeat(4) { assertTrue(memory.remember(owner, "synthetic-$it", rows(), now, null, false)) }
            assertNotNull(peek(memory, "synthetic-0"))
            memory.remember(owner, "synthetic-4", rows(), now, null, false)
            assertNull(peek(memory, "synthetic-1"))
            assertNotNull(peek(memory, "synthetic-0"))
            assertFalse(memory.remember(owner, "synthetic-huge", rows("x".repeat(ChatPreviewRepository.MAX_BYTES)), now, null, false))
            assertNull(peek(memory, "synthetic-huge"))
            memory.remember(owner, "synthetic-large-a", rows("x".repeat(2_200_000)), now, null, false)
            memory.remember(owner, "synthetic-large-b", rows("x".repeat(2_200_000)), now, null, false)
            assertNull(peek(memory, "synthetic-large-a"))
            assertNotNull(peek(memory, "synthetic-large-b"))
            now += ConversationCacheV2.MAX_TTL_MILLIS + 1
            assertNull(peek(memory, "synthetic-large-b"))
        }
    }

    @Test fun mutationAndPreviewPublicationShareOneGate() = runBlocking {
        val vault = vault()
        val owner = requireNotNull(vault.captureConversationCacheReadOwner())
        ChatPreviewRepository(vault).use { memory ->
            memory.remember(owner, "synthetic-session", rows(), System.currentTimeMillis(), null, false)
            val publicationEntered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val publication = async(Dispatchers.IO) {
                memory.publishIfPresent("synthetic-session") {
                    publicationEntered.complete(Unit)
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            try {
                withTimeout(5000) { publicationEntered.await() }
                val mutationEntered = CompletableDeferred<Unit>()
                val mutation = async(Dispatchers.IO) {
                    mutationEntered.complete(Unit)
                    vault.saveSelectedContextProfile("synthetic-other")
                }
                withTimeout(5000) { mutationEntered.await() }
                release.countDown()
                assertTrue(withTimeout(5000) { publication.await() })
                withTimeout(5000) { mutation.await() }
                assertNull(peek(memory))
                assertFalse(memory.remember(owner, "synthetic-session", rows(), System.currentTimeMillis(), null, false))
            } finally { release.countDown() }
        }
    }

    @Test fun onlyStableHostMessagesAndToolNamesAreRetained() = runBlocking {
        val vault = vault()
        val owner = requireNotNull(vault.captureConversationCacheReadOwner())
        ChatPreviewRepository(vault).use { memory ->
            val supplied = listOf<TimelineEntry>(
                UserMessage("host", "Synthetic stored question", sourceKind = ActiveSourceKind.STORED),
                UserMessage("pending", "Synthetic pending", UserMessageState.SENDING),
                UserMessage("optimistic", "Synthetic local", sourceKind = ActiveSourceKind.LOCAL_USER),
                AssistantMessage("host-answer", "Synthetic answer"),
                AssistantMessage("stream", "Synthetic incomplete", AssistantMessageState.STREAMING),
                ToolActivity("tool", "synthetic-tool", "terminal", ToolStatus.COMPLETED,
                    argsSummary = "SYNTHETIC_PRIVATE_ARGS", resultSummary = "SYNTHETIC_PRIVATE_RESULT"),
                GenericGatewayActivity("generic", "unknown", "Synthetic", "SYNTHETIC_PRIVATE_PAYLOAD"))
            memory.remember(owner, "synthetic-session", supplied, System.currentTimeMillis(), null, false)
            val snapshot = requireNotNull(peek(memory))
            assertEquals(listOf("host", "host-answer", "tool"), snapshot.timeline.map { it.id })
            val tool = snapshot.timeline.last() as ToolActivity
            assertNull(tool.argsSummary); assertNull(tool.resultSummary); assertNull(tool.toolId)
            assertEquals("ChatPreviewSnapshot([redacted])", snapshot.toString())
            memory.close()
            assertFalse(memory.remember(owner, "synthetic-session", rows(), System.currentTimeMillis(), null, false))
            assertNull(peek(memory))
        }
    }
}
