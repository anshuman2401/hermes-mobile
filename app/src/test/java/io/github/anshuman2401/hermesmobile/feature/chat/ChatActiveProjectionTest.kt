package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.session.S4bBaselineFixture
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatActiveProjectionTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun taskSnapshotSurvivesStoredPublicationAndStopFreezesItsUnfinishedRows() = runBlocking(main.testDispatcher) {
        scenario { f ->
            f.active = true; f.holdBody = true
            val tasks = """{"todos":[{"id":"synthetic-task","content":"Synthetic step","status":"in_progress"}],"revision":1}"""
            f.resumeJson = resume(inflight = "\"user\":\"Synthetic user\",\"assistant\":\"Synthetic partial\",\"streaming\":true").dropLast(1) + ",\"todo_state\":$tasks}"
            val vm = f.vm()
            f.await(f.bodyEntered)
            val initial = state(vm) { it.taskProgress.snapshot?.revision == 1L }
            assertTrue(initial.taskProgress.live)
            f.await(f.seedEntered) // Resume publication precedes event admission; seed dispatch follows it.
            val delivery = async(start = CoroutineStart.UNDISPATCHED) { f.gateway.events.first { it is GatewayEvent.TasksUpdated } }
            f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
                """{"jsonrpc":"2.0","method":"event","params":{"type":"todo.updated","session_id":"synthetic-runtime","payload":${tasks.replace("\"revision\":1", "\"revision\":2")}}}""")
            f.await(delivery)
            state(vm) { it.taskProgress.snapshot?.revision == 2L }
            f.releaseAll(); f.joined(vm)
            assertEquals(2L, vm.uiState.value.taskProgress.snapshot!!.revision)
            assertTrue(vm.uiState.value.isStreaming)
            vm.stopStreaming()
            assertFalse(vm.uiState.value.taskProgress.live)
            assertTrue(vm.uiState.value.taskProgress.ended)
            assertEquals(io.github.anshuman2401.hermesmobile.domain.TaskStatus.IN_PROGRESS, vm.uiState.value.taskProgress.snapshot!!.items.single().status)
            assertTrue(f.transport.sentFrames.none { it.contains("prompt.submit") })
        }
    }

    @Test fun remoteActiveSnapshotRendersStreamingProjection() = runBlocking(main.testDispatcher) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try {
            f.initialize(); f.active = true
            val vm = f.vm()
            f.loaded(vm)
            assertObservedProjection(f)
            assertEquals("synthetic-runtime", vm.uiState.value.runtimeSessionId)
            assertTrue("E01: actual typed inflight assistant must render one attributable streaming snapshot row",
                vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().count {
                    it.text == "Synthetic partial" && it.state == AssistantMessageState.STREAMING
                } == 1)
            assertTrue(vm.uiState.value.isStreaming)
        } finally {
            f.close()
            assertEquals(1, f.resumeCalls.get())
            assertEquals(1, f.seedCalls.get())
            assertTrue(f.transport.sentFrames.none { it.contains("prompt.submit") })
        }
    }

    @Test fun remoteActiveSnapshotBindsExistingBufferForDeltaAndTerminal() = runBlocking(main.testDispatcher) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try {
            f.initialize(); f.active = true; f.withLocalTurns = true
            val unrelated = f.ledger.prepareTurn("synthetic-session", "Synthetic unrelated question", "synthetic-runtime")
            f.ledger.markWriteAttempted(unrelated.pendingTurnId); f.ledger.markUnknown(unrelated.pendingTurnId)
            val vm = f.vm()
            f.loaded(vm)
            assertObservedProjection(f)
            val decoded = async(start = CoroutineStart.UNDISPATCHED) {
                f.gateway.events.first { it is GatewayEvent.MessageDelta }
            }
            f.event("message.delta", " suffix")
            assertEquals("synthetic-runtime", f.await(decoded).sessionId)
            // A real-time observation window starts only after actual tagged-frame decoding.
            val changed = withContext(Dispatchers.Default) {
                withTimeoutOrNull(1500) { vm.uiState.first { state -> state.timeline
                    .filterIsInstance<AssistantMessage>().any { it.text == "Synthetic partial suffix" } } }
            }
            assertNotNull("E01: a decoded current-runtime delta must extend the snapshot in the existing buffer exactly once", changed)
            val active = vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().single { it.text == "Synthetic partial suffix" }
            assertEquals(AssistantMessageState.STREAMING, active.state)
            suspend fun frame(type: String, payload: String, predicate: (GatewayEvent) -> Boolean) = coroutineScope {
                val delivery = async(start = CoroutineStart.UNDISPATCHED) { f.gateway.events.first(predicate) }
                f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"synthetic-runtime","payload":$payload}}""")
                f.await(delivery)
            }
            frame("reasoning.delta", """{"text":"Synthetic live reasoning"}""") { it is GatewayEvent.ReasoningDelta }
            state(vm) { it.timeline.filterIsInstance<ReasoningEntry>().any { row -> row.text == "Synthetic live reasoning" } }
            frame("tool.start", """{"tool_id":"synthetic-live-call","name":"terminal","args":{"command":"Synthetic command"}}""") { it is GatewayEvent.ToolStart }
            state(vm) { it.timeline.filterIsInstance<ToolActivity>().any { row -> row.toolId == "synthetic-live-call" && row.status == ToolStatus.RUNNING } }
            frame("tool.progress", """{"type":"Synthetic progress","detail":"Synthetic progress detail"}""") { it is GatewayEvent.ToolProgress }
            state(vm) { it.timeline.filterIsInstance<GenericGatewayActivity>().any { row -> row.eventType.startsWith("tool.progress.") } }
            frame("message.interim", """{"text":"Synthetic already shown","already_streamed":true}""") { it is GatewayEvent.MessageInterim }
            frame("message.interim", """{"text":"Synthetic agent note","already_streamed":false}""") { it is GatewayEvent.MessageInterim }
            state(vm) { it.timeline.filterIsInstance<AgentNoteEntry>().any { row -> row.text == "Synthetic agent note" } }
            assertEquals(1, vm.uiState.value.timeline.filterIsInstance<AgentNoteEntry>().size)
            val queuedBoundary = vm.uiState.value.timeline.indexOfFirst { it is UserMessage && it.sourceKind == ActiveSourceKind.QUEUED }
            assertTrue(vm.uiState.value.timeline.indexOfFirst { it is AgentNoteEntry } < queuedBoundary)
            assertTrue(vm.uiState.value.timeline.indexOfFirst { it is ReasoningEntry } < queuedBoundary)
            assertTrue(vm.uiState.value.timeline.indexOfFirst { it is ToolActivity && it.toolId == "synthetic-live-call" } < queuedBoundary)
            assertEquals(active.id, vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().last().id)
            frame("tool.complete", """{"tool_id":"synthetic-live-call","name":"terminal","result":"Synthetic tool result","duration_seconds":0}""") { it is GatewayEvent.ToolComplete }
            state(vm) { it.timeline.filterIsInstance<ToolActivity>().any { row -> row.toolId == "synthetic-live-call" && row.status == ToolStatus.COMPLETED } }
            val terminal = async(start = CoroutineStart.UNDISPATCHED) {
                f.gateway.events.first { it is GatewayEvent.MessageComplete }
            }
            f.event("message.complete", "Synthetic final", "complete")
            f.await(terminal)
            val sealed = withContext(Dispatchers.Default) {
                withTimeoutOrNull(1500) { vm.uiState.first { state -> state.timeline
                    .filterIsInstance<AssistantMessage>().any { it.id == active.id && it.text == "Synthetic final" && it.state == AssistantMessageState.COMPLETE } } }
            }
            assertNotNull("E01: terminal must replace/seal the same snapshot row", sealed)
            assertFalse(vm.uiState.value.isStreaming)
            assertEquals(io.github.anshuman2401.hermesmobile.data.local.PendingTurnState.UNKNOWN,
                f.ledger.getPendingTurn(unrelated.pendingTurnId)!!.state)
            assertTrue(vm.uiState.value.timeline.any { it is AgentNoteEntry })
            assertTrue(vm.uiState.value.timeline.any { it is ReasoningEntry })
            assertEquals(1, vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().count { it.id == active.id })
        } finally {
            f.close()
            assertEquals(1, f.resumeCalls.get())
            assertEquals(1, f.seedCalls.get())
            assertTrue(f.transport.sentFrames.none { it.contains("prompt.submit") })
        }
        scenario { f ->
            f.active = true; f.holdBody = true
            f.resumeJson = resume(inflight = "\"user\":\"Synthetic empty stream user\",\"assistant\":\"\",\"streaming\":true", queued = "Synthetic empty stream user")
            val vm = f.vm(); f.await(f.bodyEntered)
            val initial = state(vm) { it.isStreaming && it.runtimeSessionId == "synthetic-runtime" }
            val id = initial.timeline.filterIsInstance<AssistantMessage>().last().id
            assertEquals("", initial.timeline.filterIsInstance<AssistantMessage>().last().text)
            assertEquals(2, initial.timeline.filterIsInstance<UserMessage>().count { it.text == "Synthetic empty stream user" })
            // Streaming UI is published before resume finishes admitting gateway events.
            f.await(f.seedEntered)
            f.delivered(vm, "message.delta", "Synthetic first delta")
            state(vm) { it.timeline.filterIsInstance<AssistantMessage>().any { row -> row.id == id && row.text == "Synthetic first delta" } }
            f.delivered(vm, "message.complete", "Synthetic failed final", "error")
            state(vm) { !it.isStreaming && it.timeline.filterIsInstance<AssistantMessage>().any { row -> row.id == id && row.state == AssistantMessageState.FAILED } }
            f.releaseAll(); f.joined(vm)
            assertEquals(1, vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().count { it.id == id && it.text == "Synthetic failed final" })
            assertEquals(AssistantMessageState.FAILED, vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().last().state)
            assertTrue(vm.uiState.value.timeline.filterIsInstance<UserMessage>().any { it.sourceKind == ActiveSourceKind.QUEUED })
            assertTrue(f.transport.sentFrames.none { it.contains("prompt.submit") })
        }
    }

    @Test fun remoteTerminalRestoresComposerOnStillAttachedRuntime() = runBlocking(main.testDispatcher) {
        assertRecoveredNextSend(holdBody = false)
    }

    @Test fun lateRestAfterRemoteTerminalDoesNotRearmRuntimeBlock() = runBlocking(main.testDispatcher) {
        assertRecoveredNextSend(holdBody = true)
    }

    private suspend fun assertRecoveredNextSend(holdBody: Boolean) = scenario { f ->
        f.active = true; f.withLocalTurns = true; f.holdBody = holdBody
        val unrelated = f.ledger.prepareTurn("synthetic-session", "Synthetic unrelated question", "synthetic-runtime")
        f.ledger.markWriteAttempted(unrelated.pendingTurnId); f.ledger.markUnknown(unrelated.pendingTurnId)
        val vm = f.vm()
        if (holdBody) {
            f.await(f.bodyEntered)
            state(vm) { it.isStreaming && it.runtimeSessionId == "synthetic-runtime" }
            f.await(f.seedEntered) // The live callback has installed event admission before seed dispatch.
        } else f.loaded(vm)
        vm.updateDraft("Synthetic next question")
        assertFalse(vm.uiState.value.isComposerReady)
        vm.submitPrompt()
        assertEquals("Synthetic next question", vm.uiState.value.composerDraft)
        assertTrue(f.transport.sentFrames.none { it.contains("prompt.submit") })
        f.delivered(vm, "message.complete", "Synthetic independent terminal", "complete")
        val sealed = state(vm) { !it.isStreaming && it.timeline.filterIsInstance<AssistantMessage>().any { row ->
            row.text == "Synthetic independent terminal" && row.state == AssistantMessageState.COMPLETE
        } }
        assertEquals("synthetic-runtime", sealed.runtimeSessionId)
        assertNull("Current certified terminal must release the RUNNING send gate", sealed.submitBlockedReason)
        assertTrue(sealed.isComposerReady)
        if (holdBody) { f.releaseAll(); f.joined(vm) }
        assertFalse(vm.uiState.value.isStreaming)
        assertNull("Late stored paint must not reinstate the obsolete runtime block", vm.uiState.value.submitBlockedReason)
        assertTrue(vm.uiState.value.isComposerReady)
        assertEquals(io.github.anshuman2401.hermesmobile.data.local.PendingTurnState.UNKNOWN,
            f.ledger.getPendingTurn(unrelated.pendingTurnId)!!.state)
        f.transport.setRpcHandler("prompt.submit") { id, _ -> f.reply(id, """{"status":"streaming"}""") }
        vm.submitPrompt()
        val admitted = state(vm) { it.isStreaming && it.timeline.filterIsInstance<UserMessage>().any { row ->
            row.text == "Synthetic next question"
        } }
        val next = admitted.timeline.filterIsInstance<AssistantMessage>().single { it.id.startsWith("asst-turn-") }
        assertEquals(io.github.anshuman2401.hermesmobile.data.local.PendingTurnState.CONFIRMED_ADMITTED,
            f.ledger.getPendingTurn(next.id.removePrefix("asst-"))!!.state)
        assertEquals(1, f.transport.sentFrames.count { it.contains("prompt.submit") })
        assertEquals(io.github.anshuman2401.hermesmobile.data.local.PendingTurnState.UNKNOWN,
            f.ledger.getPendingTurn(unrelated.pendingTurnId)!!.state)
    }

    @Test fun uncertifiedRemoteTerminalKeepsRecoveredSendBlocked() = runBlocking(main.testDispatcher) { scenario { f ->
        f.active = true; f.withLocalTurns = true; f.holdBody = true
        val vm = f.vm(); f.await(f.bodyEntered)
        state(vm) { it.isStreaming && it.runtimeSessionId == "synthetic-runtime" }
        // Streaming paint precedes event admission; seed dispatch proves the resume callback finished.
        f.await(f.seedEntered)
        vm.updateDraft("Synthetic next question")
        f.delivered(vm, "message.complete", "Synthetic unknown terminal", "uncertified")
        state(vm) { !it.isStreaming && it.timeline.filterIsInstance<AssistantMessage>().any { row ->
            row.text == "Synthetic unknown terminal" && row.state == AssistantMessageState.UNKNOWN
        } }
        f.releaseAll(); f.joined(vm)
        assertEquals(SubmitBlockedReason.NO_RUNTIME, vm.uiState.value.submitBlockedReason)
        assertFalse(vm.uiState.value.isComposerReady)
        vm.submitPrompt()
        assertEquals("Synthetic next question", vm.uiState.value.composerDraft)
        assertTrue(f.transport.sentFrames.none { it.contains("prompt.submit") })
    } }

    private fun stored(vararg rows: String): String = """{"session_id":"synthetic-session","profile":"default","messages":[${rows.joinToString(",")}],"pagination":{"limit":120,"offset":0,"order":"latest","returned":${rows.size}}}"""
    private fun row(id: Int, role: String, text: String, seconds: String = "", extra: String = "") =
        """{"id":$id,"session_id":"synthetic-session","role":"$role","content":"$text"${if(seconds.isNotEmpty()) ",\"timestamp\":$seconds" else ""}${if(extra.isNotEmpty()) ",$extra" else ""}}"""
    private fun resume(messages: List<String> = emptyList(), inflight: String = "", queued: String = "", activity: String = "\"running\":true,\"status\":\"running\"", turn: String = "100") =
        """{"session_id":"synthetic-runtime","stored_session_id":"synthetic-session","message_count":${messages.size},"messages":[${messages.joinToString(",")}],$activity,"turn_started_at":$turn${if(inflight.isNotEmpty()) ",\"inflight\":{$inflight}" else ""}${if(queued.isNotEmpty()) ",\"queued\":{\"user\":\"$queued\"}" else ""}}"""
    private fun message(role: String, text: String, seconds: String = "") =
        """{"role":"$role","content":"$text"${if(seconds.isNotEmpty()) ",\"timestamp\":$seconds" else ""}}"""
    private suspend fun scenario(block: suspend CoroutineScope.(S4bBaselineFixture) -> Unit) = coroutineScope {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try { f.initialize(); block(f) }
        finally {
            f.releaseAll()
            withContext(NonCancellable + Dispatchers.Default) { withTimeout(5000) { this@coroutineScope.coroutineContext[Job]!!.children.toList().joinAll() } }
            f.close()
            assertEquals(f.httpCalls.get(), f.responseCloses.get())
        }
    }
    private suspend fun state(vm: ChatViewModel, predicate: (ChatUiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(5000) { vm.uiState.first(predicate) } }
    private fun group(id: String, role: String, text: String, kind: ActiveSourceKind = ActiveSourceKind.STORED,
        seconds: Double? = null, pending: Boolean? = null, extra: List<TimelineEntry> = emptyList()): ActiveFoldMessage =
        ActiveFoldMessage(id, role, listOf(if(role == "user") UserMessage(id, text, timestamp = 0, sourceKind = kind)
            else AssistantMessage(id, text, timestamp = 0, sourceKind = kind)) + extra, kind, seconds, pending)

    @Test fun fourAnchorsUseLastRuntimeLastPersistedAndActualTimestamps() = runBlocking(main.testDispatcher) {
        val observed = ArrayList<Boolean>()
        for (variant in 0..3) scenario { f ->
            f.storedJson = stored(row(1,"assistant","Synthetic anchor","10.25"), row(2,"user","Synthetic active question","11.5"))
            val runtime = when(variant) {
                0 -> listOf(message("assistant","Synthetic anchor","10.25"))
                1 -> listOf(message("user","Synthetic active question","11.5"))
                2 -> listOf(message("assistant","Synthetic absent","10.25"))
                else -> emptyList()
            }
            f.resumeJson = resume(runtime, "\"user\":\"Synthetic active question\",\"assistant\":\"Synthetic partial\",\"streaming\":true")
            val vm = f.vm(); f.loaded(vm)
            observed.add(vm.uiState.value.timeline.filterIsInstance<UserMessage>().none { it.sourceKind == ActiveSourceKind.LOCAL_USER })
            assertEquals(0L, vm.uiState.value.timeline.first { it.id == "stored-2" }.timestamp)
        }
        assertEquals(listOf(true, false, false, true), observed)
        val a = group("a","assistant","", seconds=10.25)
        val u = group("u","user","Synthetic user",seconds=20.0)
        val repeated = listOf(a, u, a.copy(id="later"))
        assertFalse(ActiveHistoryFold.suppressInflightUser(repeated, listOf(a), u.text))
        assertTrue(ActiveHistoryFold.suppressInflightUser(listOf(a,u), listOf(a.copy(id="other")), u.text))
        assertFalse(ActiveHistoryFold.suppressInflightUser(listOf(a,u), listOf(a.copy(sourceTimestampSeconds=10.0)), u.text))
        assertFalse(ActiveHistoryFold.suppressInflightUser(listOf(a,u), listOf(a.copy(sourceTimestampSeconds=null)), u.text))
        assertTrue(ActiveHistoryFold.suppressInflightUser(listOf(a.copy(parts=listOf(AssistantMessage("a","Synthetic answer",timestamp=0))),u),
            listOf(group("r","assistant"," Synthetic   answer ")),u.text))
    }

    @Test fun onlyRepresentedSyntheticOpenTailIsRemoved() = runBlocking(main.testDispatcher) { scenario { f ->
        f.withLocalTurns = true
        val vm = f.vm(); f.loaded(vm)
        f.transport.setRpcHandler("prompt.submit") { id,_ -> f.reply(id,"""{"status":"streaming"}""") }
        vm.updateDraft("Synthetic local question"); vm.submitPrompt()
        state(vm) { it.isStreaming }
        f.delivered(vm,"message.delta","Synthetic local partial")
        val streamed = state(vm) { it.timeline.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic local partial" } }
        val local = streamed.timeline.filterIsInstance<AssistantMessage>().single { it.text == "Synthetic local partial" }
        assertTrue(local.id.startsWith("asst-turn-")); assertEquals(ActiveSourceKind.STREAM,local.sourceKind)
        val pending = f.vault.getPendingTurn(local.id.removePrefix("asst-"))!!
        assertEquals(io.github.anshuman2401.hermesmobile.data.local.PendingTurnState.CONFIRMED_ADMITTED,pending.state)
        val synthetic = listOf(group("u","user","Synthetic local question",ActiveSourceKind.LOCAL_USER),
            group(local.id,"assistant",local.text,ActiveSourceKind.STREAM,pending=true),
            group("q","user","Synthetic local question",ActiveSourceKind.QUEUED))
        val represented = listOf(group("stored-u","user"," Synthetic local question "),group("stored-a","assistant",local.text+" suffix"))
        assertTrue(ActiveHistoryFold.removeRepresentedTail(represented,synthetic).isEmpty())
        assertEquals(synthetic,ActiveHistoryFold.removeRepresentedTail(listOf(represented.first(),group("x","assistant","Synthetic divergence")),synthetic))
        assertEquals(synthetic,ActiveHistoryFold.removeRepresentedTail(listOf(group("x","user","Synthetic other"),represented.last()),synthetic))
        val completed = synthetic.take(2).map { if(it.role=="assistant") it.copy(sourceKind=ActiveSourceKind.STORED,pending=false) else it }
        assertEquals(completed,ActiveHistoryFold.removeRepresentedTail(represented,completed))
        f.storedJson=stored(row(1,"user","Synthetic stored question"),row(2,"assistant","Synthetic stored answer"),
            row(3,"user","Synthetic local question"),row(4,"assistant","Synthetic local partial suffix"))
        vm.loadSession();f.loaded(vm)
        assertTrue(vm.uiState.value.timeline.none { it.id==local.id || it.id==pending.pendingTurnId })
        assertEquals("Synthetic local partial suffix",vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().last().text)
        assertEquals(1,f.transport.sentFrames.count { it.contains("prompt.submit") })
    } }

    @Test fun structuredCurrentTurnAndCommittedPartialNeedSourceEvidence() = runBlocking(main.testDispatcher) {
        for (timestamp in listOf("99","100","101","")) scenario { f ->
            f.storedJson = stored(row(1,"user","Synthetic current user","100"),
                row(2,"assistant","Synthetic committed answer",timestamp))
            f.resumeJson = resume(inflight="\"user\":\"Synthetic current user\",\"assistant\":\"Synthetic committed\",\"streaming\":true")
            val vm=f.vm();f.loaded(vm)
            val reused = vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().none { it.text=="Synthetic committed" }
            assertEquals(timestamp=="100" || timestamp=="101", reused)
        }
        for (timestamp in listOf("99","101")) scenario { f ->
            f.storedJson=stored(row(1,"user","Synthetic current user","100"),row(2,"assistant","",timestamp,
                "\"reasoning\":\"Synthetic structured reasoning\",\"tool_calls\":[{\"id\":\"synthetic-call\",\"function\":{\"name\":\"terminal\",\"arguments\":{\"command\":\"Synthetic command\"}}}]"),
                row(3,"tool","Synthetic result","101","\"tool_call_id\":\"synthetic-call\""))
            f.resumeJson=resume(inflight="\"user\":\"Synthetic current user\",\"assistant\":\"Synthetic flat dump\",\"streaming\":true")
            val vm=f.vm();f.loaded(vm)
            assertTrue(f.await(f.decodedStored).isSuccess)
            assertTrue(vm.uiState.value.timeline.any { it is ReasoningEntry })
            assertTrue(vm.uiState.value.timeline.any { it is ToolActivity })
            assertEquals(timestamp=="99",vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any { it.text=="Synthetic flat dump" })
        }
        for (variant in listOf("partial", "offsets", "divergent", "not-streaming", "old-source")) scenario { f ->
            f.storedJson = stored(row(1, "user", "Synthetic current user", "100"),
                row(2, "assistant", "Synthetic partial", if (variant == "old-source") "99" else "101"))
            val answer = if (variant == "divergent") "Synthetic divergence" else "Synthetic partial extension"
            val offsets = if (variant == "offsets") ",\"corrections\":[\"Synthetic correction\"],\"correction_offsets\":[0]" else ""
            f.resumeJson = resume(inflight = "\"user\":\"Synthetic current user\",\"assistant\":\"$answer\",\"streaming\":${variant != "not-streaming"}$offsets")
            val vm = f.vm(); f.loaded(vm)
            val inherited = vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().single { it.id == "stored-2" }
            assertEquals(variant == "partial", inherited.state == AssistantMessageState.STREAMING)
            if (variant == "partial") {
                assertEquals(answer, inherited.text)
                f.delivered(vm, "message.delta", " suffix")
                state(vm) { it.timeline.filterIsInstance<AssistantMessage>().any { row -> row.id == inherited.id && row.text == answer + " suffix" } }
            }
        }
    }

    @Test fun correctionsFailureAndQueuePreserveBoundaries() = runBlocking(main.testDispatcher) {
        for ((offsets, expected) in listOf("[0,2,100,1]" to listOf("ab","cdef"), "[0,-1,2,3]" to listOf("abcdef"),
            "null" to listOf("abcdef"), "[0,1]" to listOf("abcdef"))) scenario { f ->
            f.resumeJson=resume(inflight="\"user\":\"Synthetic user\",\"assistant\":\"abcdef\",\"streaming\":true,\"error\":\"Synthetic failure\",\"status\":\"error\",\"recoverable\":false,\"corrections\":[\"C0\",\"C1\",\"C2\",\"C3\"],\"correction_offsets\":$offsets,\"error_surface\":{\"retryable\":false,\"code\":\"Synthetic code\"}",queued="Synthetic user")
            val vm=f.vm();f.loaded(vm)
            val rows=vm.uiState.value.timeline
            assertEquals(expected,rows.filterIsInstance<AssistantMessage>().filter { it.id.startsWith("assistant-stream-") }.map { it.text }.filter { it.isNotEmpty() })
            assertEquals(listOf("C0","C1","C2","C3"),rows.filterIsInstance<UserMessage>().filter { it.sourceKind==ActiveSourceKind.CORRECTION }.map { it.text })
            assertEquals(AssistantMessageState.FAILED,rows.filterIsInstance<AssistantMessage>().last().state)
            assertEquals("Synthetic user",rows.filterIsInstance<UserMessage>().last().text)
            assertEquals(ActiveSourceKind.QUEUED,rows.filterIsInstance<UserMessage>().last().sourceKind)
            val parsed=f.await(f.decodedResume).activeProjection!!.inflight.value!!
            assertEquals(false,parsed.recoverable.value);assertEquals(false,parsed.errorSurface.value!!.retryable.value)
        }
        scenario { f ->
            f.resumeJson=resume(inflight="\"user\":\"Synthetic hidden\",\"display_kind\":\"hidden\",\"assistant\":\"\",\"streaming\":true",queued="Synthetic hidden")
            val vm=f.vm();f.loaded(vm)
            assertEquals(1,vm.uiState.value.timeline.filterIsInstance<UserMessage>().count { it.text=="Synthetic hidden" })
            assertTrue(vm.uiState.value.isStreaming)
        }
    }

    @Test fun structuralSameIdChangesOverlayAndUnchangedBaselineDoesNotAppend() = runBlocking(main.testDispatcher) { scenario { f ->
        f.active=true;val vm=f.vm();f.loaded(vm)
        f.delivered(vm,"message.delta"," suffix")
        val live=state(vm) { it.timeline.filterIsInstance<AssistantMessage>().any { it.text=="Synthetic partial suffix" } }
        assertEquals(1,live.timeline.filterIsInstance<AssistantMessage>().count { it.sourceKind==ActiveSourceKind.STREAM })
        val base=group("same","assistant","Synthetic answer")
        val changes=listOf(base.copy(parts=base.parts+ReasoningEntry("r",ReasoningDisplayKind.REASONING,"Synthetic reason",timestamp=0)),
            base.copy(pending=true),base.copy(interim=true),base.copy(sourceTimestampSeconds=1.25),base.copy(sourceMetadata=f.await(f.decodedResume).activeProjection),
            base.copy(sourceKind=ActiveSourceKind.STREAM),base.copy(parts=listOf(AssistantMessage("same",base.text,AssistantMessageState.FAILED,timestamp=0))))
        for(changed in changes) assertEquals(listOf(changed),ActiveHistoryFold.overlay(listOf(base),listOf(base),listOf(changed)))
        assertTrue(ActiveHistoryFold.overlay(emptyList(),listOf(base),listOf(base)).isEmpty())
        val expanded=base.copy(parts=listOf(ToolActivity("tool","synthetic-tool","terminal",ToolStatus.COMPLETED,isExpanded=false,timestamp=0)))
        assertTrue(ActiveHistoryFold.overlay(emptyList(),listOf(expanded),listOf(expanded.copy(parts=listOf((expanded.parts.single() as ToolActivity).copy(isExpanded=true))))).isEmpty())
    } }

    @Test fun settledReplySuppressionUsesOrderedNewCommittedPredicate() = runBlocking(main.testDispatcher) { scenario { f ->
        f.withLocalTurns=true;val vm=f.vm();f.loaded(vm)
        f.transport.setRpcHandler("prompt.submit") { id,_ ->f.reply(id,"""{"status":"streaming"}""") }
        vm.updateDraft("Synthetic local question");vm.submitPrompt();state(vm){it.isStreaming}
        f.delivered(vm,"message.complete","@file:src/a.kt\nSynthetic answer","complete")
        val sealed=state(vm){!it.isStreaming && it.timeline.filterIsInstance<AssistantMessage>().any{it.id.startsWith("asst-")&&it.state==AssistantMessageState.COMPLETE}}
        val local=sealed.timeline.filterIsInstance<AssistantMessage>().last()
        assertEquals(ActiveSourceKind.STREAM,local.sourceKind)
        val user=group("user","user","Synthetic local question")
        val committed=group("committed","assistant","Synthetic answer")
        for(kind in listOf(ActiveSourceKind.STREAM,ActiveSourceKind.INFLIGHT)) {
            val settled=group(local.id,"assistant",local.text,kind)
            assertEquals(listOf(user,committed),ActiveHistoryFold.overlay(listOf(user,committed),emptyList(),listOf(settled)))
            assertEquals(3,ActiveHistoryFold.overlay(listOf(user,committed),listOf(committed),listOf(settled)).size)
            assertEquals(3,ActiveHistoryFold.overlay(listOf(committed,user),emptyList(),listOf(settled)).size)
            assertEquals(3,ActiveHistoryFold.overlay(listOf(user,committed.copy(pending=true)),emptyList(),listOf(settled)).size)
            assertEquals(3,ActiveHistoryFold.overlay(listOf(user,committed),emptyList(),listOf(settled.copy(pending=true))).size)
        }
        val golden=listOf("@file:src/a.kt\nAnswer" to "Answer","@folder:`path with space`\nAnswer" to "Answer",
            "@url:\"https://example.test/a b\"\nAnswer" to "Answer","@file:a   \nAnswer" to "Answer",
            "  @file:a\nAnswer" to "@file:a\nAnswer","Answer @file:a" to "Answer @file:a",
            "@file:a @file:b\nAnswer" to "@file:a @file:b\nAnswer","@git:a\nAnswer" to "@git:a\nAnswer",
            "@file:\nAnswer" to "@file:\nAnswer","@file:a\n@session:s\nAnswer" to "Answer",
            "\ufeff@file:a\nAnswer" to "@file:a\nAnswer","@file:a\u00a0b\nAnswer" to "@file:a\u00a0b\nAnswer",
            "@file:a\u0085b\nAnswer" to "Answer","@file:a\ufeff\nAnswer" to "Answer")
        golden.forEach { (input,output)->assertEquals(output,ActiveHistoryFold.textWithoutReferenceLines(input)) }
    } }

    @Test fun activationStreamReplacementIsOnceAndRawPartsAreOrdered() = runBlocking(main.testDispatcher) { scenario { f ->
        f.active=true;f.holdBody=true;val vm=f.vm()
        state(vm){it.runtimeSessionId=="synthetic-runtime"&&it.isStreaming}
        // Streaming paint precedes event admission; seed dispatch completes that callback.
        f.await(f.seedEntered)
        f.delivered(vm,"message.delta"," suffix")
        state(vm){it.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic partial suffix"}}
        f.releaseAll();f.joined(vm)
        assertEquals(1,vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().count { it.text=="Synthetic partial suffix" })
        for((old,now,expected) in listOf(Triple("","new","new"),Triple("raw","rawmore","rawmore"),Triple(" raw","raw"," rawraw"),Triple("@file:a\nraw","raw","@file:a\nrawraw"))) {
            val activation=group("activation","assistant",old,ActiveSourceKind.STREAM)
            val current=group("current","assistant",now,ActiveSourceKind.STREAM)
            val second=group("second","assistant","next",ActiveSourceKind.STREAM)
            val result=ActiveHistoryFold.overlay(listOf(activation),emptyList(),listOf(current,second))
            assertEquals(listOf("current","second"),result.map{it.id});assertEquals(expected,result.first().text)
        }
        val emptyNext=ActiveHistoryFold.overlay(emptyList(),emptyList(),listOf(group("a","assistant","a",ActiveSourceKind.STREAM),group("b","assistant","b",ActiveSourceKind.STREAM)))
        assertEquals(listOf("a","b"),emptyNext.map{it.id})
    } }

    @Test fun postRestPresentationAndResumeRuntimeBaselinesAreDistinct() = runBlocking(main.testDispatcher) { scenario { f ->
        f.active=true;val vm=f.vm();f.loaded(vm)
        f.delivered(vm,"message.delta"," before-open")
        state(vm){it.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic partial before-open"}}
        val rpc=CompletableDeferred<String>();val decodedPage=CompletableDeferred<Unit>()
        f.transport.setRpcHandler("session.resume"){id,_ ->rpc.complete(id)}
        f.decodedStoredHook={it.getOrThrow();decodedPage.complete(Unit)}
        vm.loadSession();f.await(rpc);f.await(decodedPage)
        state(vm){!it.isInitialLoading}
        f.delivered(vm,"message.delta"," during-open")
        state(vm){it.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic partial before-open during-open"}}
        f.reply(rpc.getCompleted(),f.resumeBody().replace("Synthetic partial","Synthetic fresh snapshot"))
        f.joined(vm)
        // Changes AFTER REST differ from the presentation baseline and survive its overlay; the runtime baseline remains separate.
        assertFalse(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic fresh snapshot"})
        assertTrue(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic partial before-open during-open"})
        val secondRpc = CompletableDeferred<String>()
        val gate = CompletableDeferred<Unit>(); f.bodyGate = gate
        f.transport.setRpcHandler("session.resume") { id, _ -> secondRpc.complete(id) }
        val returned = CompletableDeferred<Unit>(); f.decodedStoredHook = { it.getOrThrow(); returned.complete(Unit) }
        vm.loadSession(); f.await(secondRpc)
        val owner = ChatViewModel::class.java.getDeclaredField("activeHistoryEventAdmission").apply { isAccessible = true }
        val liveBuffer = ChatViewModel::class.java.getDeclaredField("activeTurnBuffer").apply { isAccessible = true }
        assertNotNull("F04: captured continuing-runtime admission must be installed before the held REST body", owner.get(vm))
        assertNotNull("F04: the continuing runtime retains its existing buffer", liveBuffer.get(vm))
        val scopeField = ChatViewModel::class.java.getDeclaredField("storedHistoryScope").apply { isAccessible = true }
        val captured = scopeField.get(vm) as io.github.anshuman2401.hermesmobile.data.history.StoredHistoryScope
        assertTrue("F04: continuing runtime must retain the actual repository scope", f.repository.isStoredHistoryCurrent(captured))
        val delivery = async(start = CoroutineStart.UNDISPATCHED) { f.gateway.events.first { it is GatewayEvent.MessageDelta } }
        f.event("message.delta", " before-rest")
        val actual = f.await(delivery)
        @Suppress("UNCHECKED_CAST")
        val admission = owner.get(vm) as suspend (GatewayEvent) -> Boolean
        assertTrue("F04: the real decoded current-runtime event must pass production admission", admission(actual))
        val painted = withContext(Dispatchers.Default) { withTimeoutOrNull(5000) { vm.uiState.first {
            it.timeline.filterIsInstance<AssistantMessage>().any { row -> row.text.endsWith(" before-rest") }
        } } }
        if (painted == null) {
            assertTrue("F04: admitted delta did not reach the existing canonical buffer", (liveBuffer.get(vm) as AssistantTurnStreamBuffer).presentationWindow().endsWith(" before-rest"))
            fail("F04: canonical delta retained but scheduler did not publish its owned window")
        }
        gate.complete(Unit); f.await(returned); state(vm) { !it.isInitialLoading }
        f.reply(secondRpc.getCompleted(), f.resumeBody().replace("Synthetic partial", "Synthetic second snapshot"))
        f.joined(vm)
        assertTrue(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic second snapshot" })
        assertFalse(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any { it.text.endsWith(" before-rest") })
        val committed=group("committed","assistant","Synthetic committed")
        assertTrue(ActiveHistoryFold.overlay(emptyList(),listOf(committed),listOf(committed)).isEmpty())
        assertEquals(listOf(committed),ActiveHistoryFold.overlay(emptyList(),emptyList(),listOf(committed)))
    } }

    @Test fun unknownMalformedAndUnattributedRuntimeNeverSeedsTurn() = runBlocking(main.testDispatcher) {
        for(activity in listOf("\"running\":null,\"status\":\"idle\"","\"running\":\"true\",\"status\":\"running\"","\"status\":\"idle\"")) scenario { f ->
            f.resumeJson=resume(inflight="\"assistant\":\"Synthetic untrusted partial\",\"streaming\":true",activity=activity)
            val vm=f.vm();f.loaded(vm)
            assertEquals(ResumeActivityEvidence.UNKNOWN,f.await(f.decodedResume).activityEvidence)
            assertFalse(vm.uiState.value.isStreaming);assertNotNull(vm.uiState.value.submitBlockedReason)
            val before=vm.uiState.value.timeline
            for(id in listOf(null,"","synthetic-session","synthetic-other","synthetic-old-runtime")) f.event("message.delta","Synthetic foreign",session=id)
            f.event("message.delta","Synthetic stale generation",generation=f.gateway.connectionGeneration-1)
            f.delivered(vm,"message.delta","Synthetic unattributed")
            f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
                """{"jsonrpc":"2.0","method":"event","params":{"type":"session.title","session_id":"synthetic-runtime","payload":{"title":"Synthetic E02 barrier"}}}""")
            state(vm) { it.sessionTitle == "Synthetic E02 barrier" }
            assertEquals(before,vm.uiState.value.timeline)
        }
        scenario { f ->
            f.resumeJson = resume(inflight = "\"assistant\":\"Synthetic retained failure\",\"streaming\":false,\"error\":\"Synthetic failure\"",
                activity = "\"running\":false,\"status\":\"idle\"")
            val vm = f.vm(); f.loaded(vm)
            val before = vm.uiState.value.timeline
            assertEquals(AssistantMessageState.FAILED, before.filterIsInstance<AssistantMessage>().last().state)
            assertFalse(vm.uiState.value.isStreaming)
            f.delivered(vm, "message.delta", "Synthetic unattributed suffix")
            f.delivered(vm, "message.complete", "Synthetic unattributed final", "complete")
            f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
                """{"jsonrpc":"2.0","method":"event","params":{"type":"session.title","session_id":"synthetic-runtime","payload":{"title":"Synthetic E02 failure barrier"}}}""")
            state(vm) { it.sessionTitle == "Synthetic E02 failure barrier" }
            assertEquals(before, vm.uiState.value.timeline)
        }
        scenario { f ->
            f.resumeJson=resume(inflight="\"assistant\":17,\"streaming\":\"true\"")
            val vm=f.vm();f.loaded(vm);assertFalse(vm.uiState.value.isStreaming)
            assertEquals(WirePresence.INVALID,f.await(f.decodedResume).activeProjection!!.inflight.value!!.assistant.presence)
        }
    }

    private suspend fun assertObservedProjection(f: S4bBaselineFixture) {
        val decoded = f.await(f.decodedResume)
        assertEquals("synthetic-runtime", decoded.observedRuntimeSessionId)
        assertEquals("synthetic-session", decoded.observedDurableSessionId)
        assertEquals(ResumeActivityEvidence.RUNNING, decoded.activityEvidence)
        assertEquals("Synthetic partial", decoded.activeProjection?.inflight?.value?.assistant?.value)
        assertEquals(true, decoded.activeProjection?.inflight?.value?.streaming?.value)
    }
}
