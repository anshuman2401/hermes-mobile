package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.context.*
import io.github.anshuman2401.hermesmobile.data.commands.*
import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatContextUsageTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private class Usage : ContextUsageRepository {
        var reads = 0
        var current = true
        var beforeCurrent: suspend () -> Unit = {}
        var before: suspend () -> Unit = {}
        var result: GatewayResult<ContextUsageSnapshot> = GatewayResult.Success(ContextUsageSnapshot(
            ContextBreakdown(100,1000,10.0,true,"Synthetic model",listOf(ContextCategory("conversation","Conversation",150))),
            SessionUsage(500,AccountAllowanceParser.parse(listOf("Weekly: 36% remaining (64% used)")))))
        override val signals = MutableSharedFlow<ContextUsageSignal>(extraBufferCapacity = 16)
        override suspend fun capture(storedId: String, runtimeId: String) = ContextUsageOwner(storedId,runtimeId)
        override suspend fun isCurrent(owner: ContextUsageOwner): Boolean { beforeCurrent(); return current }
        override suspend fun read(owner: ContextUsageOwner): GatewayResult<ContextUsageSnapshot> { reads++; before(); return result }
        override suspend fun admitEvent(storedId: String, event: GatewayEvent): ContextUsageSignal? =
            if (event is GatewayEvent.SessionUsageUpdated && isCurrent(ContextUsageOwner(storedId,event.sessionId!!)))
                ContextUsageSignal(ContextUsageSignalKind.USAGE_UPDATED,event.sessionId,event.usage)
            else if (event is GatewayEvent.StatusUpdate && event.kind == "compacted")
                ContextUsageSignal(ContextUsageSignalKind.COMPACTED,event.sessionId) else null
    }
    private class Commands : SlashCommandRepository {
        var calls = 0
        var before: suspend () -> Unit = {}
        var current = true
        var response: CommandOutcome = CommandOutcome.Result(SlashDirective.Compressed(false,true,"Context compressed."))
        override suspend fun capture(storedId: String, runtimeId: String) = CommandSession(storedId,runtimeId)
        override suspend fun isCurrent(session: CommandSession) = current
        override suspend fun suggestions(session: CommandSession, query: String) = emptyList<SlashSuggestion>()
        override suspend fun execute(session: CommandSession, invocation: SlashInvocation): CommandOutcome { calls++; before(); return response }
    }
    private class Fixture(val vm: ChatViewModel, val usage: Usage, val commands: Commands,
        val adapter: FakeGatewayProtocolAdapter, val sessions: ChatComposerSingleFlightTest.SingleFlightSessionRepository)
    private fun fixture(): Fixture {
        val vault = EncryptedVault(folder.newFolder(),AesGcmCipherEngine(InMemoryMasterKeyProvider()),ioDispatcher = main.testDispatcher)
        val usage = Usage(); val commands = Commands(); val adapter = FakeGatewayProtocolAdapter()
        val sessions = ChatComposerSingleFlightTest.SingleFlightSessionRepository()
        sessions.resumeOutcome = sessions.resumeOutcome.map { it.copy(activityEvidence = ResumeActivityEvidence.NON_RUNNING,info = mapOf("running" to false)) }
        val vm = ChatViewModel("sess-100",sessionRepository = sessions,gatewayProtocolAdapter = adapter,
            pendingTurnLedger = PendingTurnLedger(vault),vaultStorage = vault,slashCommands = commands,contextUsageRepository = usage)
        return Fixture(vm,usage,commands,adapter,sessions)
    }
    private suspend fun emitUsage(f: Fixture, used: Long = 400, model: String? = "Synthetic live model", seq: Long? = null) {
        f.adapter.eventFlow.emit(GatewayEvent.SessionUsageUpdated(
            f.vm.uiState.value.runtimeSessionId, seq,
            LiveUsageSummary(ContextBreakdown(used,1000,used/10.0,false,null,emptyList()),600,model)))
    }
    @Test fun usageUpdatesQuietlyAndSurvivesCloseAndFailedDetailsRead() = runTest {
        val f=fixture();advanceUntilIdle()
        val before=f.vm.uiState.value.timeline
        emitUsage(f);advanceUntilIdle()
        assertEquals("Synthetic live model",f.vm.uiState.value.sessionModelName)
        assertEquals(before,f.vm.uiState.value.timeline)
        assertEquals(0,f.usage.reads)
        f.usage.result=GatewayResult.Failure(GatewayFailure.Transport("Synthetic failure"))
        f.vm.openContextUsage();advanceUntilIdle()
        assertEquals(400L,f.vm.uiState.value.contextUsage.live!!.context!!.used)
        assertNotNull(f.vm.uiState.value.contextUsage.error)
        f.vm.closeContextUsage();advanceUntilIdle();f.vm.openContextUsage();advanceUntilIdle()
        assertEquals(400L,f.vm.uiState.value.contextUsage.live!!.context!!.used)
        f.adapter.connectionStatus.value=io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.OFFLINE
        advanceUntilIdle()
        assertNull(f.vm.uiState.value.contextUsage.live)
    }
    @Test fun newerUsageWinsOverInFlightReadAndDuplicateSequenceIsIgnored() = runTest {
        val f=fixture();advanceUntilIdle();val gate=CompletableDeferred<Unit>()
        f.usage.before={gate.await()};f.vm.openContextUsage();runCurrent()
        emitUsage(f,400,seq=10);runCurrent()
        emitUsage(f,200,seq=10);runCurrent()
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(400L,f.vm.uiState.value.contextUsage.live!!.context!!.used)
        assertTrue(f.vm.uiState.value.contextUsage.detailsStale)
        f.usage.before={};f.vm.refreshContextUsage();advanceUntilIdle()
        assertFalse(f.vm.uiState.value.contextUsage.detailsStale)
        assertEquals(100L,f.vm.uiState.value.contextUsage.snapshot!!.context.used)
    }
    @Test fun usageModelKeepsSameModelEffortButClearsChangedModelEffort() = runTest {
        val f=fixture();advanceUntilIdle();val runtime=f.vm.uiState.value.runtimeSessionId
        f.adapter.eventFlow.emit(GatewayEvent.SessionInfo(runtime,null,mapOf("model" to "Synthetic live model","reasoning_effort" to "high")))
        advanceUntilIdle();val timeline=f.vm.uiState.value.timeline
        emitUsage(f);advanceUntilIdle()
        assertEquals("high",f.vm.uiState.value.sessionReasoningEffort)
        emitUsage(f,model="Synthetic other model");advanceUntilIdle()
        assertNull(f.vm.uiState.value.sessionReasoningEffort)
        assertEquals("Synthetic other model",f.vm.uiState.value.copy(cachedDefaultReasoningEffort="high").sessionModelLabel)
        f.adapter.eventFlow.emit(GatewayEvent.SessionInfo(runtime,null,mapOf("running" to false)))
        advanceUntilIdle()
        assertEquals("Synthetic other model",f.vm.uiState.value.sessionModelName)
        assertTrue(f.vm.uiState.value.sessionUsageEffortUnknown)
        assertEquals(timeline,f.vm.uiState.value.timeline)
        emitUsage(f,model=null);advanceUntilIdle()
        assertEquals("Synthetic other model",f.vm.uiState.value.sessionModelName)
        f.adapter.eventFlow.emit(GatewayEvent.SessionUsageUpdated("other-runtime",null,LiveUsageSummary(model="Wrong")))
        advanceUntilIdle();assertEquals("Synthetic other model",f.vm.uiState.value.sessionModelName)
    }
    @Test fun generationChangeDuringSuspendingValidationRejectsEvent() = runTest {
        val f=fixture();advanceUntilIdle();val gate=CompletableDeferred<Unit>()
        f.usage.beforeCurrent={gate.await()}
        emitUsage(f);runCurrent();f.adapter.connectionGeneration++
        gate.complete(Unit);advanceUntilIdle()
        assertNull(f.vm.uiState.value.contextUsage.live)
        assertNull(f.vm.uiState.value.sessionModelName)
    }
    @Test fun usageDuringStreamingUpdatesSummaryWithoutAnExtraRead() = runTest {
        val f=fixture();advanceUntilIdle();f.vm.openContextUsage();advanceUntilIdle()
        f.vm.updateDraft("Synthetic prompt");f.vm.submitPrompt();advanceUntilIdle()
        assertTrue(f.vm.uiState.value.isStreaming);val reads=f.usage.reads
        emitUsage(f);advanceUntilIdle()
        assertEquals(reads,f.usage.reads)
        assertEquals(400L,f.vm.uiState.value.contextUsage.live!!.context!!.used)
        assertEquals("Synthetic live model",f.vm.uiState.value.sessionModelName)
        assertTrue(f.vm.uiState.value.contextUsage.detailsStale)
    }
    @Test fun successfulReadSummaryStaysVisibleDuringRefreshAndFailure() = runTest {
        val f=fixture();advanceUntilIdle();f.vm.openContextUsage();advanceUntilIdle()
        val gate=CompletableDeferred<Unit>();f.usage.before={gate.await()}
        f.vm.refreshContextUsage();runCurrent()
        assertTrue(f.vm.uiState.value.contextUsage.loading)
        assertEquals(100L,f.vm.uiState.value.contextUsage.live!!.context!!.used)
        f.usage.result=GatewayResult.Failure(GatewayFailure.Transport("Synthetic failure"))
        gate.complete(Unit);advanceUntilIdle()
        assertNotNull(f.vm.uiState.value.contextUsage.error)
        assertEquals(100L,f.vm.uiState.value.contextUsage.live!!.context!!.used)
    }
    @Test fun rejectedOwnershipClearsPreviouslyAdmittedSummary() = runTest {
        val f=fixture();advanceUntilIdle();emitUsage(f);advanceUntilIdle()
        assertNotNull(f.vm.uiState.value.contextUsage.live)
        f.usage.current=false;emitUsage(f);advanceUntilIdle()
        assertNull(f.vm.uiState.value.contextUsage.live)
    }
    @Test fun authorityLossDuringEventValidationCannotPublishUsage() = runTest {
        val f=fixture();advanceUntilIdle();f.usage.current=false
        emitUsage(f);advanceUntilIdle()
        assertNull(f.vm.uiState.value.contextUsage.live)
        assertNull(f.vm.uiState.value.sessionModelName)
    }
    @Test fun openingReadsOnlyWhenVisibleAndCloseKeepsConversationAndDraft() = runTest {
        val f=fixture();advanceUntilIdle();assertEquals(0,f.usage.reads)
        f.vm.updateDraft("Synthetic unsent draft");advanceUntilIdle()
        val history=f.vm.uiState.value.timeline
        f.vm.openContextUsage();advanceUntilIdle()
        assertEquals(1,f.usage.reads);assertNotNull(f.vm.uiState.value.contextUsage.snapshot)
        f.vm.toggleContextBreakdown();assertTrue(f.vm.uiState.value.contextUsage.expanded)
        f.vm.closeContextUsage();advanceUntilIdle()
        assertFalse(f.vm.uiState.value.contextUsage.visible);assertNull(f.vm.uiState.value.contextUsage.snapshot)
        assertEquals(history,f.vm.uiState.value.timeline);assertEquals("Synthetic unsent draft",f.vm.uiState.value.composerDraft)
        f.vm.openContextUsage();advanceUntilIdle();assertEquals(2,f.usage.reads)
        assertFalse(f.vm.uiState.value.contextUsage.expanded)
    }
    @Test fun refreshIsSingleFlightAndFailureHasExplicitRetry() = runTest {
        val f=fixture();advanceUntilIdle()
        val gate=CompletableDeferred<Unit>();f.usage.before={gate.await()}
        f.vm.openContextUsage();runCurrent();f.vm.refreshContextUsage();runCurrent()
        assertEquals(1,f.usage.reads);assertTrue(f.vm.uiState.value.contextUsage.loading)
        f.usage.result=GatewayResult.Failure(GatewayFailure.Transport("Synthetic failure"))
        gate.complete(Unit);advanceUntilIdle()
        assertFalse(f.vm.uiState.value.contextUsage.loading);assertNotNull(f.vm.uiState.value.contextUsage.error)
        f.usage.before={};f.usage.result=GatewayResult.Success(ContextUsageSnapshot(ContextBreakdown(0,1000,0.0,false,"Synthetic",emptyList()),
            SessionUsage(0,AccountAllowanceParser.parse(emptyList()))))
        f.vm.refreshContextUsage();advanceUntilIdle()
        assertEquals(2,f.usage.reads);assertEquals(0L,f.vm.uiState.value.contextUsage.snapshot!!.context.used)
        assertNull(f.vm.uiState.value.contextUsage.error)
    }
    @Test fun supersededNonCooperativeReadCannotReopenSheetOrPublishSnapshot() = runTest {
        val f=fixture();advanceUntilIdle();val gate=CompletableDeferred<Unit>()
        f.usage.before={withContext(NonCancellable){gate.await()}}
        f.vm.openContextUsage();runCurrent();f.vm.closeContextUsage();runCurrent()
        gate.complete(Unit);advanceUntilIdle()
        assertFalse(f.vm.uiState.value.contextUsage.visible);assertNull(f.vm.uiState.value.contextUsage.snapshot)
    }
    @Test fun lostOwnerClearsLoadingWithoutPublishingOldAccount() = runTest {
        val f=fixture();advanceUntilIdle();val gate=CompletableDeferred<Unit>()
        f.usage.before={gate.await()};f.vm.openContextUsage();runCurrent()
        f.usage.current=false;gate.complete(Unit);advanceUntilIdle()
        assertNull(f.vm.uiState.value.contextUsage.snapshot);assertFalse(f.vm.uiState.value.contextUsage.loading)
    }
    @Test fun activeTurnInvalidatesSnapshotAndRefetchesOnCompletion() = runTest {
        val f=fixture();advanceUntilIdle();f.vm.openContextUsage();advanceUntilIdle()
        f.vm.updateDraft("Synthetic prompt");f.vm.submitPrompt();advanceUntilIdle()
        assertTrue(f.vm.uiState.value.isStreaming);assertNull(f.vm.uiState.value.contextUsage.snapshot)
        assertEquals(1,f.usage.reads);assertFalse(f.vm.uiState.value.canCompressContext)
        f.adapter.eventFlow.emit(GatewayEvent.MessageComplete("runtime-100",null,"Synthetic reply",status = "complete"));advanceUntilIdle()
        assertFalse(f.vm.uiState.value.isStreaming);assertEquals(2,f.usage.reads)
        assertNotNull(f.vm.uiState.value.contextUsage.snapshot)
    }
    @Test fun completedCompressionClearsAfterFiveSecondsAndCanBeDismissed() = runTest {
        val f = fixture(); advanceUntilIdle()
        val timeline = f.vm.uiState.value.timeline
        f.vm.updateDraft("Synthetic draft")
        f.vm.compressContext(); runCurrent()
        assertEquals(CompressionPhase.IDLE, f.vm.uiState.value.compressionPhase)
        assertNotNull(f.vm.uiState.value.compressionNotice)
        assertEquals("/compress", f.vm.uiState.value.slashResults.last().command)
        advanceTimeBy(4999); runCurrent()
        assertNotNull(f.vm.uiState.value.compressionNotice)
        advanceTimeBy(1); runCurrent()
        assertNull(f.vm.uiState.value.compressionNotice)
        assertTrue(f.vm.uiState.value.slashResults.isEmpty())
        f.vm.compressContext(); runCurrent(); f.vm.dismissCompressionNotice()
        assertNull(f.vm.uiState.value.compressionNotice)
        assertTrue(f.vm.uiState.value.slashResults.isEmpty())
        assertEquals(timeline, f.vm.uiState.value.timeline)
        assertEquals("Synthetic draft", f.vm.uiState.value.composerDraft)
    }
    @Test fun dismissingResultDoesNotResolvePendingCompression() = runTest {
        val f = fixture(); advanceUntilIdle()
        f.commands.response = CommandOutcome.Result(SlashDirective.Compressed(true, false, "Still running."))
        f.vm.compressContext(); runCurrent()
        f.vm.dismissCompressionNotice()
        assertNotNull(f.vm.uiState.value.compressionNotice)
        f.vm.dismissSlashResults()
        advanceTimeBy(6000); runCurrent()
        assertEquals(CompressionPhase.PENDING, f.vm.uiState.value.compressionPhase)
        assertNotNull(f.vm.uiState.value.compressionNotice)
        assertFalse(f.vm.uiState.value.canCompressContext)
        assertEquals(1, f.commands.calls)
    }
    @Test fun olderCompletionTimerCannotClearNewPendingCompressionOrOtherResult() = runTest {
        val f = fixture(); advanceUntilIdle()
        f.vm.compressContext(); runCurrent()
        advanceTimeBy(4000); runCurrent()
        f.commands.response = CommandOutcome.Result(SlashDirective.Compressed(true, false, "Still running."))
        f.vm.compressContext(); runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertEquals(CompressionPhase.PENDING, f.vm.uiState.value.compressionPhase)
        assertEquals("Still running.", f.vm.uiState.value.compressionNotice)
        assertEquals("Still running.", f.vm.uiState.value.slashResults.last().text)
    }
    @Test fun failedCompressionPersistsUntilDismissedAndUnknownRemainsBlocked() = runTest {
        val f = fixture(); advanceUntilIdle()
        f.commands.response = CommandOutcome.Result(SlashDirective.Compressed(false, false, "Synthetic failure.", failed = true))
        f.vm.compressContext(); runCurrent(); advanceTimeBy(6000); runCurrent()
        assertEquals("Synthetic failure.", f.vm.uiState.value.compressionNotice)
        f.vm.dismissSlashResults()
        assertNull(f.vm.uiState.value.compressionNotice)
        f.commands.response = CommandOutcome.Failure("Synthetic unknown.", outcomeUnknown = true)
        f.vm.compressContext(); runCurrent(); f.vm.dismissCompressionNotice(); f.vm.dismissSlashResults()
        advanceTimeBy(6000); runCurrent()
        assertEquals(CompressionPhase.UNKNOWN, f.vm.uiState.value.compressionPhase)
        assertEquals("Synthetic unknown.", f.vm.uiState.value.compressionNotice)
        assertFalse(f.vm.uiState.value.canCompressContext)
        assertEquals(2, f.commands.calls)
    }
    @Test fun compressionButtonSharesSingleFlightAndPreservesDraft() = runTest {
        val f=fixture();advanceUntilIdle();f.vm.openContextUsage();advanceUntilIdle()
        f.vm.updateDraft("Synthetic unsent draft");advanceUntilIdle()
        val gate=CompletableDeferred<Unit>();f.commands.before={gate.await()}
        f.vm.compressContext();runCurrent();f.vm.compressContext();f.vm.submitPrompt();runCurrent()
        assertEquals(1,f.commands.calls);assertEquals(0,f.adapter.submitPromptCallCount)
        assertFalse(f.vm.uiState.value.isComposerReady);assertFalse(f.vm.uiState.value.canCompressContext)
        gate.complete(Unit);advanceUntilIdle()
        assertEquals("Synthetic unsent draft",f.vm.uiState.value.composerDraft)
        assertEquals(CompressionPhase.IDLE,f.vm.uiState.value.compressionPhase)
        assertTrue(f.vm.uiState.value.canCompressContext);assertEquals(2,f.usage.reads)
    }
    @Test fun pendingWaitsForMatchingTerminalAndReadyPreservesFailure() = runTest {
        val f=fixture();advanceUntilIdle();f.vm.openContextUsage();advanceUntilIdle()
        f.commands.response=CommandOutcome.Result(SlashDirective.Compressed(true,false,"Still running."))
        f.vm.compressContext();advanceUntilIdle();assertEquals(CompressionPhase.PENDING,f.vm.uiState.value.compressionPhase)
        f.vm.compressContext();assertEquals(1,f.commands.calls)
        f.usage.signals.emit(ContextUsageSignal(ContextUsageSignalKind.COMPACTED,"other-runtime"));advanceUntilIdle()
        assertEquals(CompressionPhase.PENDING,f.vm.uiState.value.compressionPhase)
        f.usage.signals.emit(ContextUsageSignal(ContextUsageSignalKind.FAILED,"runtime-100"))
        f.usage.signals.emit(ContextUsageSignal(ContextUsageSignalKind.READY,"runtime-100"));advanceUntilIdle()
        assertEquals(CompressionPhase.IDLE,f.vm.uiState.value.compressionPhase)
        assertEquals("Hermes could not compress this conversation.",f.vm.uiState.value.compressionNotice)
        assertEquals(1,f.commands.calls);assertEquals(2,f.usage.reads)
    }
    @Test fun compactedBeforePendingRpcReplyIsNotLost() = runTest {
        val f=fixture();advanceUntilIdle();val gate=CompletableDeferred<Unit>()
        f.commands.before={gate.await()};f.commands.response=CommandOutcome.Result(SlashDirective.Compressed(true,false,"Still running."))
        f.vm.compressContext();runCurrent()
        f.usage.signals.emit(ContextUsageSignal(ContextUsageSignalKind.COMPACTED,"runtime-100"));runCurrent()
        assertEquals(CompressionPhase.REQUESTING,f.vm.uiState.value.compressionPhase)
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(CompressionPhase.IDLE,f.vm.uiState.value.compressionPhase);assertEquals(1,f.commands.calls)
    }
    @Test fun ambiguousWriteNeverRetriesAndExplicitRefreshReconcilesHistory() = runTest {
        val f=fixture();advanceUntilIdle()
        f.commands.response=CommandOutcome.Failure("Compression outcome unknown.",outcomeUnknown=true)
        f.vm.compressContext();advanceUntilIdle();f.vm.compressContext();advanceUntilIdle()
        assertEquals(CompressionPhase.UNKNOWN,f.vm.uiState.value.compressionPhase);assertEquals(1,f.commands.calls)
        f.vm.refreshContextUsage();advanceUntilIdle()
        assertEquals(CompressionPhase.IDLE,f.vm.uiState.value.compressionPhase);assertEquals(1,f.commands.calls)
    }
    @Test fun slashCompressionAndSheetCompressionUseSameExclusion() = runTest {
        val f=fixture();advanceUntilIdle();val gate=CompletableDeferred<Unit>();f.commands.before={gate.await()}
        f.vm.updateDraft("/compress");f.vm.submitPrompt();runCurrent();f.vm.compressContext()
        assertEquals(1,f.commands.calls);assertEquals(CompressionPhase.REQUESTING,f.vm.uiState.value.compressionPhase)
        gate.complete(Unit);advanceUntilIdle();assertEquals("",f.vm.uiState.value.composerDraft)
        assertEquals(CompressionPhase.IDLE,f.vm.uiState.value.compressionPhase)
    }
    @Test fun disconnectCancelsReadAndCompressionWithoutResend() = runTest {
        val f=fixture();advanceUntilIdle();f.vm.openContextUsage();advanceUntilIdle()
        val gate=CompletableDeferred<Unit>();f.commands.before={gate.await()}
        f.vm.compressContext();runCurrent()
        f.adapter.connectionStatus.value=io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.OFFLINE;runCurrent()
        assertNull(f.vm.uiState.value.contextUsage.snapshot)
        assertEquals(CompressionPhase.UNKNOWN,f.vm.uiState.value.compressionPhase)
        gate.complete(Unit);advanceUntilIdle();assertEquals(1,f.commands.calls)
        assertFalse(f.vm.uiState.value.canCompressContext)
    }
    @Test fun failedAuthoritativeRefreshNeverUnlocksUnknownCompression() = runTest {
        val f=fixture();advanceUntilIdle();f.commands.response=CommandOutcome.Failure("Unknown",true)
        f.vm.compressContext();advanceUntilIdle()
        f.sessions.resumeOutcome=Result.failure(IllegalStateException("Synthetic failure"))
        f.vm.refreshContextUsage();advanceUntilIdle()
        assertEquals(CompressionPhase.UNKNOWN,f.vm.uiState.value.compressionPhase)
        assertFalse(f.vm.uiState.value.canCompressContext);assertEquals(1,f.commands.calls)
    }    @Test fun nativeCompletionUsesExistingCollectorAndSheetAddsNoSubscription() = runTest {
        val f=fixture();advanceUntilIdle()
        assertEquals(1,f.adapter.eventFlow.subscriptionCount.value)
        f.vm.openContextUsage();advanceUntilIdle()
        assertEquals(1,f.adapter.eventFlow.subscriptionCount.value)
        f.commands.response=CommandOutcome.Result(SlashDirective.Compressed(true,false,"Still running."))
        f.vm.compressContext();advanceUntilIdle()
        f.adapter.eventFlow.emit(GatewayEvent.StatusUpdate("other-runtime",null,"",kind="compacted"));advanceUntilIdle()
        assertEquals(CompressionPhase.PENDING,f.vm.uiState.value.compressionPhase)
        f.adapter.eventFlow.emit(GatewayEvent.StatusUpdate("runtime-100",null,"",kind="compacted"));runCurrent()
        assertEquals(CompressionPhase.IDLE,f.vm.uiState.value.compressionPhase)
        assertEquals("Conversation context compressed.",f.vm.uiState.value.compressionNotice)
        advanceTimeBy(5000); runCurrent()
        assertNull(f.vm.uiState.value.compressionNotice)
        assertEquals(1,f.commands.calls)
    }
    @Test fun dismissalDuringSuspendedAuthorityCheckStillOwnsPublication() = runTest {
        val f=fixture();advanceUntilIdle();val gate=CompletableDeferred<Unit>()
        f.usage.beforeCurrent={withContext(NonCancellable){gate.await()}}
        f.vm.openContextUsage();runCurrent()
        assertTrue(f.vm.uiState.value.contextUsage.loading)
        f.vm.closeContextUsage();runCurrent();gate.complete(Unit);advanceUntilIdle()
        assertFalse(f.vm.uiState.value.contextUsage.visible)
        assertNull(f.vm.uiState.value.contextUsage.snapshot)
        assertFalse(f.vm.uiState.value.contextUsage.loading)
    }

}
