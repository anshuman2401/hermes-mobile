package com.hermes.bot.feature.chat

import com.hermes.bot.data.commands.*
import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.*
import com.hermes.bot.data.queue.*
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatQueueViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()
    private class Commands : SlashCommandRepository {
        val seen = mutableListOf<SlashInvocation>()
        var gate: CompletableDeferred<Unit>? = null
        var response: CommandOutcome = CommandOutcome.Result(SlashDirective.Output("Synthetic result"))
        override suspend fun capture(storedId: String, runtimeId: String) = CommandSession(storedId, runtimeId)
        override suspend fun isCurrent(session: CommandSession) = true
        override suspend fun suggestions(session: CommandSession, query: String) = emptyList<SlashSuggestion>()
        override suspend fun execute(session: CommandSession, invocation: SlashInvocation): CommandOutcome {
            seen += invocation; gate?.await(); return response
        }
    }
    private fun vm(adapter: FakeGatewayProtocolAdapter, store: ChatQueueStore = MemoryChatQueueStore(), commands: Commands = Commands(),
        sessions: com.hermes.bot.data.session.SessionRepository = ChatComposerSingleFlightTest.SingleFlightSessionRepository(),
        decorateVault: (VaultStorage) -> VaultStorage = { it }): ChatViewModel {
        val baseVault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        val vault = decorateVault(baseVault)
        // Explicit synthetic writer seam; this fake never represents platform/auth security.
        val scopedSessions = object : com.hermes.bot.data.session.SessionRepository by sessions {
            override suspend fun capturePromptSubmissionAccess(storedId: String, runtimeId: String) =
                object : com.hermes.bot.data.turn.PromptSubmissionAccess {
                    override suspend fun isCurrent() = store.isCurrent()
                    override suspend fun submit(prompt: String) = adapter.submitPrompt(runtimeId, prompt)
                }
        }
        return ChatViewModel("sess-100", sessionRepository = scopedSessions,
            gatewayProtocolAdapter = adapter, pendingTurnLedger = PendingTurnLedger(vault), vaultStorage = vault,
            slashCommands = commands, chatQueueStore = store).also { it.setQueueVisibility(true) }
    }
    private fun idleEvidence(sessions: com.hermes.bot.data.session.SessionRepository =
        ChatComposerSingleFlightTest.SingleFlightSessionRepository()) =
        object : com.hermes.bot.data.session.SessionRepository by sessions {
            override suspend fun fetchHistorySnapshot(durableSessionId: String) =
                sessions.fetchHistorySnapshot(durableSessionId).map {
                    it.copy(activityEvidence = ResumeActivityEvidence.NON_RUNNING)
                }
        }

    @Test fun idlePromptUsesDirectSendAndLeavesQueueEmpty() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val chat = vm(adapter); advanceUntilIdle()
        chat.updateDraft("synthetic direct")
        assertFalse(chat.uiState.value.queuesComposerInput)
        chat.submitPrompt(); chat.submitPrompt(); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
        assertTrue(chat.uiState.value.isStreaming)
        assertNull(chat.uiState.value.queue.active)
        assertTrue(chat.uiState.value.queue.waiting.isEmpty())
        assertFalse(chat.uiState.value.queue.visible)
    }

    @Test fun directTerminalWaitsForLedgerSettlementBeforeQueuedWrite() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var first = true
        val adapter = FakeGatewayProtocolAdapter()
        val chat = vm(adapter, decorateVault = { vault -> object : VaultStorage by vault {
            override suspend fun updatePendingTurnState(pendingTurnId: String, newState: PendingTurnState) {
                if (newState == PendingTurnState.RESOLVED && first) {
                    first = false; entered.complete(Unit); release.await()
                }
                vault.updatePendingTurnState(pendingTurnId, newState)
            }
        } }); advanceUntilIdle()
        chat.updateDraft("synthetic direct"); chat.submitPrompt(); advanceUntilIdle()
        chat.updateDraft("synthetic queued"); chat.submitPrompt(); advanceUntilIdle()
        adapter.eventFlow.emit(GatewayEvent.MessageComplete("runtime-100", 1L, "synthetic done", "complete"))
        runCurrent(); entered.await()
        assertFalse(chat.uiState.value.isStreaming)
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
        release.complete(Unit); advanceUntilIdle()
        assertEquals(2, adapter.submitPromptCallCount)
        assertTrue(chat.uiState.value.queue.waiting.isEmpty())
    }

    @Test fun delayedQueueReadBlocksAttachmentActionsAndDirectSendUntilKnownEmpty() = runTest {
        val saved = MemoryChatQueueStore(); val read = CompletableDeferred<Unit>()
        val delayed = object : ChatQueueStore by saved {
            override suspend fun read(): ChatQueueState? { read.await(); return saved.read() }
        }
        val adapter = FakeGatewayProtocolAdapter(); val chat = vm(adapter, delayed); runCurrent()
        assertFalse(chat.uiState.value.isInitialLoading)
        assertFalse(chat.uiState.value.canAttachImage)
        assertFalse(chat.uiState.value.canAttachFile)
        val field = ChatViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val state = field.get(chat) as kotlinx.coroutines.flow.MutableStateFlow<ChatUiState>
        state.value = state.value.copy(pendingFiles = listOf(PendingFileAttachment("Synthetic file reference")))
        chat.updateDraft("synthetic attachment prompt")
        assertFalse(chat.uiState.value.isComposerReady)
        chat.submitPrompt(); runCurrent()
        assertEquals(0, adapter.submitPromptCallCount)
        assertEquals("synthetic attachment prompt", chat.uiState.value.composerDraft)
        read.complete(Unit); advanceUntilIdle()
        assertTrue(chat.uiState.value.canAttachFile)
        assertTrue(chat.uiState.value.isComposerReady)
        chat.submitPrompt(); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
        assertTrue(adapter.lastSubmittedText!!.contains("Synthetic file reference"))
    }

    @Test fun unavailableQueueBlocksAttachmentsAndDictationWithoutTreatingItAsEmpty() = runTest {
        val saved = MemoryChatQueueStore()
        val unavailable = object : ChatQueueStore by saved {
            override suspend fun read(): ChatQueueState? = error("Synthetic unavailable")
        }
        val adapter = FakeGatewayProtocolAdapter(); val chat = vm(adapter, unavailable); advanceUntilIdle()
        val state = chat.uiState.value.copy(dictationAvailable = true,
            pendingImages = listOf(PendingImageAttachment("synthetic-image", "Synthetic", "Synthetic image reference")))
        assertEquals(QueuePause.STORAGE, state.queue.pause)
        assertFalse(state.canAttachImage)
        assertFalse(state.canAttachFile)
        assertFalse(state.canStartDictation)
        assertFalse(state.isComposerReady)
        assertEquals(0, adapter.submitPromptCallCount)
    }

    @Test fun stopOrBackgroundDuringPreparationCannotStartAPromptWrite() = runTest {
        for (stop in listOf(false, true)) {
            val adapter = FakeGatewayProtocolAdapter()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val sessions = object : com.hermes.bot.data.session.SessionRepository by ChatComposerSingleFlightTest.SingleFlightSessionRepository() {
                override suspend fun captureSendActivityOwner(storedSessionId: String, runtimeSessionId: String): com.hermes.bot.data.session.SessionActivityOwner? {
                    entered.complete(Unit); release.await(); return null
                }
            }
            val store = MemoryChatQueueStore()
            store.write(ChatQueueState(waiting = listOf(QueueItem(payload = QueuePayload.Prompt("synthetic prepared")))))
            val chat = vm(adapter, store, sessions = idleEvidence(sessions)); advanceUntilIdle()
            chat.resumeQueue(); runCurrent()
            entered.await(); assertTrue(chat.uiState.value.isSubmitting)
            if (stop) chat.stopStreaming() else chat.setQueueVisibility(false)
            runCurrent(); release.complete(Unit); advanceUntilIdle()
            assertEquals(0, adapter.submitPromptCallCount)
            assertEquals("", chat.uiState.value.composerDraft)
            assertNotNull(chat.uiState.value.queue.active)
            assertNotNull(chat.uiState.value.queue.pause)
        }
    }

    @Test fun rapidSendAndImeIntentAcceptOneDraftOnly() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val chat = vm(adapter); advanceUntilIdle()
        chat.updateDraft("synthetic running"); chat.submitPrompt(); advanceUntilIdle()
        chat.pauseQueue(); advanceUntilIdle()
        chat.updateDraft("synthetic once"); chat.submitPrompt(); chat.submitPrompt(); chat.submitPrompt()
        advanceUntilIdle()
        assertEquals(1, chat.uiState.value.queue.waiting.size)
        assertEquals("synthetic once", (chat.uiState.value.queue.waiting.single().payload as QueuePayload.Prompt).text)
        assertEquals(1, adapter.submitPromptCallCount)
    }

    @Test fun terminalBeforeAdmissionWaitsForAcknowledgementThenReleasesNextPrompt() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val acknowledgement = CompletableDeferred<Unit>()
        adapter.onSubmitPrompt = { acknowledgement.await() }
        val chat = vm(adapter); advanceUntilIdle()
        chat.updateDraft("synthetic first"); chat.submitPrompt(); runCurrent()
        assertTrue(chat.uiState.value.isSubmitting)
        chat.updateDraft("synthetic second"); chat.submitPrompt(); runCurrent()
        adapter.eventFlow.emit(GatewayEvent.MessageComplete("synthetic-other-runtime", 1L, "synthetic foreign", "complete"))
        adapter.eventFlow.emit(GatewayEvent.MessageComplete("runtime-100", 2L, "synthetic terminal", "complete"))
        runCurrent()
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
        acknowledgement.complete(Unit); advanceUntilIdle()
        assertEquals(2, adapter.submitPromptCallCount)
        assertTrue(chat.uiState.value.timeline.any { it is AssistantMessage && it.text == "synthetic terminal" && it.state == AssistantMessageState.COMPLETE })
        assertTrue(chat.uiState.value.timeline.none { it is AssistantMessage && it.text == "synthetic foreign" })
        assertEquals(QueueItemState.STREAMING, chat.uiState.value.queue.active?.state)
    }

    @Test fun terminalBeforeUnknownAdmissionNeverReleasesLaterWork() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val acknowledgement = CompletableDeferred<Unit>()
        adapter.onSubmitPrompt = { acknowledgement.await() }
        adapter.submitPromptResult = GatewayResult.Failure(GatewayFailure.SubmissionUnknown("Synthetic unknown"))
        val chat = vm(adapter); advanceUntilIdle()
        chat.updateDraft("synthetic first"); chat.submitPrompt(); runCurrent()
        chat.updateDraft("synthetic second"); chat.submitPrompt(); runCurrent()
        adapter.eventFlow.emit(GatewayEvent.MessageComplete("runtime-100", 1L, "synthetic terminal", "complete"))
        runCurrent(); acknowledgement.complete(Unit); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals(QueuePause.UNKNOWN, chat.uiState.value.queue.pause)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
        assertNull(chat.uiState.value.queue.active)
        assertNotNull(chat.uiState.value.pendingUnknownTurnId)
    }

    @Test fun errorBeforeAdmissionHoldsLaterWorkAfterAcknowledgement() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val acknowledgement = CompletableDeferred<Unit>()
        adapter.onSubmitPrompt = { acknowledgement.await() }
        val chat = vm(adapter); advanceUntilIdle()
        chat.updateDraft("synthetic first"); chat.submitPrompt(); runCurrent()
        chat.updateDraft("synthetic second"); chat.submitPrompt(); runCurrent()
        adapter.eventFlow.emit(GatewayEvent.Error("runtime-100", 1L, "Synthetic error"))
        runCurrent(); acknowledgement.complete(Unit); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals(QueuePause.FAILED, chat.uiState.value.queue.pause)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
    }

    @Test fun sendDuringStreamQueuesWithoutExtraFrameThenRunsAfterTerminal() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val chat = vm(adapter); advanceUntilIdle()
        chat.updateDraft("synthetic first"); chat.submitPrompt(); advanceUntilIdle()
        assertTrue(chat.uiState.value.isStreaming)
        chat.updateDraft("synthetic second"); assertTrue(chat.uiState.value.isComposerReady)
        chat.submitPrompt(); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
        assertEquals("", chat.uiState.value.composerDraft)
        adapter.eventFlow.emit(GatewayEvent.MessageComplete("runtime-100", 3L, "synthetic final", "complete"))
        advanceUntilIdle()
        assertEquals(2, adapter.submitPromptCallCount)
        assertTrue(chat.uiState.value.queue.waiting.isEmpty())
        assertEquals(QueueItemState.STREAMING, chat.uiState.value.queue.active?.state)
    }
    @Test fun compressionToolbarQueuesDuringStreamWithoutInterruptingTurn() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val chat = vm(adapter); advanceUntilIdle()
        chat.updateDraft("synthetic first"); chat.submitPrompt(); advanceUntilIdle()
        assertTrue(chat.uiState.value.isStreaming)
        assertTrue(chat.uiState.value.canCompressContext)
        chat.compressContext(); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals("compress", (chat.uiState.value.queue.waiting.single().payload as QueuePayload.Command).invocation.name)
        repeat(9) { chat.compressContext(); advanceUntilIdle() }
        assertFalse(chat.uiState.value.canCompressContext)
        assertEquals(10, chat.uiState.value.queue.waiting.size)
    }
    @Test fun commandsHoldNextPromptUntilAcknowledgement() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val commands = Commands(); val gate = CompletableDeferred<Unit>(); commands.gate = gate
        val chat = vm(adapter, commands = commands); advanceUntilIdle()
        chat.updateDraft("/status"); chat.submitPrompt(); runCurrent()
        chat.updateDraft("synthetic prompt"); chat.submitPrompt(); runCurrent()
        assertEquals(0, adapter.submitPromptCallCount)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
        assertTrue(chat.uiState.value.slashBusy)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals(1, commands.seen.size)
    }
    @Test fun commandFailurePausesQueueAndKeepsLaterPromptUnsent() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val commands = Commands(); commands.response = CommandOutcome.Failure("Synthetic failure")
        val chat = vm(adapter, commands = commands); advanceUntilIdle()
        chat.updateDraft("/status"); chat.submitPrompt(); advanceUntilIdle()
        chat.updateDraft("synthetic prompt"); chat.submitPrompt(); advanceUntilIdle()
        assertEquals(0, adapter.submitPromptCallCount)
        assertEquals(QueuePause.FAILED, chat.uiState.value.queue.pause)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
    }
    @Test fun failedDurableEnqueuePreservesDraftAndMakesNoHostWrite() = runTest {
        val store = MemoryChatQueueStore(); val adapter = FakeGatewayProtocolAdapter(); val chat = vm(adapter, store); advanceUntilIdle()
        chat.updateDraft("synthetic running"); chat.submitPrompt(); advanceUntilIdle()
        store.failWrite = true
        chat.updateDraft("synthetic retained"); chat.submitPrompt(); advanceUntilIdle()
        assertEquals("synthetic retained", chat.uiState.value.composerDraft)
        assertEquals(1, adapter.submitPromptCallCount)
        assertNotNull(chat.uiState.value.queue.error)
    }
    @Test fun yoloAcknowledgementWithoutConfirmedStateDoesNotReleaseNextPrompt() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val commands = Commands(); commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        val chat = vm(adapter, commands = commands); advanceUntilIdle()
        chat.updateDraft("/yolo on"); chat.submitPrompt(); advanceUntilIdle()
        chat.updateDraft("synthetic prompt"); chat.submitPrompt(); advanceUntilIdle()
        assertEquals(0, adapter.submitPromptCallCount)
        assertEquals(QueuePause.CONFIRMATION, chat.uiState.value.queue.pause)
        assertEquals(QueueItemState.CONFIRMING, chat.uiState.value.queue.active?.state)
    }
    @Test fun yoloWaitsForFreshStateAndAcceptsPromptsDuringConfirmationRead() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val commands = Commands()
        val sessions = ChatViewModelTest.FakeChatSessionRepository()
        sessions.resumeOutcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0,
            info = mapOf("yolo" to false), activityEvidence = ResumeActivityEvidence.NON_RUNNING))
        commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        val write = CompletableDeferred<Unit>(); commands.gate = write
        val chat = vm(adapter, commands = commands, sessions = sessions); advanceUntilIdle()
        chat.updateDraft("/yolo on"); chat.submitPrompt(); runCurrent()
        assertEquals(false, chat.uiState.value.sessionYolo)
        chat.updateDraft("synthetic next"); chat.submitPrompt(); runCurrent()
        val read = CompletableDeferred<Unit>(); sessions.resumeGate = read
        sessions.resumeOutcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0,
            info = mapOf("yolo" to true), activityEvidence = ResumeActivityEvidence.NON_RUNNING))
        write.complete(Unit); runCurrent()
        assertTrue(chat.uiState.value.isRefreshing)
        assertEquals(0, adapter.submitPromptCallCount)
        chat.updateDraft("synthetic another"); assertTrue(chat.uiState.value.isComposerReady)
        chat.submitPrompt(); runCurrent()
        assertEquals(2, chat.uiState.value.queue.waiting.size)
        read.complete(Unit); advanceUntilIdle()
        assertEquals(true, chat.uiState.value.sessionYolo)
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
    }
    @Test fun bareYoloRefreshesUnknownModeBeforeOneToggleWrite() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val commands = Commands()
        val sessions = ChatViewModelTest.FakeChatSessionRepository()
        sessions.resumeOutcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0, activityEvidence = ResumeActivityEvidence.NON_RUNNING))
        val chat = vm(adapter, commands = commands, sessions = sessions); advanceUntilIdle()
        assertNull(chat.uiState.value.sessionYolo)
        val gate = CompletableDeferred<Unit>(); commands.gate = gate
        commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        sessions.resumeOutcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0,
            info = mapOf("yolo" to false), activityEvidence = ResumeActivityEvidence.NON_RUNNING))
        chat.toggleYolo(); runCurrent()
        assertEquals("on", commands.seen.single().argument)
        assertEquals(false, chat.uiState.value.sessionYolo)
        sessions.resumeOutcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0,
            info = mapOf("yolo" to true), activityEvidence = ResumeActivityEvidence.NON_RUNNING))
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(true, chat.uiState.value.sessionYolo)
        assertNull(chat.uiState.value.queue.active)
        assertEquals(1, commands.seen.size)
    }
    @Test fun unknownPromptCannotBeRetriedAroundTheQueue() = runTest {
        val adapter = FakeGatewayProtocolAdapter()
        adapter.submitPromptResult = GatewayResult.Failure(GatewayFailure.SubmissionUnknown("Synthetic unknown"))
        val store = MemoryChatQueueStore()
        store.write(ChatQueueState(waiting = listOf(QueueItem(payload = QueuePayload.Prompt("synthetic uncertain")))))
        val chat = vm(adapter, store, sessions = idleEvidence()); advanceUntilIdle()
        chat.resumeQueue(); advanceUntilIdle()
        assertEquals(QueueItemState.UNKNOWN, chat.uiState.value.queue.active?.state)
        chat.retryAmbiguousPrompt(); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
        chat.updateDraft("synthetic later"); chat.submitPrompt(); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
    }
    @Test fun stopRemainsImmediateAtCapacityAndDoesNotSendWaitingItems() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val chat = vm(adapter); advanceUntilIdle()
        chat.updateDraft("synthetic first"); chat.submitPrompt(); advanceUntilIdle()
        repeat(10) { chat.updateDraft("synthetic $it"); chat.submitPrompt(); advanceUntilIdle() }
        assertEquals(10, chat.uiState.value.queue.waiting.size)
        chat.updateDraft("/stop"); assertTrue(chat.uiState.value.isComposerReady)
        chat.submitPrompt(); advanceUntilIdle()
        assertFalse(chat.uiState.value.isStreaming)
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals(10, chat.uiState.value.queue.waiting.size)
        assertNotNull(chat.uiState.value.queue.pause)
    }
    @Test fun disconnectDuringStreamHoldsActiveOutcomeAndNeverResendsAfterReconnect() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val store = MemoryChatQueueStore()
        store.write(ChatQueueState(waiting = listOf(QueueItem(payload = QueuePayload.Prompt("synthetic first")))))
        val chat = vm(adapter, store, sessions = idleEvidence()); advanceUntilIdle(); chat.resumeQueue(); advanceUntilIdle()
        chat.updateDraft("synthetic later"); chat.submitPrompt(); advanceUntilIdle()
        adapter.connectionStatus.value = com.hermes.bot.domain.ConnectionStatus.OFFLINE; advanceUntilIdle()
        assertEquals(QueueItemState.UNKNOWN, chat.uiState.value.queue.active?.state)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
        adapter.connectionStatus.value = com.hermes.bot.domain.ConnectionStatus.CONNECTED
        chat.refreshSession(); advanceUntilIdle()
        chat.resumeQueue(); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals(QueueItemState.UNKNOWN, chat.uiState.value.queue.active?.state)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
    }

    @Test fun cancelledCommandOnDisconnectRetainsUnknownOutcomeAndLaterPrompt() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val commands = Commands()
        commands.gate = CompletableDeferred()
        val chat = vm(adapter, commands = commands); advanceUntilIdle()
        chat.updateDraft("/status"); chat.submitPrompt(); runCurrent()
        chat.updateDraft("synthetic later"); chat.submitPrompt(); runCurrent()
        adapter.connectionStatus.value = com.hermes.bot.domain.ConnectionStatus.OFFLINE; advanceUntilIdle()
        assertEquals(QueueItemState.UNKNOWN, chat.uiState.value.queue.active?.state)
        assertEquals(1, chat.uiState.value.queue.waiting.size)
        assertEquals(0, adapter.submitPromptCallCount)
        adapter.connectionStatus.value = com.hermes.bot.domain.ConnectionStatus.CONNECTED
        chat.resumeQueue(); advanceUntilIdle()
        assertEquals(1, commands.seen.size)
        assertEquals(0, adapter.submitPromptCallCount)
    }

    @Test fun backgroundPausesWaitingItemsAndRestoreDoesNotAutoSend() = runTest {
        val adapter = FakeGatewayProtocolAdapter(); val chat = vm(adapter); advanceUntilIdle()
        chat.updateDraft("synthetic running"); chat.submitPrompt(); advanceUntilIdle()
        chat.pauseQueue(); advanceUntilIdle()
        chat.updateDraft("synthetic pending"); chat.submitPrompt(); advanceUntilIdle()
        chat.setQueueVisibility(false); advanceUntilIdle()
        assertEquals(QueuePause.BACKGROUND, chat.uiState.value.queue.pause)
        chat.setQueueVisibility(true); advanceUntilIdle()
        assertEquals(1, adapter.submitPromptCallCount)
    }
}
