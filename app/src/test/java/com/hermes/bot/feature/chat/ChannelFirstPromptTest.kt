package com.hermes.bot.feature.chat

import androidx.lifecycle.ViewModelStore
import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.*
import com.hermes.bot.data.queue.*
import com.hermes.bot.data.session.*
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelFirstPromptTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()
    private class Recording(val delegate: FakeGatewayProtocolAdapter = FakeGatewayProtocolAdapter()) : GatewayProtocolAdapter by delegate {
        val writes = mutableListOf<Pair<String,String>>()
        override suspend fun submitPrompt(sessionId: String, text: String): GatewayResult<PromptSubmitResult> {
            writes += sessionId to text
            return delegate.submitPrompt(sessionId, text)
        }
    }
    private class Repo : SessionRepository by ChatComposerSingleFlightTest.SingleFlightSessionRepository() {
        var deferred: CompletableDeferred<Unit>? = CompletableDeferred()
        var throwOnResume = false
        var creates = 0
        override suspend fun createDraftSession(title: String?): Result<com.hermes.bot.domain.SessionHandle> {
            creates++; return Result.success(com.hermes.bot.domain.SessionHandle("synthetic-created", "synthetic-runtime"))
        }
        var outcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0, emptyList()))
        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> { deferred?.await(); if (throwOnResume) throw IllegalStateException("synthetic failure"); return outcome }
    }
    private fun vm(repo: Repo, adapter: Recording, storage: VaultStorage? = null, queueStore: ChatQueueStore? = null): ChatViewModel {
        val vault = storage ?: EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        // Explicit fake protocol writer for queue-enabled handoff tests only.
        val scopedRepo = object : SessionRepository by repo {
            override suspend fun capturePromptSubmissionAccess(storedId: String, runtimeId: String) =
                object : com.hermes.bot.data.turn.PromptSubmissionAccess {
                    override suspend fun isCurrent() = queueStore?.isCurrent() == true
                    override suspend fun submit(prompt: String) = adapter.submitPrompt(runtimeId, prompt)
                }
        }
        return ChatViewModel("sess-100", sessionRepository = scopedRepo, gatewayProtocolAdapter = adapter,
            vaultStorage = vault, pendingTurnLedger = PendingTurnLedger(vault), chatQueueStore = queueStore)
            .also { if (queueStore != null) it.setQueueVisibility(true) }
    }
    @Test fun idleChannelWaitsForDelayedQueueReadThenSendsDirectlyOnce() = runTest {
        val repo = Repo(); val adapter = Recording(); val saved = MemoryChatQueueStore()
        val read = CompletableDeferred<Unit>()
        val delayed = object : ChatQueueStore by saved {
            override suspend fun read(): ChatQueueState? { read.await(); return saved.read() }
        }
        val chat = vm(repo, adapter, queueStore = delayed)
        chat.acceptChannelFirstPrompt("synthetic-entry", "synthetic first")
        repo.deferred!!.complete(Unit); runCurrent()
        assertFalse(chat.uiState.value.queue.loaded); assertTrue(adapter.writes.isEmpty())
        read.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("runtime-100" to "synthetic first"), adapter.writes)
        assertEquals("", chat.uiState.value.composerDraft)
        assertFalse(chat.acceptChannelFirstPrompt("synthetic-repeat", "synthetic duplicate"))
        val owner = ViewModelStore().apply { put("chat", chat) }; owner.clear(); advanceUntilIdle()
        val reopened = vm(repo, adapter, queueStore = saved); advanceUntilIdle()
        assertNull(reopened.uiState.value.queue.active)
        assertEquals(1, adapter.writes.size)
    }

    @Test fun manualSendEditOrDisposalCancelsDelayedQueueHandoff() = runTest {
        for (action in listOf("send", "edit", "dispose")) {
            val repo = Repo(); val adapter = Recording(); val saved = MemoryChatQueueStore()
            val read = CompletableDeferred<Unit>()
            val delayed = object : ChatQueueStore by saved {
                override suspend fun read(): ChatQueueState? { read.await(); return saved.read() }
            }
            val chat = vm(repo, adapter, queueStore = delayed)
            chat.acceptChannelFirstPrompt("synthetic-entry", "synthetic first")
            repo.deferred!!.complete(Unit); runCurrent()
            when (action) {
                "send" -> chat.submitPrompt()
                "edit" -> chat.updateDraft("synthetic edited")
                else -> ViewModelStore().apply { put("chat", chat); clear() }
            }
            read.complete(Unit); advanceUntilIdle()
            assertTrue(adapter.writes.isEmpty())
            assertEquals(if (action == "edit") "synthetic edited" else "synthetic first", chat.uiState.value.composerDraft)
        }
    }

    @Test fun restoredQueueKeepsChannelHandoffBehindAcceptedIntentWithoutSending() = runTest {
        val repo = Repo(); val adapter = Recording(); val saved = MemoryChatQueueStore()
        val prior = ChatQueueCoordinator(saved); prior.restore()
        assertTrue(prior.enqueue(QueuePayload.Prompt("synthetic earlier")))
        val chat = vm(repo, adapter, queueStore = saved)
        chat.acceptChannelFirstPrompt("synthetic-entry", "synthetic first")
        repo.deferred!!.complete(Unit); advanceUntilIdle()
        assertTrue(adapter.writes.isEmpty())
        assertEquals(QueuePause.RESTORED, chat.uiState.value.queue.pause)
        assertEquals(listOf("synthetic earlier", "synthetic first"),
            chat.uiState.value.queue.waiting.map { (it.payload as QueuePayload.Prompt).text })
    }

    @Test fun unavailableQueueStorageRetainsChannelDraftWithoutSending() = runTest {
        val repo = Repo(); val adapter = Recording(); val saved = MemoryChatQueueStore()
        val unavailable = object : ChatQueueStore by saved {
            override suspend fun read(): ChatQueueState? = error("Synthetic unavailable")
        }
        val chat = vm(repo, adapter, queueStore = unavailable)
        chat.acceptChannelFirstPrompt("synthetic-entry", "synthetic first")
        repo.deferred!!.complete(Unit); advanceUntilIdle()
        assertTrue(adapter.writes.isEmpty())
        assertEquals("synthetic first", chat.uiState.value.composerDraft)
        assertEquals(QueuePause.STORAGE, chat.uiState.value.queue.pause)
    }

    @Test fun waitsForInitialRemoteLoadThenStreamsAndRepliesOnSameSession() = runTest {
        val repo = Repo(); val adapter = Recording(); val chat = vm(repo, adapter)
        assertTrue(chat.acceptChannelFirstPrompt("entry-one", "synthetic first"))
        assertFalse(chat.acceptChannelFirstPrompt("entry-one", "must not overwrite"))
        runCurrent(); assertTrue(adapter.writes.isEmpty()); assertEquals("synthetic first", chat.uiState.value.composerDraft)
        repo.deferred!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("runtime-100" to "synthetic first"), adapter.writes)
        adapter.delegate.eventFlow.emit(GatewayEvent.MessageStart("runtime-100", 1L))
        adapter.delegate.eventFlow.emit(GatewayEvent.MessageDelta("runtime-100", 2L, "synthetic response"))
        advanceUntilIdle()
        assertTrue(chat.uiState.value.timeline.any { it is AssistantMessage && it.text.contains("synthetic response") })
        adapter.delegate.eventFlow.emit(GatewayEvent.MessageComplete("runtime-100", 3L, "synthetic response", "complete"))
        advanceUntilIdle(); chat.updateDraft("synthetic reply"); chat.submitPrompt(); advanceUntilIdle()
        assertEquals(2, adapter.writes.size); assertEquals("runtime-100", adapter.writes.last().first)
    }
    @Test fun alreadyCompletedInitialReadAcceptsHandoffOnce() = runTest {
        val repo = Repo(); repo.deferred!!.complete(Unit); val adapter = Recording()
        val chat = vm(repo, adapter); advanceUntilIdle()
        assertTrue(chat.acceptChannelFirstPrompt("entry", "synthetic first"))
        advanceUntilIdle()
        assertEquals(listOf("runtime-100" to "synthetic first"), adapter.writes)
        assertFalse(chat.acceptChannelFirstPrompt("another-entry", "synthetic duplicate"))
        advanceUntilIdle(); assertEquals(1, adapter.writes.size)
    }
    @Test fun editingOrManualSendWhileInitialLoadPendingCancelsAutomaticIntent() = runTest {
        for (manual in listOf(false,true)) {
            val repo = Repo(); val adapter = Recording(); val chat = vm(repo,adapter)
            chat.acceptChannelFirstPrompt("entry", "synthetic first"); runCurrent()
            if (manual) chat.submitPrompt() else chat.updateDraft("synthetic edited")
            repo.deferred!!.complete(Unit); advanceUntilIdle()
            assertTrue(adapter.writes.isEmpty())
            assertEquals(if (manual) "synthetic first" else "synthetic edited", chat.uiState.value.composerDraft)
        }
    }
    @Test fun failedOrOfflineInitialAttemptNeverArmsOnLaterLoad() = runTest {
        for (offline in listOf(false,true)) {
            val repo = Repo(); val adapter = Recording()
            if (offline) adapter.delegate.connectionStatus.value = ConnectionStatus.OFFLINE
            else repo.outcome = Result.failure(IllegalStateException("synthetic failure"))
            val chat = vm(repo, adapter); chat.acceptChannelFirstPrompt("entry", "synthetic first")
            repo.deferred!!.complete(Unit); advanceUntilIdle(); assertTrue(adapter.writes.isEmpty())
            adapter.delegate.connectionStatus.value = ConnectionStatus.CONNECTED
            repo.outcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0, emptyList()))
            chat.loadSession(); advanceUntilIdle()
            assertTrue(adapter.writes.isEmpty()); assertEquals("synthetic first",chat.uiState.value.composerDraft)
        }
    }
    @Test fun disposalCancelsWaitingAndLateDraftRestoreCannotOverwriteHandoff() = runTest {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        val draftGate = CompletableDeferred<Unit>()
        val delayed = object : VaultStorage by vault {
            override suspend fun getDraft(storedSessionId: String): Draft? { draftGate.await(); return Draft(storedSessionId,"synthetic stored") }
        }
        val repo = Repo(); val adapter = Recording(); val chat = vm(repo, adapter, delayed)
        val store = ViewModelStore(); store.put("chat",chat)
        chat.acceptChannelFirstPrompt("entry", "synthetic first"); runCurrent()
        draftGate.complete(Unit); runCurrent(); assertEquals("synthetic first",chat.uiState.value.composerDraft)
        store.clear(); repo.deferred!!.complete(Unit); advanceUntilIdle(); assertTrue(adapter.writes.isEmpty())
    }
    @Test fun unknownSubmitAndReopeningDoNotAutomaticallyWriteAgain() = runTest {
        val repo = Repo(); val adapter = Recording()
        adapter.delegate.submitPromptResult = GatewayResult.Failure(GatewayFailure.SubmissionUnknown("synthetic unknown"))
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        val chat = vm(repo, adapter, vault); chat.acceptChannelFirstPrompt("entry", "synthetic first")
        repo.deferred!!.complete(Unit); advanceUntilIdle(); assertEquals(1,adapter.writes.size)
        chat.loadSession(); advanceUntilIdle(); assertEquals(1,adapter.writes.size)
        val reopened = vm(repo, adapter, vault); advanceUntilIdle(); assertEquals(1,adapter.writes.size)
        assertEquals("",reopened.uiState.value.composerDraft)
    }
    @Test fun thrownOrWrongIdentityInitialAttemptTerminatesAutomaticIntent() = runTest {
        for (throws in listOf(false,true)) {
            val repo = Repo(); val adapter = Recording()
            repo.throwOnResume = throws
            repo.outcome = Result.success(SessionResumeResult("synthetic-other-runtime", "synthetic-other-session", 0))
            val chat = vm(repo,adapter); chat.acceptChannelFirstPrompt("entry", "synthetic first")
            repo.deferred!!.complete(Unit); advanceUntilIdle(); assertTrue(adapter.writes.isEmpty())
            repo.throwOnResume = false; repo.outcome = Result.success(SessionResumeResult("runtime-100", "sess-100",0))
            chat.loadSession(); advanceUntilIdle(); assertTrue(adapter.writes.isEmpty())
            assertEquals("synthetic first",chat.uiState.value.composerDraft)
        }
    }
    @Test fun busyWhenInitialLoadCompletesDoesNotWaitAndRetry() = runTest {
        val repo = Repo(); val adapter = Recording(); val chat = vm(repo,adapter)
        chat.acceptChannelFirstPrompt("entry","synthetic first"); chat.refreshSession()
        repo.deferred!!.complete(Unit); advanceUntilIdle()
        assertTrue(adapter.writes.isEmpty()); assertEquals("synthetic first",chat.uiState.value.composerDraft)
        assertTrue(chat.uiState.value.isComposerReady)
    }
    @Test fun scopedInitializationWaitsForReplaySeedBeforeSingleWrite() = runBlocking(main.testDispatcher) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try {
            f.initialize(); f.withLocalTurns = true; f.holdSeed = true
            val written = CompletableDeferred<String>()
            f.transport.setRpcHandler("prompt.submit") { id, params ->
                written.complete(params["session_id"]!!.asStringOrNull()!!)
                f.transport.respondSuccess(id, "{}")
            }
            val chat = f.vm()
            assertTrue(chat.acceptChannelFirstPrompt("entry", "Synthetic first"))
            val seedId = f.await(f.seedEntered)
            assertFalse(written.isCompleted)
            f.reply(seedId, f.seedBody())
            assertEquals(f.runtimeId, f.await(written))
            f.loaded(chat)
            assertEquals(1, f.transport.sentFrames.count { it.contains("\"method\":\"prompt.submit\"") })
            assertFalse(chat.acceptChannelFirstPrompt("repeat", "Synthetic duplicate"))
        } finally { f.close() }
    }

    @Test fun failedScopedInitializationDoesNotSendAfterExplicitReload() = runBlocking(main.testDispatcher) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try {
            f.initialize(); f.withLocalTurns = true; f.resumeError = true
            val chat = f.vm(); chat.acceptChannelFirstPrompt("entry", "Synthetic first")
            f.joined(chat)
            withContext(Dispatchers.Default) { withTimeout(5000) { chat.uiState.first { !it.isInitialLoading } } }
            assertEquals(0, f.transport.sentFrames.count { it.contains("\"method\":\"prompt.submit\"") })
            f.resumeError = false; chat.loadSession(); f.loaded(chat)
            assertEquals(0, f.transport.sentFrames.count { it.contains("\"method\":\"prompt.submit\"") })
            assertEquals("Synthetic first", chat.uiState.value.composerDraft)
        } finally { f.close() }
    }

    @Test fun certifiedEmptyDraftFromCreateSendsOnceWithMissingOrEmptyStoredHistory() = runBlocking(main.testDispatcher) {
        verifyCertifiedEmptyDraftSubmission("""{"status":"streaming"}""", SubmissionStatus.STREAMING)
    }

    @Test fun certifiedEmptyDraftWithUnconfirmedAdmissionStaysUnknownWithoutResending() = runBlocking(main.testDispatcher) {
        verifyCertifiedEmptyDraftSubmission("{}", SubmissionStatus.UNKNOWN)
    }

    private suspend fun verifyCertifiedEmptyDraftSubmission(acknowledgement: String, expectedStatus: SubmissionStatus) {
        for (stored in listOf("empty", "missing-first", "missing-last")) {
            val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
            try {
                f.initialize(); f.withLocalTurns = true
                f.resumeJson = com.hermes.bot.data.gateway.fake.FakeGatewayFixtures.SESSION_RESUME_UNPERSISTED
                    .replace("TEST-RUNTIME-SID-1", f.runtimeId)
                    .replace("TEST-STORED-SESSION-KEY-1", f.resolvedId)
                f.storedJson = """{"session_id":"synthetic-session","profile":"default","messages":[],"pagination":{"limit":120,"offset":0,"order":"latest","returned":0}}"""
                if (stored != "empty") f.statusCode = 404
                if (stored == "missing-first") f.holdResume = true
                if (stored == "missing-last") f.headerGate = CompletableDeferred()
                f.transport.setRpcHandler("session.create") { id, params ->
                    assertEquals("default", params["profile"]?.asStringOrNull())
                    f.transport.respondSuccess(id, """{"session_id":"synthetic-runtime","stored_session_id":"synthetic-session","message_count":0,"messages":[]} """)
                }
                val created = f.repository.createDraftSession().getOrThrow()
                assertEquals(f.resolvedId, created.storedSessionId)
                val written = CompletableDeferred<Unit>()
                f.transport.setRpcHandler("prompt.submit") { id, params ->
                    assertEquals(f.runtimeId, params["session_id"]?.asStringOrNull())
                    written.complete(Unit)
                    f.transport.respondSuccess(id, acknowledgement)
                }
                val chat = f.vm()
                assertTrue(chat.acceptChannelFirstPrompt("synthetic-entry", "Synthetic first"))
                if (stored == "missing-first") {
                    f.await(f.decodedStored)
                    assertFalse(written.isCompleted)
                    f.reply(f.await(f.resumeEntered), f.resumeBody())
                }
                if (stored == "missing-last") {
                    f.await(f.seedEntered)
                    assertFalse(written.isCompleted)
                    f.headerGate!!.complete(Unit)
                }
                f.await(written); f.loaded(chat)
                // A write observation and history-load completion do not mean the
                // acknowledgement has been processed or the ledger update has finished.
                val submitted = withContext(Dispatchers.Default) {
                    withTimeout(5000) {
                        chat.uiState.first { !it.isSubmitting && it.submissionStatus != SubmissionStatus.IDLE }
                    }
                }
                assertEquals(expectedStatus, submitted.submissionStatus)
                assertNull(submitted.submitBlockedReason)
                if (expectedStatus == SubmissionStatus.STREAMING) {
                    assertNull(submitted.errorMessage)
                    assertTrue(submitted.isStreaming)
                    assertNull(submitted.pendingUnknownTurnId)
                } else {
                    assertNotNull(submitted.errorMessage)
                    assertFalse(submitted.isStreaming)
                    assertNotNull(submitted.pendingUnknownTurnId)
                }
                assertFalse(chat.acceptChannelFirstPrompt("synthetic-repeat", "Synthetic duplicate"))
                assertEquals(1, f.transport.sentFrames.count { it.contains("\"method\":\"prompt.submit\"") })
                assertEquals(1, f.transport.sentFrames.count { it.contains("\"method\":\"session.create\"") })
            } finally { f.close() }
        }
    }

    @Test fun existingThreadReplyAndAnotherThreadUseNoCreate() = runTest {
        val repo = Repo(); repo.deferred!!.complete(Unit); val adapter = Recording()
        val sessions = com.hermes.bot.feature.sessions.SessionsViewModel(repo)
        var opened: String? = null
        sessions.selectAndResumeSession("sess-100") { opened = it }; advanceUntilIdle(); assertEquals("sess-100",opened)
        val chat = vm(repo,adapter); advanceUntilIdle(); chat.updateDraft("synthetic reply"); chat.submitPrompt(); advanceUntilIdle()
        assertEquals(1,adapter.writes.size); assertEquals(0,repo.creates)
        val store = ViewModelStore(); store.put("chat",chat); store.clear()
        repo.outcome = Result.success(SessionResumeResult("synthetic-runtime-two","synthetic-session-two",0))
        sessions.selectAndResumeSession("synthetic-session-two") { opened = it }; advanceUntilIdle()
        assertEquals("synthetic-session-two",opened); assertEquals(0,repo.creates)
    }

}
