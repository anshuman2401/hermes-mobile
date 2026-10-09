package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.commands.*
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSlashCommandsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()
    private class Commands : SlashCommandRepository {
        var current = true
        val queries = mutableListOf<String>()
        var calls = 0
        val invocations = mutableListOf<SlashInvocation>()
        var before: suspend () -> Unit = {}
        var response: CommandOutcome = CommandOutcome.Result(SlashDirective.Output("Synthetic command result"))
        override suspend fun capture(storedId: String, runtimeId: String) = CommandSession(storedId, runtimeId)
        override suspend fun isCurrent(session: CommandSession) = current
        override suspend fun suggestions(session: CommandSession, query: String): List<SlashSuggestion> {
            queries.add(query); before()
            return listOf(SlashSuggestion(if (query == "/") "/status" else query, "Synthetic description"))
        }
        override suspend fun execute(session: CommandSession, invocation: SlashInvocation): CommandOutcome {
            calls++; invocations.add(invocation); before(); return response
        }
    }
    private fun vm(commands: Commands, adapter: GatewayProtocolAdapter = FakeGatewayProtocolAdapter(),
        sessions: io.github.anshuman2401.hermesmobile.data.session.SessionRepository = ChatComposerSingleFlightTest.SingleFlightSessionRepository()): ChatViewModel {
        val vault = EncryptedVault(temporary.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = main.testDispatcher)
        return ChatViewModel("sess-100",
            sessionRepository = sessions,
            gatewayProtocolAdapter = adapter, pendingTurnLedger = PendingTurnLedger(vault),
            vaultStorage = vault, slashCommands = commands)
    }
    @Test fun leadingWhitespaceAndInlineSlashesNeverRequestSuggestions() = runTest {
        val commands = Commands(); val vm = vm(commands); advanceUntilIdle()
        for (text in listOf(" /status", "\n/status", "hello /status", "/usr/local")) {
            vm.updateDraft(text); advanceUntilIdle()
            assertFalse(vm.uiState.value.slashMenuVisible)
        }
        assertTrue(commands.queries.isEmpty())
        vm.updateDraft("/"); advanceUntilIdle()
        assertEquals(listOf("/"), commands.queries)
        assertEquals("/status", vm.uiState.value.slashSuggestions.single().text)
        vm.dismissSlashSuggestions()
        assertFalse(vm.uiState.value.slashMenuVisible)
    }
    @Test fun staleCompletionCannotOverwriteNewQuery() = runTest {
        val commands = Commands(); val gate = CompletableDeferred<Unit>()
        commands.before = { withContext(NonCancellable) { gate.await() } }
        val vm = vm(commands); advanceUntilIdle()
        vm.updateDraft("/s"); advanceTimeBy(81); runCurrent()
        commands.before = {}
        vm.updateDraft("/c"); advanceTimeBy(81); runCurrent()
        assertEquals("/c", vm.uiState.value.slashSuggestions.single().text)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("/c", vm.uiState.value.slashSuggestions.single().text)
    }
    @Test fun outputIsSeparateFromRemoteHistoryAndDoesNotSubmitPrompt() = runTest {
        val commands = Commands(); val adapter = FakeGatewayProtocolAdapter()
        val vm = vm(commands, adapter); advanceUntilIdle(); val history = vm.uiState.value.timeline
        vm.updateDraft("/status"); vm.submitPrompt(); vm.submitPrompt(); advanceUntilIdle()
        assertEquals(1, commands.calls)
        assertEquals(0, adapter.submitPromptCallCount)
        assertEquals(history, vm.uiState.value.timeline)
        assertEquals("", vm.uiState.value.composerDraft)
        assertEquals("Synthetic command result", vm.uiState.value.slashResults.single().text)
    }
    @Test fun failedCommandKeepsDraftAndDoesNotRetry() = runTest {
        val commands = Commands(); commands.response = CommandOutcome.Failure("Command outcome unknown.")
        val vm = vm(commands); advanceUntilIdle()
        vm.updateDraft("/review details"); vm.submitPrompt(); advanceUntilIdle()
        assertEquals("/review details", vm.uiState.value.composerDraft)
        assertEquals(1, commands.calls)
        assertFalse(vm.uiState.value.slashBusy)
    }
    @Test fun prefillDoesNotOverwriteTextTypedDuringRequest() = runTest {
        val commands = Commands(); val gate = CompletableDeferred<Unit>()
        val vm = vm(commands); advanceUntilIdle()
        commands.response = CommandOutcome.Result(SlashDirective.Prefill("Synthetic replacement", null))
        commands.before = { gate.await() }
        vm.updateDraft("/undo"); vm.submitPrompt(); runCurrent()
        vm.updateDraft("New text"); gate.complete(Unit); advanceUntilIdle()
        assertEquals("New text", vm.uiState.value.composerDraft)
    }
    @Test fun skillDirectiveUsesExistingSingleWriteCoordinatorAndDisplaysInvocation() = runTest {
        val commands = Commands(); val adapter = FakeGatewayProtocolAdapter()
        commands.response = CommandOutcome.Result(SlashDirective.Send("Synthetic model-facing instructions", "/review details", null))
        val vm = vm(commands, adapter); advanceUntilIdle()
        vm.updateDraft("/review details"); vm.submitPrompt(); vm.submitPrompt(); advanceUntilIdle()
        assertEquals(1, commands.calls)
        assertEquals(1, adapter.submitPromptCallCount)
        assertEquals("/review details", vm.uiState.value.timeline.filterIsInstance<UserMessage>().last().text)
        assertTrue(vm.uiState.value.isStreaming)
    }
    @Test fun lateResultWithLostOwnerIsDiscarded() = runTest {
        val commands = Commands(); val gate = CompletableDeferred<Unit>()
        val vm = vm(commands); advanceUntilIdle()
        commands.before = { gate.await() }
        vm.updateDraft("/status"); vm.submitPrompt(); runCurrent()
        commands.current = false; gate.complete(Unit); advanceUntilIdle()
        assertTrue(vm.uiState.value.slashResults.isEmpty())
        assertEquals("/status", vm.uiState.value.composerDraft)
    }
    @Test fun normalSendAndAttachmentsCannotInterleaveWithCommandRequest() = runTest {
        val commands = Commands(); val gate = CompletableDeferred<Unit>(); val adapter = FakeGatewayProtocolAdapter()
        val vm = vm(commands, adapter); advanceUntilIdle()
        commands.before = { gate.await() }
        vm.updateDraft("/status"); vm.submitPrompt(); runCurrent()
        vm.updateDraft("New unsent prompt")
        assertFalse(vm.uiState.value.isComposerReady)
        assertFalse(vm.uiState.value.canAttachImage)
        vm.submitPrompt()
        assertEquals(0, adapter.submitPromptCallCount)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("New unsent prompt", vm.uiState.value.composerDraft)
    }
    @Test fun disconnectDismissesMenuAndCancelsCommandWithoutResend() = runTest {
        val commands = Commands(); val gate = CompletableDeferred<Unit>(); val adapter = FakeGatewayProtocolAdapter()
        val vm = vm(commands, adapter); advanceUntilIdle()
        assertEquals(io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.CONNECTED, vm.uiState.value.headerConnectionStatus)
        vm.updateDraft("/"); advanceUntilIdle()
        assertTrue(vm.uiState.value.slashMenuVisible)
        commands.before = { gate.await() }
        vm.updateDraft("/status"); vm.submitPrompt(); runCurrent()
        adapter.connectionStatus.value = io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.OFFLINE
        runCurrent()
        assertEquals(io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.OFFLINE, vm.uiState.value.headerConnectionStatus)
        assertFalse(vm.uiState.value.slashMenuVisible)
        assertFalse(vm.uiState.value.slashBusy)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(vm.uiState.value.slashResults.isEmpty())
        assertEquals(1, commands.calls)
        assertEquals(0, adapter.submitPromptCallCount)
    }
    @Test fun bareSlashCannotBecomeAnAccidentalPrompt() = runTest {
        val commands = Commands(); val adapter = FakeGatewayProtocolAdapter()
        val vm = vm(commands, adapter); advanceUntilIdle()
        vm.updateDraft("/"); vm.submitPrompt(); advanceUntilIdle()
        assertEquals(0, adapter.submitPromptCallCount)
        assertEquals("/", vm.uiState.value.composerDraft)
    }
    @Test fun toolbarYoloWorksWithEmptyDraftAndPreservesDraftDuringSingleFlight() = runTest {
        val commands = Commands(); val adapter = FakeGatewayProtocolAdapter()
        val sessions = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        val initial = sessions.resumeOutcome.getOrThrow()
        sessions.resumeOutcome = Result.success(initial.copy(info = mapOf("yolo" to false)))
        val vm = vm(commands, adapter, sessions); advanceUntilIdle()
        commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        sessions.resumeOutcome = Result.success(initial.copy(info = mapOf("yolo" to true)))
        assertTrue(vm.uiState.value.canToggleYolo)
        val firstGate = CompletableDeferred<Unit>()
        commands.before = { firstGate.await() }
        vm.toggleYolo(); runCurrent(); vm.toggleYolo()
        firstGate.complete(Unit); advanceUntilIdle()
        commands.before = {}
        assertEquals(1, commands.calls)
        assertEquals("on", commands.invocations.single().argument)
        assertEquals("", vm.uiState.value.composerDraft)
        vm.updateDraft("Synthetic draft")
        vm.toggleYolo(); advanceUntilIdle()
        assertEquals("Synthetic draft", vm.uiState.value.composerDraft)
        assertEquals("off", commands.invocations.last().argument)
        val gate = CompletableDeferred<Unit>()
        commands.before = { gate.await() }
        vm.toggleYolo(); runCurrent()
        assertFalse(vm.uiState.value.canToggleYolo)
        vm.toggleYolo(); vm.updateDraft("Synthetic edited draft")
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(3, commands.calls)
        assertEquals("Synthetic edited draft", vm.uiState.value.composerDraft)
        assertEquals(0, adapter.submitPromptCallCount)
    }
    @Test fun toolbarYoloUnknownModeDoesNotWrite() = runTest {
        val commands = Commands(); val vm = vm(commands); advanceUntilIdle()
        assertFalse(vm.uiState.value.canToggleYolo)
        vm.toggleYolo(); advanceUntilIdle()
        assertEquals(0, commands.calls)
    }
    @Test fun toolbarYoloReadinessRejectsBusyAndDisconnectedStates() {
        val ready = ChatUiState(runtimeSessionId = "runtime-synthetic", sessionYolo = false,
            isInitialLoading = false)
        assertTrue(ready.canToggleYolo)
        for (blocked in listOf(
            ready.copy(sessionYolo = null), ready.copy(runtimeSessionId = null),
            ready.copy(isInitialLoading = true), ready.copy(isRefreshing = true),
            ready.copy(isSubmitting = true), ready.copy(isStreaming = true),
            ready.copy(slashBusy = true), ready.copy(isAttachingImage = true),
            ready.copy(isAttachingFile = true), ready.copy(isAttachmentConnectionAvailable = false),
            ready.copy(submitBlockedReason = SubmitBlockedReason.NO_RUNTIME),
            ready.copy(savedSnapshotCapturedAtMillis = 1L)
        )) assertFalse(blocked.canToggleYolo)
    }
    @Test fun yoloTogglesConfirmedEffectiveModeWithoutPromptAndReconcilesHostPolicy() = runTest {
        val commands = Commands(); val adapter = FakeGatewayProtocolAdapter()
        val sessions = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        val initial = sessions.resumeOutcome.getOrThrow()
        sessions.resumeOutcome = Result.success(initial.copy(info = mapOf("yolo" to false)))
        val vm = vm(commands, adapter, sessions); advanceUntilIdle()
        assertEquals(false, vm.uiState.value.sessionYolo)
        commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        sessions.resumeOutcome = Result.success(initial.copy(info = mapOf("yolo" to true)))
        vm.updateDraft("/yolo"); vm.submitPrompt(); vm.submitPrompt(); advanceUntilIdle()
        assertEquals(1, commands.calls)
        assertEquals("on", commands.invocations.single().argument)
        assertEquals(true, vm.uiState.value.sessionYolo)
        assertEquals("", vm.uiState.value.composerDraft)
        assertEquals(0, adapter.submitPromptCallCount)
        // Host-wide policy still bypasses approvals even after this session flag is disabled.
        vm.updateDraft("/yolo"); vm.submitPrompt(); advanceUntilIdle()
        assertEquals("off", commands.invocations.last().argument)
        assertEquals(true, vm.uiState.value.sessionYolo)
        assertTrue(vm.uiState.value.slashResults.last().text.contains("Host policy"))
    }
    @Test fun unknownYoloModeReadsWithoutWritingAndExplicitOffCanRecover() = runTest {
        val commands = Commands(); val sessions = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        val vm = vm(commands, sessions = sessions); advanceUntilIdle()
        vm.updateDraft("/yolo"); vm.submitPrompt(); advanceUntilIdle()
        assertEquals(0, commands.calls)
        assertNull(vm.uiState.value.sessionYolo)
        assertEquals("/yolo", vm.uiState.value.composerDraft)
        commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        sessions.resumeOutcome = Result.success(sessions.resumeOutcome.getOrThrow().copy(info = mapOf("yolo" to false)))
        vm.updateDraft("/yolo off"); vm.submitPrompt(); advanceUntilIdle()
        assertEquals(1, commands.calls)
        assertEquals("off", commands.invocations.single().argument)
        assertEquals(false, vm.uiState.value.sessionYolo)
    }
    @Test fun uncertainYoloWriteNeverRetriesAndFailedReadKeepsModeUnknown() = runTest {
        val commands = Commands(); val sessions = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        sessions.resumeOutcome = Result.success(sessions.resumeOutcome.getOrThrow().copy(info = mapOf("yolo" to true)))
        val vm = vm(commands, sessions = sessions); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); commands.before = { gate.await() }
        commands.response = CommandOutcome.Failure("Command outcome unknown.", outcomeUnknown = true)
        sessions.resumeOutcome = Result.failure(IllegalStateException("Synthetic read failure"))
        vm.updateDraft("/yolo"); vm.submitPrompt(); runCurrent()
        assertEquals(false, vm.uiState.value.sessionYolo)
        assertEquals(true, vm.uiState.value.confirmedSessionYolo)
        assertTrue(vm.uiState.value.yoloUnconfirmed)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, commands.calls)
        assertNull(vm.uiState.value.sessionYolo)
        assertEquals("/yolo", vm.uiState.value.composerDraft)
        assertNotNull(vm.uiState.value.refreshErrorMessage)
    }
    @Test fun yoloInfoCannotCrossRuntimeAndDisconnectClearsMode() = runTest {
        val commands = Commands(); val adapter = FakeGatewayProtocolAdapter()
        val vm = vm(commands, adapter); advanceUntilIdle()
        for (runtime in listOf(null, "", "sess-100", "old-runtime", "other-runtime")) {
            adapter.eventFlow.emit(io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent.SessionInfo(runtime, null, mapOf("yolo" to true)))
            runCurrent(); assertNull(vm.uiState.value.sessionYolo)
        }
        adapter.eventFlow.emit(io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent.SessionInfo("runtime-100", null, mapOf("yolo" to true)))
        runCurrent(); assertEquals(true, vm.uiState.value.sessionYolo)
        adapter.connectionStatus.value = io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.OFFLINE
        runCurrent(); assertNull(vm.uiState.value.sessionYolo)
        adapter.eventFlow.emit(io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent.SessionInfo("runtime-100", null, mapOf("yolo" to true)))
        runCurrent(); assertNull(vm.uiState.value.sessionYolo)
        adapter.connectionStatus.value = io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.CONNECTED
        runCurrent(); assertNull(vm.uiState.value.sessionYolo)
        adapter.eventFlow.emit(io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent.SessionInfo("runtime-100", null, mapOf("yolo" to "true")))
        runCurrent(); assertNull(vm.uiState.value.sessionYolo)
        adapter.eventFlow.emit(io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent.SessionInfo("runtime-100", null, mapOf("yolo" to false)))
        runCurrent(); assertEquals(false, vm.uiState.value.sessionYolo)
    }
    @Test fun invalidYoloArgumentsDoNotWrite() = runTest {
        val commands = Commands(); val vm = vm(commands); advanceUntilIdle()
        for (argument in listOf("global", "toggle", "on global")) {
            vm.updateDraft("/yolo $argument"); vm.submitPrompt(); advanceUntilIdle()
        }
        assertEquals(0, commands.calls)
    }

    @Test fun allYoloInputsDisplayImmediatelyAndStayDisabledUntilEffectiveRefresh() = runTest {
        for (input in listOf("toolbar", "/yolo", "/yolo on", "/yolo off")) {
            val commands = Commands()
            val base = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
            val initial = input == "/yolo off"
            base.resumeOutcome = Result.success(base.resumeOutcome.getOrThrow().copy(info = mapOf("yolo" to initial)))
            val write = CompletableDeferred<Unit>(); val read = CompletableDeferred<Unit>()
            var holdRead = false
            val sessions = object : io.github.anshuman2401.hermesmobile.data.session.SessionRepository by base {
                override suspend fun resumeSession(storedSessionId: String): Result<io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult> {
                    if (holdRead) read.await()
                    return base.resumeOutcome
                }
            }
            val adapter = FakeGatewayProtocolAdapter()
            val vm = vm(commands, adapter, sessions); advanceUntilIdle()
            commands.before = { write.await() }
            commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
            vm.updateDraft(if (input == "toolbar") "Synthetic draft" else input)
            if (input == "toolbar") vm.toggleYolo() else vm.submitPrompt()
            assertEquals(!initial, vm.uiState.value.sessionYolo) // before coroutine/host response
            assertEquals(initial, vm.uiState.value.confirmedSessionYolo)
            assertTrue(vm.uiState.value.yoloPending)
            assertFalse(vm.uiState.value.canToggleYolo)
            runCurrent()
            adapter.eventFlow.emit(io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent.SessionInfo("runtime-100", null, mapOf("yolo" to initial)))
            runCurrent(); assertEquals(!initial, vm.uiState.value.sessionYolo)
            holdRead = true; write.complete(Unit); runCurrent()
            assertTrue(vm.uiState.value.isRefreshing)
            assertEquals(!initial, vm.uiState.value.sessionYolo)
            assertEquals(initial, vm.uiState.value.confirmedSessionYolo)
            vm.toggleYolo(); vm.submitPrompt(); runCurrent()
            assertEquals(1, commands.calls)
            // Effective host policy is authoritative even when different from requested state.
            read.complete(Unit); advanceUntilIdle()
            assertEquals(initial, vm.uiState.value.sessionYolo)
            assertFalse(vm.uiState.value.yoloPending)
            assertFalse(vm.uiState.value.yoloUnconfirmed)
            assertEquals(if (input == "toolbar") "Synthetic draft" else "", vm.uiState.value.composerDraft)
        }
    }

    @Test fun definiteYoloRejectionRollsBackWithoutRefreshAndKeepsSlashDraft() = runTest {
        val commands = Commands(); val base = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        base.resumeOutcome = Result.success(base.resumeOutcome.getOrThrow().copy(info = mapOf("yolo" to false)))
        val vm = vm(commands, sessions = base); advanceUntilIdle()
        base.resumeOutcome = Result.failure(IllegalStateException("Must not refresh rejection"))
        commands.response = CommandOutcome.Failure("Hermes rejected the change.")
        val gate = CompletableDeferred<Unit>(); commands.before = { gate.await() }
        vm.updateDraft("/yolo on"); vm.submitPrompt()
        assertEquals(true, vm.uiState.value.sessionYolo)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(false, vm.uiState.value.sessionYolo)
        assertEquals(false, vm.uiState.value.confirmedSessionYolo)
        assertFalse(vm.uiState.value.yoloPending)
        assertEquals("/yolo on", vm.uiState.value.composerDraft)
        assertNull(vm.uiState.value.refreshErrorMessage)
        assertEquals("Hermes rejected the change.", vm.uiState.value.slashResults.last().text)
    }

    @Test fun successfulWriteWithFailedRefreshIsUnconfirmed() = runTest {
        val commands = Commands(); val base = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        base.resumeOutcome = Result.success(base.resumeOutcome.getOrThrow().copy(info = mapOf("yolo" to false)))
        val vm = vm(commands, sessions = base); advanceUntilIdle()
        commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        base.resumeOutcome = Result.failure(IllegalStateException("Synthetic failure"))
        vm.toggleYolo(); advanceUntilIdle()
        assertNull(vm.uiState.value.sessionYolo)
        assertEquals(false, vm.uiState.value.confirmedSessionYolo)
        assertTrue(vm.uiState.value.yoloUnconfirmed)
        assertFalse(vm.uiState.value.canToggleYolo)
        assertEquals(1, commands.calls)
    }

    @Test fun disconnectedLateWriteCannotOverwriteReconnectedStateOrResend() = runTest {
        val commands = Commands(); val base = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        base.resumeOutcome = Result.success(base.resumeOutcome.getOrThrow().copy(info = mapOf("yolo" to false)))
        val adapter = FakeGatewayProtocolAdapter(); val vm = vm(commands, adapter, base); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        commands.before = { withContext(NonCancellable) { gate.await() } }
        commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        vm.toggleYolo(); runCurrent()
        adapter.connectionStatus.value = io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.OFFLINE; runCurrent()
        assertNull(vm.uiState.value.sessionYolo)
        assertTrue(vm.uiState.value.yoloUnconfirmed)
        adapter.connectionStatus.value = io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.CONNECTED; runCurrent()
        assertEquals(false, vm.uiState.value.sessionYolo)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(false, vm.uiState.value.sessionYolo)
        assertEquals(1, commands.calls)
    }

    @Test fun lateYoloRefreshAndInfoCannotCrossWorkspaceOwner() = runTest {
        val commands = Commands(); val base = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        base.resumeOutcome = Result.success(base.resumeOutcome.getOrThrow().copy(info = mapOf("yolo" to false)))
        val gate = CompletableDeferred<Unit>(); var hold = false
        val sessions = object : io.github.anshuman2401.hermesmobile.data.session.SessionRepository by base {
            override suspend fun resumeSession(storedSessionId: String): Result<io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult> {
                if (hold) gate.await()
                return base.resumeOutcome
            }
        }
        val adapter = FakeGatewayProtocolAdapter(); val vm = vm(commands, adapter, sessions); advanceUntilIdle()
        commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        hold = true; vm.toggleYolo(); runCurrent()
        commands.current = false
        base.resumeOutcome = Result.success(base.resumeOutcome.getOrThrow().copy(info = mapOf("yolo" to true)))
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(false, vm.uiState.value.confirmedSessionYolo)
        assertTrue(vm.uiState.value.yoloUnconfirmed)
        adapter.eventFlow.emit(io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent.SessionInfo("runtime-100", null, mapOf("yolo" to true)))
        runCurrent(); assertEquals(false, vm.uiState.value.confirmedSessionYolo)
    }

    @Test fun lateWriteAfterRuntimeReplacementCannotOverwriteNewerOperation() = runTest {
        val commands = Commands(); val base = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        base.resumeOutcome = Result.success(base.resumeOutcome.getOrThrow().copy(info = mapOf("yolo" to false)))
        val vm = vm(commands, sessions = base); advanceUntilIdle()
        val old = CompletableDeferred<Unit>()
        commands.before = { withContext(NonCancellable) { old.await() } }
        commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        vm.toggleYolo(); runCurrent()
        base.resumeOutcome = Result.success(base.resumeOutcome.getOrThrow().copy(runtimeSessionId = "runtime-new", info = mapOf("yolo" to true)))
        vm.loadSession(); runCurrent()
        val newer = CompletableDeferred<Unit>(); commands.before = { newer.await() }
        vm.toggleYolo(); runCurrent()
        assertEquals(false, vm.uiState.value.sessionYolo)
        old.complete(Unit); runCurrent()
        assertEquals(false, vm.uiState.value.sessionYolo)
        assertTrue(vm.uiState.value.yoloPending)
        assertEquals(true, vm.uiState.value.confirmedSessionYolo)
        newer.complete(Unit); advanceUntilIdle()
        assertEquals(true, vm.uiState.value.sessionYolo)
        assertEquals(2, commands.calls)
    }

    @Test fun scopedParallelRefreshKeepsOptimisticModeUntilLiveResumeAndSeedFinish() = runBlocking(main.testDispatcher) {
        val f = io.github.anshuman2401.hermesmobile.data.session.S4bBaselineFixture(temporary.newFolder(), main.testDispatcher)
        try {
            f.initialize()
            f.resumeJson = f.resumeBody().dropLast(1) + ",\"info\":{\"yolo\":false}}"
            val commands = Commands()
            val vm = f.vm(slashCommands = commands); f.loaded(vm)
            assertEquals(false, vm.uiState.value.sessionYolo)
            val resume = CompletableDeferred<String>(); val seed = CompletableDeferred<String>()
            f.transport.setRpcHandler("session.resume") { id, _ -> resume.complete(id) }
            f.transport.setRpcHandler("session.events.since") { id, _ -> seed.complete(id) }
            commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
            vm.updateDraft("Synthetic retained draft"); vm.toggleYolo()
            val id = f.await(resume)
            assertEquals(true, vm.uiState.value.sessionYolo)
            assertEquals(false, vm.uiState.value.confirmedSessionYolo)
            assertTrue(vm.uiState.value.yoloPending)
            f.reply(id, f.resumeBody())
            val seedId = f.await(seed)
            assertEquals(false, vm.uiState.value.sessionYolo)
            assertTrue(vm.uiState.value.yoloPending)
            assertFalse(vm.uiState.value.canToggleYolo)
            f.reply(seedId, f.seedBody()); f.joined(vm)
            withContext(Dispatchers.Default) { withTimeout(5000) {
                vm.uiState.first { !it.yoloPending }
            } }
            assertFalse(vm.uiState.value.yoloUnconfirmed)
            assertEquals("Synthetic retained draft", vm.uiState.value.composerDraft)
            assertEquals(1, commands.calls)
        } finally { f.releaseAll(); f.close() }
    }

    @Test fun disconnectDuringRefreshAllowsNewReadAndDiscardsOldRefresh() = runTest {
        val commands = Commands(); val base = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        val initial = base.resumeOutcome.getOrThrow().copy(info = mapOf("yolo" to false))
        base.resumeOutcome = Result.success(initial)
        val oldRead = CompletableDeferred<Unit>(); var holdNext = false
        val sessions = object : io.github.anshuman2401.hermesmobile.data.session.SessionRepository by base {
            override suspend fun resumeSession(storedSessionId: String): Result<io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult> {
                if (holdNext) {
                    holdNext = false
                    withContext(NonCancellable) { oldRead.await() }
                    return Result.success(initial.copy(info = mapOf("yolo" to true)))
                }
                return base.resumeOutcome
            }
        }
        val adapter = FakeGatewayProtocolAdapter(); val vm = vm(commands, adapter, sessions); advanceUntilIdle()
        commands.response = CommandOutcome.Result(SlashDirective.YoloChanged())
        holdNext = true; vm.toggleYolo(); runCurrent()
        assertTrue(vm.uiState.value.isRefreshing)
        adapter.connectionStatus.value = io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.OFFLINE; runCurrent()
        assertFalse(vm.uiState.value.isRefreshing)
        assertNull(vm.uiState.value.sessionYolo)
        adapter.connectionStatus.value = io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.CONNECTED; runCurrent()
        assertEquals(false, vm.uiState.value.sessionYolo)
        assertFalse(vm.uiState.value.yoloUnconfirmed)
        oldRead.complete(Unit); advanceUntilIdle()
        assertEquals(false, vm.uiState.value.sessionYolo)
        assertEquals(1, commands.calls)
    }

}
