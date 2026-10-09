package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.session.S4bBaselineFixture
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatParallelResumeTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun restPaintsWhileResumeAndSeedArePending() = runBlocking(main.testDispatcher) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        var vm: ChatViewModel? = null
        try {
            f.initialize()
            f.holdResume = true
            f.holdSeed = true
            vm = f.vm() // Constructor invokes the existing loadSession entrypoint.
            f.await(f.resumeEntered) // The actual wire RPC gate, not scheduler idleness.
            val painted = withContext(Dispatchers.Default) {
                withTimeoutOrNull(1500) {
                    vm.uiState.first { state -> !state.isInitialLoading && state.timeline
                        .filterIsInstance<AssistantMessage>().any { it.text == "Synthetic stored answer" } }
                }
            }
            assertNotNull("L01: exact REST text must be readable while the original scoped resume RPC is withheld", painted)
            assertEquals(1, f.httpCalls.get())
            assertEquals(1, f.bodyReturns.get())
            assertNull(vm.uiState.value.runtimeSessionId)
            assertNotNull(vm.uiState.value.submitBlockedReason)
            f.holdResume = false
            f.reply(f.await(f.resumeEntered), f.resumeBody())
            f.await(f.seedEntered)
            val attached = withContext(Dispatchers.Default) {
                withTimeoutOrNull(1500) { vm.uiState.first { it.runtimeSessionId == "synthetic-runtime" } }
            }
            assertNotNull("L01: live attachment must publish before replay seed completion", attached)
            assertTrue(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic stored answer" })
        } finally {
            f.releaseAll()
            // Join decoded delivery, including the seed which becomes reachable only after release.
            try {
                vm?.let { f.loaded(it) }
                assertEquals(1, f.resumeCalls.get())
                assertEquals(1, f.seedCalls.get())
                assertEquals(1, f.httpCalls.get())
                assertEquals(1, f.bodyReturns.get())
                assertEquals(1, f.responseCloses.get())
            } finally { f.close() }
        }
    }

    private suspend fun scenario(block: suspend CoroutineScope.(S4bBaselineFixture) -> Unit) = coroutineScope {
        val f=S4bBaselineFixture(temp.newFolder(),main.testDispatcher)
        try { f.initialize();block(f) }
        finally {
            f.releaseAll()
            withContext(NonCancellable+Dispatchers.Default){withTimeout(5000){this@coroutineScope.coroutineContext[Job]!!.children.toList().joinAll()}}
            f.close();assertEquals(f.httpCalls.get(),f.responseCloses.get())
        }
    }
    private suspend fun state(vm: ChatViewModel,predicate:(ChatUiState)->Boolean)=withContext(Dispatchers.Default){withTimeout(5000){vm.uiState.first(predicate)}}

    @Test fun wsFirstLateRestPreservesCurrentLiveChanges() = runBlocking(main.testDispatcher) { scenario { f ->
        f.active=true;f.holdBody=true
        val vm=f.vm();f.await(f.bodyEntered)
        state(vm){it.isStreaming&&it.runtimeSessionId=="synthetic-runtime"}
        // Attachment is published before replay admission finishes; await the actual seed result.
        f.await(f.seedFinished)
        f.delivered(vm,"message.delta"," suffix")
        state(vm){it.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic partial suffix"}}
        val reason=async(start=CoroutineStart.UNDISPATCHED){f.gateway.events.first{it is io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent.ReasoningDelta}}
        f.transport.emitTaggedFrame(f.gateway.connectionGeneration,"""{"jsonrpc":"2.0","method":"event","params":{"type":"reasoning.delta","session_id":"synthetic-runtime","payload":{"text":"Synthetic current reasoning"}}}""")
        f.await(reason);state(vm){it.timeline.any{row->row is ReasoningEntry}}
        f.releaseAll();f.joined(vm)
        assertTrue(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic stored answer"})
        assertEquals(1,vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().count{it.text=="Synthetic partial suffix"})
        assertTrue(vm.uiState.value.timeline.any{it is ReasoningEntry})
        f.delivered(vm,"message.complete","Synthetic final","complete")
        state(vm){!it.isStreaming}
        assertEquals(1,vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().count{it.text=="Synthetic final"})
    } }

    @Test fun lateStoredBodyCannotUndoNewUsageModelReport() = runBlocking(main.testDispatcher) { scenario { f ->
        f.active=true;f.holdBody=true
        val vm=f.vm(io.github.anshuman2401.hermesmobile.data.context.DefaultContextUsageRepository(f.gateway,f.repository))
        f.await(f.bodyEntered);state(vm){it.isStreaming&&it.runtimeSessionId=="synthetic-runtime"}
        f.await(f.seedFinished)
        f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
            """{"jsonrpc":"2.0","method":"event","params":{"type":"session.usage","session_id":"synthetic-runtime","payload":{"usage":{"model":"Synthetic latest","context_used":20,"context_max":100,"context_percent":20,"context_estimated":false,"total":30}}}}""")
        state(vm){it.sessionModelName=="Synthetic latest"}
        f.releaseAll();f.joined(vm)
        assertEquals("Synthetic latest",vm.uiState.value.sessionModelName)
        assertTrue(vm.uiState.value.timeline.none{it is GenericGatewayActivity && it.eventType=="session.usage"})
    } }

    @Test fun laneFailuresAreIndependentAndNeverOverwriteAcceptedRest() = runBlocking(main.testDispatcher) {
        for((restFails,liveFails) in listOf(false to true,true to false,true to true)) scenario { f ->
            if(restFails) f.storedJson="{}"
            f.resumeError=liveFails
            val vm=f.vm();val result=f.joined(vm)
            assertFalse(result.isInitialLoading)
            if(!restFails) assertTrue(result.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic stored answer"})
            if(!liveFails) assertTrue(result.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic compact answer"})
            if(liveFails) {assertNull(result.runtimeSessionId);assertNotNull(result.submitBlockedReason)}
            assertEquals(1,f.httpCalls.get());assertEquals(1,f.resumeCalls.get())
            assertTrue(f.transport.sentFrames.none{it.contains("prompt.submit")})
        }
        scenario { f ->
            f.holdResume=true;val vm=f.vm()
            state(vm){it.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic stored answer"}}
            f.transport.respondError(f.await(f.resumeEntered),-32000,"Synthetic live failure")
            f.joined(vm)
            assertTrue(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic stored answer"})
            assertNotNull(vm.uiState.value.submitBlockedReason)
        }
    }

    @Test fun reopenAndDescendantBindingNeverReplayPromptAttempt() = runBlocking(main.testDispatcher) { scenario { f ->
        f.withLocalTurns=true
        val pending=f.ledger.prepareTurn("synthetic-session","Synthetic pending question","synthetic-runtime")
        f.ledger.markWriteAttempted(pending.pendingTurnId);f.ledger.markUnknown(pending.pendingTurnId)
        f.resolvedId="synthetic-child";f.active=true
        val vm=f.vm();f.loaded(vm)
        assertEquals("synthetic-child",vm.uiState.value.durableSessionId)
        val before=f.vault.getAllPendingTurns()
        vm.loadSession();f.loaded(vm)
        assertEquals(before,f.vault.getAllPendingTurns())
        assertEquals(io.github.anshuman2401.hermesmobile.data.local.PendingTurnState.UNKNOWN,f.ledger.getPendingTurn(pending.pendingTurnId)!!.state)
        assertEquals(0,f.transport.sentFrames.count{it.contains("prompt.submit")})
        assertNull(vm.uiState.value.trace)
    } }

    @Test fun activeUnionUsesInheritedBoundsAndAtomicFailure() = runBlocking(main.testDispatcher) { scenario { f ->
        f.active=true;val vm=f.vm();f.loaded(vm)
        val bodyRows=(1..120).map { id -> """{"id":$id,"session_id":"synthetic-session","role":"assistant","content":"${"z".repeat(15000)}","reasoning":"${"r".repeat(10000)}"}""" }
        f.storedJson="""{"session_id":"synthetic-session","profile":"default","messages":[${bodyRows.joinToString(",")}],"pagination":{"limit":120,"offset":0,"order":"latest","returned":120}}"""
        val rpc=CompletableDeferred<String>()
        f.transport.setRpcHandler("session.resume"){id,_ ->rpc.complete(id)}
        val page=CompletableDeferred<io.github.anshuman2401.hermesmobile.data.history.StoredHistoryCandidate>()
        f.decodedStoredHook={page.complete(it.getOrThrow())}
        vm.loadSession();f.await(rpc);f.await(page)
        val admittedRest=state(vm){it.timeline.filterIsInstance<AssistantMessage>().count{row->row.id.startsWith("stored-")}==120}
        assertEquals(120, admittedRest.timeline.filterIsInstance<AssistantMessage>().count { it.id.startsWith("stored-") })
        f.delivered(vm, "message.delta", "d".repeat(600000))
        val retained = state(vm) { it.timeline.filterIsInstance<AssistantMessage>().any { row -> row.text.endsWith("d".repeat(600000)) } }
        f.delivered(vm, "message.delta", "e".repeat(800000))
        state(vm) { it.errorMessage == "Stored history limit reached. Reopen to load the latest history." }
        assertEquals(retained.timeline, vm.uiState.value.timeline)
        val correction="x".repeat(7000)
        f.resumeJson=f.resumeBody().replace("\"streaming\":true","\"streaming\":true,\"corrections\":[${List(256){"\"$correction\""}.joinToString(",")}] ")
        assertTrue(f.resumeBody().toByteArray().size<2*1024*1024)
        f.reply(rpc.getCompleted(),f.resumeBody())
        f.joined(vm)
        assertEquals(retained.timeline,vm.uiState.value.timeline)
        assertEquals("Stored history limit reached. Reopen to load the latest history.",vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isInitialLoading)
        f.transport.setRpcHandler("session.resume"){id,_ ->f.reply(id,f.resumeBody())}
        val scope=f.scope(55);val budget=io.github.anshuman2401.hermesmobile.data.history.StoredHistoryBudget(scope)
        val candidate=(f.repository.openStoredHistory(scope,budget,{},{ })).isSuccess
        assertTrue(candidate)
        assertEquals(1,budget.httpAttempts)
        // Row ceiling at the existing hydrator boundary, not an impossible >120-row wire response.
        val row=io.github.anshuman2401.hermesmobile.data.history.StoredHistoryRow(1,"user",mapOf("content" to io.github.anshuman2401.hermesmobile.data.gateway.json.JsonString("Synthetic bounded")),32,false)
        try {StoredHistoryHydrator.hydrate(List(4097){row});fail("4096 canonical row cap must reject before publication")}
        catch(e:io.github.anshuman2401.hermesmobile.data.history.StoredHistoryException){assertEquals(io.github.anshuman2401.hermesmobile.data.history.StoredHistoryFailure.BUDGET,e.failure)}
    } }

    @Test fun offlineCompactAndComposerPhasesRetainProvenance() = runBlocking(main.testDispatcher) { scenario { f ->
        f.cache.replaceRemoteSnapshot("synthetic-session",io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult("synthetic-runtime","synthetic-session",1,
            listOf(io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage("assistant","Synthetic saved text"))),System.currentTimeMillis())
        f.gateway.disconnect()
        val vm=f.vm();f.joined(vm)
        assertNotNull(vm.uiState.value.savedSnapshotCapturedAtMillis)
        assertNull(vm.uiState.value.runtimeSessionId)
        assertFalse(vm.uiState.value.isComposerReady)
        assertEquals(0,f.httpCalls.get());assertEquals(0,f.resumeCalls.get());assertEquals(0,f.seedCalls.get())
        assertTrue(f.gateway.connect(f.origin.originUrl,io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket("synthetic-return-ticket",30)))
        vm.loadSession();f.loaded(vm)
        assertNull(vm.uiState.value.savedSnapshotCapturedAtMillis)
        assertNull(vm.uiState.value.submitBlockedReason)
        assertFalse(vm.uiState.value.isStreaming)
        assertTrue(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any{it.text=="Synthetic stored answer"})
        f.transport.setRpcHandler("session.resume"){id,params ->
            assertEquals("default",params["profile"]?.asStringOrNull());assertEquals("synthetic-session",params["session_id"]?.asStringOrNull())
            f.reply(id,f.resumeBody())
        }
        val refreshBefore=vm.uiState.value.isRefreshing
        vm.refreshSession();state(vm){!it.isRefreshing}
        assertEquals(refreshBefore,vm.uiState.value.isRefreshing)
        assertNull(vm.uiState.value.refreshErrorMessage)
    } }

}
