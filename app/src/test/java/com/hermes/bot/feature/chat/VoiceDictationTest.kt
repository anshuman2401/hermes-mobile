package com.hermes.bot.feature.chat

import com.hermes.bot.data.voice.*
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDictationTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()
    private class Coordinator(val operation: VoiceOperation) : VoiceDictationCoordinator {
        var starts = 0; var stops = 0; var cancels = 0; var current = true
        var lastStoredId: String? = null
        var startGate: CompletableDeferred<Unit>? = null
        var recordingFault: VoiceFailure? = null
        override fun recordingFailure(operation: VoiceOperation) = recordingFault
        var result = CompletableDeferred<VoiceResult>()
        override suspend fun start(storedId: String, runtimeId: String): VoiceOperation {
            starts++; lastStoredId = storedId; startGate?.await(); return operation
        }
        override suspend fun isCurrent(operation: VoiceOperation) = current
        override suspend fun stopAndTranscribe(operation: VoiceOperation): VoiceResult { stops++; return result.await() }
        override fun cancel(operation: VoiceOperation) { cancels++ }
    }
    private suspend fun coordinator(): Coordinator {
        val f = VoiceTestFixture(temp.newFolder(), dispatcher = main.testDispatcher); f.initialize()
        val activity = f.sessions.captureSendActivityOwner("synthetic-stored", "synthetic-runtime")!!
        return Coordinator(VoiceOperation(VoiceOwner(activity), object : VoiceCapture {
            override suspend fun stop() = byteArrayOf(1, 2)
            override fun cancel() {}
        }))
    }
    @Test fun permissionDenialAndRevokedPermissionCallbackNeverStart() = runTest {
        val c = coordinator(); var state = VoiceUiState()
        val controller = VoiceDictationController(c, backgroundScope, { testScheduler.currentTime }, { true }, { state = it }, {})
        val first = controller.requestPermission()!!
        assertNull(controller.requestPermission())
        controller.permissionResult(first, false, true, "synthetic-stored", "synthetic-runtime")
        assertTrue(state.openSettings); assertEquals(VoicePhase.IDLE, state.phase)
        controller.dismissMessage(); assertNull(state.message)
        val second = controller.requestPermission()!!
        controller.cancel()
        controller.permissionResult(second, true, false, "synthetic-stored", "synthetic-runtime")
        runCurrent(); assertEquals(0, c.starts)
    }
    @Test fun stopOnceAppendsToLatestDraftAndDoesNotSend() = runTest {
        val c = coordinator(); var state = VoiceUiState(); var draft = "initial"; var appends = 0
        val controller = VoiceDictationController(c, backgroundScope, { testScheduler.currentTime }, { true }, { state = it }, {
            appends++; draft = appendDictation(draft, it)
        })
        controller.permissionResult(controller.requestPermission()!!, true, false, "synthetic-stored", "synthetic-runtime")
        runCurrent(); assertEquals(VoicePhase.RECORDING, state.phase)
        draft = "latest edited draft"
        controller.stop(); controller.stop(); runCurrent()
        assertEquals(1, c.stops); assertEquals(VoicePhase.TRANSCRIBING, state.phase)
        c.result.complete(VoiceResult.Transcript(" synthetic transcript ")); runCurrent()
        assertEquals("latest edited draft\nsynthetic transcript", draft)
        assertEquals(1, appends); assertEquals(1L, state.transcriptRevision)
        assertEquals(VoicePhase.IDLE, state.phase); assertTrue(c.cancels > 0)
        assertEquals("existing\nwords", appendDictation("existing\n", "words"))
        assertEquals("words", appendDictation("", "words"))
    }
    @Test fun fixedTwoMinuteCapStopsAndTranscribesOnce() = runTest {
        val c = coordinator(); var state = VoiceUiState()
        val controller = VoiceDictationController(c, backgroundScope, { testScheduler.currentTime }, { true }, { state = it }, {})
        controller.permissionResult(controller.requestPermission()!!, true, false, "synthetic-stored", "synthetic-runtime")
        runCurrent(); advanceTimeBy(119_999); runCurrent()
        assertEquals(0, c.stops); assertEquals(119, state.elapsedSeconds)
        advanceTimeBy(1); runCurrent(); assertEquals(1, c.stops)
        assertEquals(VoicePhase.TRANSCRIBING, state.phase)
        controller.cancel()
    }
    @Test fun lifecycleCancelAndScopeChangeDropLateResults() = runTest {
        for (scopeChange in listOf(false, true)) {
            val c = coordinator(); var state = VoiceUiState(); var appends = 0
            val controller = VoiceDictationController(c, backgroundScope, { testScheduler.currentTime }, { true }, { state = it }, { appends++ })
            controller.permissionResult(controller.requestPermission()!!, true, false, "synthetic-stored", "synthetic-runtime")
            runCurrent(); controller.stop(); runCurrent()
            if (scopeChange) { c.current = false; advanceTimeBy(250); runCurrent() } else controller.cancel()
            c.result.complete(VoiceResult.Transcript("obsolete")); runCurrent()
            assertEquals(0, appends); assertEquals(VoicePhase.IDLE, state.phase); assertTrue(c.cancels > 0)
        }
    }
    @Test fun emptyFailureAndRecorderStartFailurePreserveDraft() = runTest {
        for (result in listOf(VoiceResult.Transcript(" "), VoiceResult.Failed(VoiceFailure.TIMEOUT), VoiceResult.Failed(VoiceFailure.UNAVAILABLE))) {
            val c = coordinator(); var state = VoiceUiState(); var appends = 0
            val controller = VoiceDictationController(c, backgroundScope, { testScheduler.currentTime }, { true }, { state = it }, { appends++ })
            controller.permissionResult(controller.requestPermission()!!, true, false, "synthetic-stored", "synthetic-runtime")
            runCurrent(); controller.stop(); c.result.complete(result); runCurrent()
            assertEquals(0, appends); assertNotNull(state.message); assertEquals(VoicePhase.IDLE, state.phase)
        }
        val c = coordinator()
        val broken = object : VoiceDictationCoordinator by c {
            override suspend fun start(storedId: String, runtimeId: String): VoiceOperation = throw IllegalStateException("synthetic private error")
        }
        var state = VoiceUiState()
        val controller = VoiceDictationController(broken, backgroundScope, { testScheduler.currentTime }, { true }, { state = it }, {})
        controller.permissionResult(controller.requestPermission()!!, true, false, "synthetic-stored", "synthetic-runtime")
        runCurrent(); assertEquals(VoiceFailure.MICROPHONE.message, state.message)
    }
    @Test fun viewModelDraftPersistenceSendIsolationAndDisconnectCancellation() = runTest {
        val f = VoiceTestFixture(temp.newFolder(), dispatcher = main.testDispatcher); f.initialize()
        val c = coordinator(); val gateway = FakeGatewayProtocolAdapter()
        val vm = ChatViewModel("synthetic-stored", sessionRepository = f.sessions, vaultStorage = f.vault,
            gatewayProtocolAdapter = gateway, voiceCoordinator = c, voiceClock = { testScheduler.currentTime })
        vm.uiState.first { !it.isInitialLoading }
        vm.updateDraft("typed")
        assertTrue(vm.uiState.value.canStartDictation)
        vm.dictationPermissionResult(vm.requestDictationPermission()!!, true, false)
        runCurrent()
        assertFalse(vm.uiState.value.isComposerReady); assertFalse(vm.uiState.value.canAttachFile)
        vm.submitPrompt(); assertEquals(0, gateway.submitPromptCallCount)
        vm.updateDraft("edited while recording")
        vm.stopDictation(); runCurrent()
        c.result.complete(VoiceResult.Transcript("dictated")); runCurrent()
        assertEquals("edited while recording\ndictated", vm.uiState.value.composerDraft)
        assertEquals(vm.uiState.value.composerDraft, f.vault.getDraft("synthetic-stored")!!.composerText)
        assertEquals(0, gateway.submitPromptCallCount)
        val token = vm.requestDictationPermission()!!
        vm.dictationPermissionResult(token, true, false); runCurrent()
        gateway.connectionStatus.value = ConnectionStatus.OFFLINE
        runCurrent(); assertEquals(VoicePhase.IDLE, vm.uiState.value.voice.phase)
        vm.cancelDictation()
    }
    @Test fun dictationUsesResolvedDurableSessionInsteadOfNavigationAlias() = runTest {
        val f = VoiceTestFixture(temp.newFolder(), dispatcher = main.testDispatcher); f.initialize()
        val sessions = object : com.hermes.bot.data.session.SessionRepository by f.sessions {
            override suspend fun resumeSession(storedSessionId: String) = Result.success(
                com.hermes.bot.data.gateway.SessionResumeResult("synthetic-runtime", "synthetic-resolved", 0)
            )
        }
        val c = coordinator()
        val vm = ChatViewModel("synthetic-alias", sessionRepository = sessions, vaultStorage = f.vault,
            gatewayProtocolAdapter = FakeGatewayProtocolAdapter(), voiceCoordinator = c,
            voiceClock = { testScheduler.currentTime })
        vm.uiState.first { !it.isInitialLoading }
        assertEquals("synthetic-resolved", vm.uiState.value.durableSessionId)
        vm.dictationPermissionResult(vm.requestDictationPermission()!!, true, false)
        runCurrent()
        assertEquals("synthetic-resolved", c.lastStoredId)
        assertEquals(VoicePhase.RECORDING, vm.uiState.value.voice.phase)
        vm.cancelDictation()
    }
    @Test fun readinessRequiresAvailableSteadyChatAndIdleVoice() {
        val ready = ChatUiState(sessionId = "synthetic-stored", runtimeSessionId = "synthetic-runtime", dictationAvailable = true)
        assertTrue(ready.canStartDictation)
        assertTrue(ready.copy(pickerConnected = true).canOpenPicker)
        assertFalse(ready.copy(pickerConnected = true, voice = VoiceUiState(VoicePhase.RECORDING)).canOpenPicker)
        assertFalse(ready.copy(dictationAvailable = false).canStartDictation)
        assertFalse(ready.copy(isStreaming = true).canStartDictation)
        assertFalse(ready.copy(isInitialLoading = true).canStartDictation)
        assertFalse(ready.copy(voice = VoiceUiState(VoicePhase.RECORDING), composerDraft = "/help").isComposerReady)
    }
    @Test fun microphoneFailureCancelsWithoutTranscription() = runTest {
        val c = coordinator(); var state = VoiceUiState()
        val controller = VoiceDictationController(c, backgroundScope, { testScheduler.currentTime }, { true }, { state = it }, {})
        controller.permissionResult(controller.requestPermission()!!, true, false, "synthetic-stored", "synthetic-runtime")
        runCurrent(); c.recordingFault = VoiceFailure.MICROPHONE
        advanceTimeBy(250); runCurrent()
        assertEquals(VoicePhase.IDLE, state.phase)
        assertEquals(VoiceFailure.MICROPHONE.message, state.message)
        assertEquals(0, c.stops); assertTrue(c.cancels > 0)
    }

    @Test fun obsoleteNonCooperativeOwnershipQueryCannotCancelNewRecording() = runTest {
        val c = coordinator(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var checks = 0
        val delayed = object : VoiceDictationCoordinator by c {
            override suspend fun isCurrent(operation: VoiceOperation): Boolean {
                if (++checks == 1) {
                    withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    return false
                }
                return true
            }
        }
        var state = VoiceUiState()
        val controller = VoiceDictationController(delayed, backgroundScope, { testScheduler.currentTime }, { true }, { state = it }, {})
        controller.permissionResult(controller.requestPermission()!!, true, false, "synthetic-stored", "synthetic-runtime")
        runCurrent(); advanceTimeBy(250); runCurrent(); entered.await()
        controller.cancel()
        controller.permissionResult(controller.requestPermission()!!, true, false, "synthetic-stored", "synthetic-runtime")
        runCurrent(); assertEquals(VoicePhase.RECORDING, state.phase)
        release.complete(Unit); runCurrent()
        assertEquals(VoicePhase.RECORDING, state.phase); assertEquals(2, c.starts)
        controller.cancel()
    }

}
