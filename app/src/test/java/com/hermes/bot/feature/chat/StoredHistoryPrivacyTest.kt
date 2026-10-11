package com.hermes.bot.feature.chat

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import com.hermes.bot.data.cache.ConversationCacheRepository
import com.hermes.bot.data.cache.ConversationCacheWriteResult
import com.hermes.bot.data.cache.ConversationCacheReadResult
import com.hermes.bot.data.cache.VaultConversationCacheRepository
import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.local.*
import com.hermes.bot.data.session.DefaultSessionRepository
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class StoredHistoryPrivacyTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun typedActiveEphemeralFieldsAreClearedBeforeRealVmSerializerEncryptedCacheTraceAndDiagnostics() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val markers = listOf("SYNTHETIC_RAW_USER", "SYNTHETIC_RAW_ASSISTANT", "SYNTHETIC_RAW_ERROR",
                "SYNTHETIC_RAW_DISPLAY", "SYNTHETIC_RAW_CORRECTION", "SYNTHETIC_RAW_QUEUE")
            f.metadata = """"running":false,"status":"idle","turn_started_at":1.2345,
                "inflight":{"user":"${markers[0]}","assistant":"${markers[1]}","error":"${markers[2]}",
                "display_metadata":{"title_preview":"${markers[3]}"},"corrections":["${markers[4]}"]},
                "queued":{"user":"${markers[5]}"}"""
            f.transport.setRpcHandler("session.resume") { id, _ ->
                f.transport.respondSuccess(id, f.resumeBody().replace("\"Synthetic compact answer\"",
                    "\"Synthetic compact answer\",\"timestamp\":1.2345,\"reasoning\":\"Synthetic allowed reasoning\""))
            }
            val parsed = (f.gateway.resumeSession("synthetic-session") as GatewayResult.Success).value
            assertEquals(markers[1], parsed.activeProjection!!.inflight.value!!.assistant.value)
            assertEquals(1.2345, parsed.messages.last().sourceTimestampSeconds!!, 0.0)
            markers.forEach { assertFalse(parsed.toString().contains(it)) }
            val serializedInput = CompletableDeferred<SessionResumeResult>()
            val observed = object : ConversationCacheRepository by f.cache {
                override suspend fun replaceRemoteSnapshotScoped(storedSessionId: String, resume: SessionResumeResult,
                    nowMillis: Long, expected: AuthCredentialLease, selectedContext: String?): ConversationCacheWriteResult {
                    serializedInput.complete(resume)
                    return f.cache.replaceRemoteSnapshotScoped(storedSessionId, resume, nowMillis, expected, selectedContext)
                }
            }
            val vm = ChatViewModel("synthetic-session", sessionRepository = f.repository,
                gatewayProtocolAdapter = f.gateway, vaultStorage = f.vault, conversationCacheRepository = observed)
            val store = ViewModelStore().apply { put("synthetic", vm) }
            val jobs = vm.viewModelScope.coroutineContext[Job]!!
            try {
                f.awaitLoaded(vm)
                val input = withContext(Dispatchers.Default) { withTimeout(5000) { serializedInput.await() } }
                assertNull(input.activeProjection); assertNull(input.inflight)
                assertNull(input.observedRuntimeSessionId); assertNull(input.observedDurableSessionId)
                assertTrue(input.messages.all { it.sourceTimestampSeconds == null })
                assertTrue(f.repository.activeMessages.value.all { it.sourceTimestampSeconds == null })
                val snapshot = withContext(Dispatchers.Default) {
                    withTimeout(5000) {
                        var read = f.cache.load("synthetic-session")
                        while (read !is ConversationCacheReadResult.Snapshot) { delay(5); read = f.cache.load("synthetic-session") }
                        read.value
                    }
                }
                val partition = (f.vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
                val encryptedRead = (f.vault.readConversationSnapshot(partition, "synthetic-session") as CacheReadResult.Available).value
                val sinks = f.serializedConversation + snapshot.rows.toString() + snapshot.localTrace +
                    encryptedRead.snapshot.rows + encryptedRead.localTrace + f.diagnostics.snapshot() + f.diagnostics.snapshotReport()
                (markers + listOf("SYNTHETIC_PRIVATE_OUTPUT", "SYNTHETIC_PRIVATE_ARGUMENT",
                    "SYNTHETIC_PRIVATE_COMPACT_OUTPUT", "SYNTHETIC_PRIVATE_COMPACT_ARGUMENT")).forEach {
                    assertFalse("Raw synthetic payload reached a real persistence/trace/diagnostic sink", sinks.contains(it))
                }
                assertTrue(f.serializedConversation.contains("Synthetic compact answer"))
                assertTrue(f.serializedConversation.contains("Synthetic allowed reasoning"))
                assertTrue(snapshot.localTrace.isEmpty())
            } finally {
                store.clear()
                withContext(Dispatchers.Default) { withTimeout(5000) { jobs.join() } }
            }
        }
    }

    @Test fun parallelActiveOpenNeverPersistsRawSentinels() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize(); f.cookie = true
            val markers = listOf("SYNTHETIC_RAW_USER", "SYNTHETIC_RAW_ASSISTANT", "SYNTHETIC_RAW_ERROR",
                "SYNTHETIC_RAW_DISPLAY", "SYNTHETIC_RAW_CORRECTION", "SYNTHETIC_RAW_QUEUE")
            f.metadata = """"running":true,"status":"running","turn_started_at":1.2345,
                "inflight":{"user":"${markers[0]}","assistant":"${markers[1]}","error":"${markers[2]}","streaming":true,
                "display_metadata":{"title_preview":"${markers[3]}"},"corrections":["${markers[4]}"]},
                "queued":{"user":"${markers[5]}"}"""
            f.transport.setRpcHandler("session.resume") { id, _ ->
                f.transport.respondSuccess(id, f.resumeBody().replace("\"Synthetic compact answer\"",
                    "\"Synthetic compact answer\",\"timestamp\":1.2345,\"reasoning\":\"Synthetic allowed reasoning\""))
            }
            val parsed = (f.gateway.resumeSession("synthetic-session") as GatewayResult.Success).value
            assertEquals(markers[1], parsed.activeProjection!!.inflight.value!!.assistant.value)
            assertEquals(1.2345, parsed.messages.last().sourceTimestampSeconds!!, 0.0)
            markers.forEach { assertFalse(parsed.toString().contains(it)) }
            val serializedInput = CompletableDeferred<SessionResumeResult>()
            val observed = object : ConversationCacheRepository by f.cache {
                override suspend fun replaceRemoteSnapshotScoped(storedSessionId: String, resume: SessionResumeResult,
                    nowMillis: Long, expected: AuthCredentialLease, selectedContext: String?): ConversationCacheWriteResult {
                    serializedInput.complete(resume)
                    return f.cache.replaceRemoteSnapshotScoped(storedSessionId, resume, nowMillis, expected, selectedContext)
                }
            }
            val vm = ChatViewModel("synthetic-session", sessionRepository = f.repository,
                gatewayProtocolAdapter = f.gateway, vaultStorage = f.vault, conversationCacheRepository = observed)
            val store = ViewModelStore().apply { put("synthetic", vm) }
            val jobs = vm.viewModelScope.coroutineContext[Job]!!
            try {
                f.awaitLoaded(vm)
                val input = withContext(Dispatchers.Default) { withTimeout(5000) { serializedInput.await() } }
                assertNull(input.activeProjection); assertNull(input.inflight)
                assertNull(input.observedRuntimeSessionId); assertNull(input.observedDurableSessionId)
                assertTrue(input.messages.all { it.sourceTimestampSeconds == null })
                assertTrue(f.repository.activeMessages.value.all { it.sourceTimestampSeconds == null })
                val snapshot = withContext(Dispatchers.Default) {
                    withTimeout(5000) {
                        var read = f.cache.load("synthetic-session")
                        while (read !is ConversationCacheReadResult.Snapshot) { delay(5); read = f.cache.load("synthetic-session") }
                        read.value
                    }
                }
                val partition = (f.vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
                val encryptedRead = (f.vault.readConversationSnapshot(partition, "synthetic-session") as CacheReadResult.Available).value
                val sinks = f.serializedConversation + snapshot.rows.toString() + snapshot.localTrace +
                    encryptedRead.snapshot.rows + encryptedRead.localTrace + f.diagnostics.snapshot() + f.diagnostics.snapshotReport()
                (markers + listOf("SYNTHETIC_PRIVATE_OUTPUT", "SYNTHETIC_PRIVATE_ARGUMENT",
                    "SYNTHETIC_PRIVATE_COMPACT_OUTPUT", "SYNTHETIC_PRIVATE_COMPACT_ARGUMENT")).forEach {
                    assertFalse("Raw synthetic payload reached a real persistence/trace/diagnostic sink", sinks.contains(it))
                }
                assertTrue(f.serializedConversation.contains("Synthetic compact answer"))
                assertTrue(f.serializedConversation.contains("Synthetic allowed reasoning"))
                assertTrue(snapshot.localTrace.isEmpty())
            } finally {
                store.clear()
                withContext(Dispatchers.Default) { withTimeout(5000) { jobs.join() } }
            }
        }
    }

    @Test fun scopedResumeCannotPersistOldTextUnderNewRemotePartition() = runBlocking(main.testDispatcher) {
        verifyCacheMutation(false)
    }

    @Test fun workspaceResetABADuringPartitionAwaitTerminatesOldWriteWithoutResurrection() = runBlocking(main.testDispatcher) {
        verifyCacheMutation(true)
    }

    private suspend fun verifyCacheMutation(remoteABA: Boolean) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gateVault = object : VaultStorage by f.vault {
                override suspend fun activeAccessPartition(): CacheAccessPartitionResult {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    currentCoroutineContext().ensureActive()
                    return f.vault.activeAccessPartition()
                }
            }
            val realCache = VaultConversationCacheRepository(gateVault)
            val writeFinished = CompletableDeferred<ConversationCacheWriteResult>()
            val observedCache = object : ConversationCacheRepository by realCache {
                // This regression gates partition acquisition for the write; previews read independently.
                override suspend fun loadScoped(storedSessionId: String, expected: AuthCredentialLease,
                    selectedContext: String?): ConversationCacheReadResult =
                    f.cache.loadScoped(storedSessionId, expected, selectedContext)
                override suspend fun replaceRemoteSnapshotScoped(storedSessionId: String, resume: com.hermes.bot.data.gateway.SessionResumeResult, nowMillis: Long, expected: AuthCredentialLease, selectedContext: String?): ConversationCacheWriteResult {
                    try {
                        val result = realCache.replaceRemoteSnapshotScoped(storedSessionId, resume, nowMillis, expected, selectedContext)
                        writeFinished.complete(result)
                        return result
                    } catch (cancelled: CancellationException) {
                        writeFinished.complete(ConversationCacheWriteResult.Skipped)
                        throw cancelled
                    }
                }
            }
            val vm = ChatViewModel("synthetic-session", sessionRepository = f.repository,
                gatewayProtocolAdapter = f.gateway, vaultStorage = f.vault, conversationCacheRepository = observedCache)
            val jobs = vm.viewModelScope.coroutineContext[Job]!!
            val store = ViewModelStore().apply { put("synthetic", vm) }
            val owner = com.hermes.bot.navigation.ChatRouteOwnerViewModel { vm }
            if (remoteABA) owner.get("synthetic-session")
            try {
                entered.await() // after real scoped resume + stored GET + current() admission
                assertEquals(1, f.gets)
                assertTrue(vm.uiState.value.timeline.any { it is ToolActivity && it.storedSource != null })
                val before = f.vault.readAuthCredentialLease(f.origin.originUrl)!!
                if (remoteABA) {
                    val context = com.hermes.bot.data.context.DefaultContextProfileRepository(f.vault)
                    com.hermes.bot.navigation.performWorkspaceSelection("synthetic-other", context, owner) {}
                    com.hermes.bot.navigation.performWorkspaceSelection("default", context, owner) {}
                    val after = f.vault.readAuthCredentialLease(f.origin.originUrl)!!
                    assertEquals(before.epoch, after.epoch)
                    assertEquals(before.credential, after.credential)
                } else f.vault.saveSelectedContextProfile("synthetic-other")
                release.complete(Unit)
                // Same synchronous test dispatcher runs the real encrypt/write path after release.
                main.testDispatcher.scheduler.advanceUntilIdle()
                val writeResult = writeFinished.await()

                if (remoteABA) {
                    assertTrue("reset must cancel the scope owning the cache await", jobs.isCancelled)
                    withTimeout(5000) { jobs.join() }
                    assertTrue(owner.activeKeys().isEmpty())
                }
                val readback = f.cache.load("synthetic-session")

                assertFalse("old scoped transcript was encrypted under the newly selected remote partition; rows=${(readback as? ConversationCacheReadResult.Snapshot)?.value?.rows?.size}", readback is ConversationCacheReadResult.Snapshot)
                assertNotEquals(com.hermes.bot.data.cache.ConversationCacheWriteResult.Written, writeResult)
                if (remoteABA) {
                    f.vault.saveSelectedContextProfile("synthetic-other")
                    assertFalse(f.cache.load("synthetic-session") is ConversationCacheReadResult.Snapshot)
                    f.vault.saveSelectedContextProfile("default")
                    assertFalse(f.cache.load("synthetic-session") is ConversationCacheReadResult.Snapshot)
                }
            } finally {
                release.complete(Unit)
                owner.activeKeys().forEach(owner::clear)
                store.clear()
                jobs.join()
            }
        }
    }

    @Test fun alreadyAdmittedEncryptedACommitFinishesBeforeBMutationWithoutRelabeling() = runBlocking(main.testDispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val folder = temp.newFolder()
        val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            cacheStageObserver = { stage ->
                if (stage == CacheWriteStage.STAGE_CONVERSATION_RECORD_WRITTEN) {
                    entered.complete(Unit)
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            })
        val origin = "http://example.test:9711"
        vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin, "Synthetic"))
        vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-cookie"), origin))
        vault.saveSelectedContextProfile("A")
        val expected = vault.readAuthCredentialLease(origin)!!
        val cache = VaultConversationCacheRepository(vault)
        val owner = com.hermes.bot.navigation.ChatRouteOwnerViewModel { ChatViewModel(it) }
        val vm = owner.get("synthetic-session")!!
        val scopeJob = vm.viewModelScope.coroutineContext[Job]!!
        val terminal = CompletableDeferred<Unit>()
        val write = vm.viewModelScope.launch {
            try {
                cache.replaceRemoteSnapshotScoped("synthetic-session", SessionResumeResult("synthetic-runtime", "synthetic-session", 1,
                    listOf(SessionMessage("assistant", "Synthetic allowed A text"))), System.currentTimeMillis(), expected, "A")
            } finally { terminal.complete(Unit) }
        }
        var switch: Deferred<com.hermes.bot.data.context.ContextProfileSelectionResult>? = null
        try {
            withContext(Dispatchers.Default) { withTimeout(5000) { entered.await() } }
            // The synchronous write is already admitted under the real vault mutex.
            com.hermes.bot.navigation.resetWorkspaceOwnership(owner) {}
            assertTrue(scopeJob.isCancelled)
            switch = async(Dispatchers.Default) {
                com.hermes.bot.navigation.performWorkspaceSelection("B", com.hermes.bot.data.context.DefaultContextProfileRepository(vault), owner) {}
            }
            release.countDown()
            withContext(Dispatchers.Default) { withTimeout(5000) { terminal.await(); write.join(); scopeJob.join(); switch!!.await() } }
            assertEquals("B", vault.getSelectedContextProfile())
            assertFalse(cache.load("synthetic-session") is ConversationCacheReadResult.Snapshot)
            vault.saveSelectedContextProfile("A")
            val read = cache.load("synthetic-session") as ConversationCacheReadResult.Snapshot
            assertEquals(listOf("Synthetic allowed A text"), read.value.rows.map { it.content })
        } finally {
            release.countDown(); owner.activeKeys().forEach(owner::clear)
            withContext(Dispatchers.Default) { withTimeout(5000) { write.join(); scopeJob.join(); switch?.join() } }
        }
    }

    @Test fun acceptedOwnCookieSuccessorStillPersistsAllowedTextOffline() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val before = f.vault.readAuthCredentialLease(f.origin.originUrl)!!
            f.cookie = true
            val vm = f.vm()
            f.awaitLoaded(vm)
            val snapshot = withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    var read = f.cache.load("synthetic-session")
                    while (read !is ConversationCacheReadResult.Snapshot) {
                        delay(5)
                        read = f.cache.load("synthetic-session")
                    }
                    read.value
                }
            }
            val after = f.vault.readAuthCredentialLease(f.origin.originUrl)!!
            assertNotEquals(before.epoch, after.epoch)
            assertEquals(3, snapshot.rows.size)
            assertTrue(snapshot.rows.any { it.content == "Synthetic compact answer" })
            assertFalse(f.serializedConversation.contains("SYNTHETIC_PRIVATE_COMPACT_OUTPUT"))
            f.gateway.disconnect()
            vm.loadSession()
            f.awaitLoaded(vm)
            assertNotNull(vm.uiState.value.savedSnapshotCapturedAtMillis)
            assertEquals(1, f.gets)
        }
    }

    @Test fun storedGetAndCompactResumeStayAbsentFromActualEncryptedRecordTraceAndDiagnostics() = kotlinx.coroutines.runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val vm = f.vm()
            f.awaitLoaded(vm)
            // Wait for the post-presentation cache write, using its actual encrypted read seam.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5000) {
                    while (f.cache.load("synthetic-session") !is ConversationCacheReadResult.Snapshot)
                        kotlinx.coroutines.delay(5)
                }
            }
            val partition = (f.vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
            val record = (f.vault.readConversationSnapshot(partition, "synthetic-session") as CacheReadResult.Available).value
            assertEquals(3, record.snapshot.rows.size)
            val serialized = f.serializedConversation
            assertTrue(serialized.isNotEmpty())
            val readback = (f.cache.load("synthetic-session") as ConversationCacheReadResult.Snapshot).value
            val sinks = serialized + readback.localTrace.toString() + f.diagnostics.snapshot().toString() +
                f.diagnostics.snapshotReport()
            for (sentinel in listOf("SYNTHETIC_PRIVATE_OUTPUT", "SYNTHETIC_PRIVATE_ARGUMENT",
                "SYNTHETIC_PRIVATE_COMPACT_OUTPUT", "SYNTHETIC_PRIVATE_COMPACT_ARGUMENT"))
                assertFalse("synthetic tool payload reached persistence/diagnostics", sinks.contains(sentinel))
            assertTrue(serialized.contains("Synthetic compact question"))
            assertTrue(serialized.contains("Synthetic compact answer"))
            assertTrue(vm.uiState.value.timeline.filterIsInstance<ToolActivity>().any {
                it.resultSummary?.contains("SYNTHETIC_PRIVATE_OUTPUT") == true
            })
            assertTrue(readback.localTrace.isEmpty())
            f.gateway.disconnect()
            vm.loadSession()
            f.awaitLoaded(vm)
            assertNotNull(vm.uiState.value.savedSnapshotCapturedAtMillis)
            assertEquals(1, f.gets)
            assertTrue(vm.uiState.value.timeline.none { it is ToolActivity && it.storedSource != null })
        }
    }

    @Test fun compactToolPayloadNeverReachesEncryptedSnapshotReadback() = runTest {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = main.testDispatcher)
        val origin = "http://example.test:9711"
        vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin, "Synthetic"))
        vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-cookie"), origin))
        val cache = VaultConversationCacheRepository(vault)
        val gateway = FakeGatewayProtocolAdapter().apply {
            resumeSessionResult = GatewayResult.Success(SessionResumeResult("synthetic-runtime", "synthetic-session", 3,
                listOf(SessionMessage("user", "Synthetic allowed question"),
                    SessionMessage("tool", "SYNTHETIC_PRIVATE_OUTPUT", toolName = "terminal",
                        toolContext = "SYNTHETIC_PRIVATE_ARGUMENT"),
                    SessionMessage("assistant", "Synthetic allowed answer"))))
        }
        val vm = ChatViewModel("synthetic-session", sessionRepository = DefaultSessionRepository(gateway, vault),
            gatewayProtocolAdapter = gateway, vaultStorage = vault, conversationCacheRepository = cache)
        vm.loadSession()
        advanceUntilIdle()
        val snapshot = (cache.load("synthetic-session") as ConversationCacheReadResult.Snapshot).value
        val serializedReadback = snapshot.rows.joinToString { "${it.content}|${it.toolContext}|${it.reasoning}" }
        assertFalse("raw tool output reached actual encrypted snapshot", serializedReadback.contains("SYNTHETIC_PRIVATE_OUTPUT"))
        assertFalse("raw tool arguments reached actual encrypted snapshot", serializedReadback.contains("SYNTHETIC_PRIVATE_ARGUMENT"))
        assertTrue(serializedReadback.contains("Synthetic allowed question"))
        assertTrue(serializedReadback.contains("Synthetic allowed answer"))
    }
}
