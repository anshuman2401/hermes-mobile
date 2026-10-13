package com.hermes.bot.feature.chat

import com.hermes.bot.data.session.S4bBaselineFixture
import com.hermes.bot.data.session.S4bBaselineFixture.HttpStep
import com.hermes.bot.data.session.S4bBaselineFixture.RpcStep
import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.gateway.fake.FakeGatewayFixtures
import com.hermes.bot.data.history.*
import com.hermes.bot.data.local.PendingTurnState
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryReconnectTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    private suspend fun scenario(block: suspend (S4bBaselineFixture) -> Unit) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try { f.initialize(); block(f) } finally { f.close() }
    }
    private suspend fun state(vm: ChatViewModel, predicate: (ChatUiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(5000) { vm.uiState.first(predicate) } }
    private suspend fun entered(f: S4bBaselineFixture, step: HttpStep) {
        val received = withContext(Dispatchers.Default) { withTimeoutOrNull(1500) { step.entered.await(); true } }
        assertEquals("S5 must admit the scoped stored read", true, received)
    }
    private fun cycle(f: S4bBaselineFixture, http: HttpStep = HttpStep(), resume: RpcStep = RpcStep(), seed: RpcStep = RpcStep()) {
        f.scripted = true; f.httpSteps.add(http); f.resumeSteps.add(resume); f.seedSteps.add(seed)
    }
    private suspend fun disconnect(f: S4bBaselineFixture, vm: ChatViewModel) {
        f.transport.emitClose(1006, "Synthetic disconnect")
        state(vm) { !it.pickerConnected }
    }
    private suspend fun reconnect(f: S4bBaselineFixture, vm: ChatViewModel) {
        assertTrue(f.gateway.connect(f.origin.originUrl, GatewayTicket("synthetic-ticket", 30)))
        state(vm) { it.pickerConnected }
    }
    private fun page(ids: List<Int>, limit: Int = 120, offset: Int = 0): String =
        """{"session_id":"synthetic-session","profile":"default","messages":[${ids.joinToString(",") { """{"id":$it,"session_id":"synthetic-session","role":"assistant","content":"Synthetic row $it"}""" }}],"pagination":{"limit":$limit,"offset":$offset,"order":"latest","returned":${ids.size}}}"""
    private fun tools(extraCall: Boolean = false): String = """{"session_id":"synthetic-session","profile":"default","messages":[
        {"id":1,"session_id":"synthetic-session","role":"user","content":"Synthetic stored question"},
        {"id":2,"session_id":"synthetic-session","role":"assistant","tool_calls":[{"id":"synthetic-call","function":{"name":"terminal","arguments":"synthetic input"}}${if (extraCall) ", {\"id\":\"synthetic-call\",\"function\":{\"name\":\"terminal\"}}" else ""}]},
        {"id":3,"session_id":"synthetic-session","role":"tool","tool_call_id":"synthetic-call","content":"Synthetic authoritative output"}],
        "pagination":{"limit":120,"offset":0,"order":"latest","returned":3}}"""
    private fun noWrites(f: S4bBaselineFixture) {
        assertEquals(0, f.transport.sentFrames.count { it.contains("\"method\":\"prompt.submit\"") })
        assertEquals(0, f.transport.sentFrames.count { it.contains("\"method\":\"session.interrupt\"") })
    }
    private suspend fun gap(f: S4bBaselineFixture, sequence: Long, session: String? = f.runtimeId) = coroutineScope {
        f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
            """{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"${f.runtimeId}","seq":1,"payload":{}}}""")
        f.gateway.ping()
        val decoded = async(start = CoroutineStart.UNDISPATCHED) { f.gateway.historyFallbackSignals.first() }
        f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":${session?.let { "\"$it\"" } ?: "null"},"seq":${sequence + 2},"payload":{"text":"Synthetic gap"}}}""")
        f.await(decoded)
    }
    private suspend fun tool(f: S4bBaselineFixture, vm: ChatViewModel, id: String = "synthetic-call") = coroutineScope {
        val decoded = async(start = CoroutineStart.UNDISPATCHED) { f.gateway.events.first { it is GatewayEvent.ToolComplete } }
        f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
            """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","session_id":"${f.runtimeId}","payload":{"tool_id":"$id","name":"terminal","result":"Synthetic live output"}}}""")
        f.await(decoded)
        // Ping is a transport FIFO milestone; UI assertions below await actual admission.
        f.gateway.ping()
    }

    @Test fun reconnectRunningStoredTurnKeepsOneUserBubbleInEitherReadOrder() = runBlocking(main.testDispatcher) {
        for (restFirst in listOf(true, false)) scenario { f ->
            val vm = f.vm(); f.loaded(vm)
            f.resumeJson = """{"session_id":"synthetic-runtime","stored_session_id":"synthetic-session","message_count":1,"messages":[{"role":"user","content":"Synthetic stored question"}],"running":true,"status":"running","turn_started_at":100,"inflight":{"user":"Synthetic stored question","assistant":"","streaming":true}}"""
            repeat(2) {
                disconnect(f, vm)
                val body = HttpStep(tools(), held = true)
                val resume = RpcStep(held = true)
                cycle(f, body, resume)
                reconnect(f, vm); entered(f, body)
                val id = f.await(resume.entered)
                if (restFirst) {
                    body.release.complete(Unit)
                    state(vm) { !it.isInitialLoading }
                    f.respond(resume, id, f.resumeBody())
                } else {
                    f.respond(resume, id, f.resumeBody())
                    state(vm) { it.isStreaming && !it.isInitialLoading }
                    body.release.complete(Unit)
                }
                val restored = f.loaded(vm)
                assertEquals(1, restored.timeline.filterIsInstance<UserMessage>().count { it.text == "Synthetic stored question" })
                assertEquals("stored-1", restored.timeline.filterIsInstance<UserMessage>().single { it.text == "Synthetic stored question" }.id)
                assertEquals(1, restored.timeline.filterIsInstance<ToolActivity>().size)
                assertEquals(1, restored.timeline.filterIsInstance<AssistantMessage>().count { it.state == AssistantMessageState.STREAMING })
                assertTrue(restored.isStreaming)
                noWrites(f)
            }
        }
    }

    @Test fun connectedEdgeRestoresDisconnectedCompletion() = runBlocking(main.testDispatcher) {
        for (restFirst in listOf(true, false)) scenario { f ->
            val vm = f.vm(); f.loaded(vm); vm.updateDraft("Synthetic retained draft")
            assertEquals(1, f.httpCalls.get())
            val retained = vm.uiState.value.timeline
            assertTrue(retained.isNotEmpty())
            disconnect(f, vm)
            val body = HttpStep(tools(), held = true); val resume = RpcStep(held = true)
            cycle(f, body, resume); f.runtimeId = "synthetic-runtime-new"
            reconnect(f, vm); entered(f, body); val id = f.await(resume.entered)
            assertEquals(retained, vm.uiState.value.timeline)
            assertTrue(vm.uiState.value.isInitialLoading)
            assertFalse(vm.uiState.value.showInitialLoading)
            assertTrue(vm.uiState.value.showHistoryRefresh)
            assertFalse(vm.uiState.value.isComposerReady)
            if (restFirst) {
                body.release.complete(Unit)
                state(vm) { it.timeline.any { row -> row is ToolActivity && row.storedSource != null } }
                f.respond(resume, id, f.resumeBody())
            } else {
                f.respond(resume, id, f.resumeBody())
                state(vm) { it.runtimeSessionId == f.runtimeId }
                body.release.complete(Unit)
            }
            val result = f.loaded(vm)
            assertEquals(1, result.timeline.filterIsInstance<ToolActivity>().size)
            assertEquals("Synthetic authoritative output", result.timeline.filterIsInstance<ToolActivity>().single().resultSummary)
            assertEquals("Synthetic retained draft", result.composerDraft)
            assertEquals(2, f.httpCalls.get()); assertEquals(2, f.resumeCalls.get()); assertEquals(2, f.seedCalls.get())
            f.event("message.complete", "Synthetic stale", "complete", "synthetic-runtime")
            f.gateway.ping(); assertFalse(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic stale" })
            noWrites(f)
        }
    }

    @Test fun edgeAndGapShareOneCurrentRead() = runBlocking(main.testDispatcher) { scenario { f ->
        val vm = f.vm(); f.loaded(vm)
        val seed = RpcStep(held = true); val body = HttpStep(held = true)
        cycle(f, body, seed = seed); gap(f, 2); entered(f, body); f.await(seed.entered)
        val first = historyJob(vm)
        body.release.complete(Unit); state(vm) { !it.isInitialLoading }
        assertTrue("REST paint is not operation terminality", first.isActive)
        // Scoped seeds hold real live frames; an accepted seed drains a real gap, not a seed failure.
        cycle(f); f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"${f.runtimeId}","seq":4,"payload":{"text":"Synthetic held gap"}}}""")
        f.respond(seed, f.await(seed.entered), """{"count":1,"epoch":"${f.gateway.currentReplayEpoch}","latest_seq":1,"truncated":false,"events":[{"type":"session.info","session_id":"${f.runtimeId}","seq":1,"payload":{}}]}""")
        val next = f.httpSteps.peek()
        if (next != null) entered(f, next)
        f.joined(vm)
        assertEquals(3, f.httpCalls.get()); assertEquals(3, f.resumeCalls.get())
        cycle(f); val later = f.httpSteps.peek(); gap(f, 6); entered(f, later); f.joined(vm)
        assertEquals(4, f.httpCalls.get())
        noWrites(f)
    } }

    @Test fun seedFailureMemoryStopsRecursiveRefresh() = runBlocking(main.testDispatcher) {
        for (kind in listOf("rpc", "truncated", "epoch", "count", "gap", "decode", "clean")) scenario { f ->
            val body = when (kind) {
                "truncated" -> f.seedBody().replace("\"truncated\":false", "\"truncated\":true")
                "epoch" -> f.seedBody().replace(f.gateway.currentReplayEpoch.orEmpty(), "synthetic-other-epoch")
                "count" -> f.seedBody().replace("\"count\":0", "\"count\":1")
                "gap" -> """{"count":1,"epoch":"${f.gateway.currentReplayEpoch}","latest_seq":3,"truncated":false,"events":[{"type":"message.delta","session_id":"${f.runtimeId}","seq":3,"payload":{"text":"Synthetic rejected"}}]}"""
                "decode" -> """{"count":1,"epoch":"${f.gateway.currentReplayEpoch}","latest_seq":1,"truncated":false,"events":[{}]}"""
                else -> f.seedBody()
            }
            val signals = java.util.concurrent.CopyOnWriteArrayList<HistoryFallbackSignal>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { f.gateway.historyFallbackSignals.collect { signals.add(it) } }
            cycle(f, seed = RpcStep(body, error = kind == "rpc"))
            if (kind !in listOf("truncated", "clean")) f.seedSteps.add(RpcStep(body, error = kind == "rpc"))
            val vm = f.vm(); f.loaded(vm)
            val expected = if (kind in listOf("truncated", "clean")) 1 else 2
            assertEquals("$kind seed attempts", expected, f.seedCalls.get())
            assertEquals("scoped seed failure must not recursively notify VM", 0, signals.size)
            cycle(f); f.runtimeId = "synthetic-rebound"; vm.loadSession(); f.loaded(vm)
            assertEquals(if (kind == "clean") 2 else expected, f.seedCalls.get())
            assertEquals(2, f.httpCalls.get())
            disconnect(f, vm)
            // New ready epoch resets durable failure memory; preserve the certified ready shape.
            val ready = FakeGatewayFixtures.READY_FRAME
            val epoch = f.gateway.currentReplayEpoch.orEmpty()
            f.transport.readyFrameOverride = ready.replace(epoch, "synthetic-fresh-epoch")
            val freshBody = HttpStep(); cycle(f, freshBody); reconnect(f, vm); entered(f, freshBody); f.loaded(vm)
            assertEquals(if (kind == "clean") 3 else expected + 1, f.seedCalls.get())
            assertFalse(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic rejected" })
            collector.cancelAndJoin(); noWrites(f)
        }
    }

    @Test fun identifiableToolCompletionIsFoldedOnce() = runBlocking(main.testDispatcher) {
        for (ambiguous in listOf(false, true)) scenario { f ->
            f.storedJson = tools(ambiguous)
            val vm = f.vm(); f.loaded(vm)
            tool(f, vm)
            if (ambiguous) state(vm) { it.timeline.filterIsInstance<ToolActivity>().size == 2 }
            else {
                tool(f, vm, "synthetic-barrier-call")
                state(vm) { it.timeline.filterIsInstance<ToolActivity>().any { row -> row.toolId == "synthetic-barrier-call" } }
                assertEquals("proved current stored occurrence is authoritative", 1, vm.uiState.value.timeline.filterIsInstance<ToolActivity>().count { it.toolId == "synthetic-call" })
            }
            assertEquals("Synthetic authoritative output", vm.uiState.value.timeline.filterIsInstance<ToolActivity>().first { it.storedSource != null }.resultSummary)
            tool(f, vm, "synthetic-other-call")
            state(vm) { it.timeline.filterIsInstance<ToolActivity>().any { row -> row.toolId == "synthetic-other-call" } }
            noWrites(f)
        }
        scenario { f ->
            f.storedJson = tools(); val vm = f.vm(); f.loaded(vm)
            f.active = true; f.resumeJson = f.resumeBody(); cycle(f, HttpStep(tools()))
            vm.loadSession(); f.loaded(vm); tool(f, vm)
            state(vm) { it.timeline.filterIsInstance<ToolActivity>().size == 2 }
            assertEquals("unattributed inflight user must retain live evidence", 2, vm.uiState.value.timeline.filterIsInstance<ToolActivity>().size)
        }
    }

    @Test fun refreshOverlapUsesDesktopBoundedGraft() = runBlocking(main.testDispatcher) {
        for (anchor in listOf(true, false)) scenario { f ->
            f.storedJson = page(listOf(10, 11)); val vm = f.vm(); f.loaded(vm)
            cycle(f, HttpStep(page((500..619).toList())))
            f.httpSteps.add(HttpStep(page((380..499).toList(), offset = 120)))
            f.httpSteps.add(HttpStep(page((260..379).toList(), offset = 240)))
            f.httpSteps.add(HttpStep(page((140..259).toList(), offset = 360)))
            f.httpSteps.add(HttpStep(page((if (anchor) 10..129 else 20..139).toList(), offset = 480)))
            vm.refreshSession(); f.loaded(vm)
            assertEquals(listOf(0, 120, 240, 360, 480), f.requests.drop(1).map { it.offset })
            assertEquals(2, f.resumeCalls.get()); assertEquals(2, f.seedCalls.get())
            val ids = vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().filter { it.id.startsWith("stored-") }.map { it.id }
            assertEquals(if (anchor) 600 else 120, ids.size)
            assertEquals(if (anchor) "stored-10" else "stored-500", ids.first())
            noWrites(f)
        }
        for (ending in listOf("short", "empty", "error", "cutoff")) scenario { f ->
            f.storedJson = page(listOf(100)); val vm = f.vm(); f.loaded(vm)
            cycle(f, HttpStep(page((500..619).toList())))
            f.httpSteps.add(when (ending) {
                "error" -> HttpStep(status = 503)
                "empty" -> HttpStep(page(emptyList(), offset = 120))
                "cutoff" -> HttpStep(page((1..99).toList() + (101..121).toList(), offset = 120))
                else -> HttpStep(page(listOf(200), offset = 120))
            })
            vm.refreshSession(); f.loaded(vm)
            assertEquals(3, f.httpCalls.get())
            assertEquals("stored-500", vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().first().id)
        }
    }

    @Test fun refreshBudgetAndAtomicAdmissionAreInherited() = runBlocking(main.testDispatcher) { scenario { f ->
        f.storedJson = page(listOf(10)); val vm = f.vm(); f.loaded(vm)
        cycle(f, HttpStep(failure = StoredHistoryFailure.OVERSIZE))
        f.httpSteps.add(HttpStep(page((100..129).toList(), 30)))
        f.httpSteps.add(HttpStep(failure = StoredHistoryFailure.OVERSIZE))
        f.httpSteps.add(HttpStep(page(listOf(99), 1, 30)))
        f.httpSteps.add(HttpStep(page(listOf(98), 1, 31)))
        f.httpSteps.add(HttpStep(page(listOf(97), 1, 32)))
        f.httpSteps.add(HttpStep(page(listOf(10), 1, 33)))
        vm.refreshSession(); f.loaded(vm)
        val requests = f.requests.drop(1)
        assertEquals(listOf(120, 30, 30, 1, 1, 1, 1), requests.map { it.limit })
        assertEquals(listOf(0, 0, 30, 30, 31, 32, 33), requests.map { it.offset })
        assertTrue(requests.zipWithNext().all { (a,b) -> b.timeout <= a.timeout })
        assertEquals(1, f.budgets.drop(1).distinct().size)
        val retained = vm.uiState.value.timeline
        cycle(f, HttpStep(failure = StoredHistoryFailure.DEPTH)); vm.refreshSession(); f.loaded(vm)
        assertTrue(vm.uiState.value.timeline.containsAll(retained))
        val scope = f.scope(99); var now = 0L; val budget = StoredHistoryBudget(scope) { now }
        f.bodyHook = { now = 31_000_000_000L }; f.httpSteps.add(HttpStep(page(listOf(7))))
        assertTrue(f.repository.fetchStoredHistoryPage(scope, budget, 0).isFailure)
        val count = f.httpCalls.get()
        assertTrue(f.repository.fetchStoredHistoryPage(scope, budget, 0).isFailure)
        assertEquals(count, f.httpCalls.get()); noWrites(f)
    } }

    @Test fun supersededRecoveryCannotPublish() = runBlocking(main.testDispatcher) { scenario { f ->
        val vm = f.vm(); f.loaded(vm)
        val old = HttpStep(page(listOf(90)), held = true, nonCooperative = true)
        cycle(f, old, RpcStep(held = true)); vm.loadSession(); entered(f, old); val job = historyJob(vm)
        cycle(f, HttpStep(page(listOf(91)))); vm.loadSession()
        f.loaded(vm); old.release.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(5000) { job.join() } }
        f.await(old.closed)
        assertTrue(job.isCancelled)
        assertFalse(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any { it.id == "stored-90" })
        assertTrue(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any { it.id == "stored-91" })
        val scope = f.scope(90); val budget = StoredHistoryBudget(scope)
        f.httpSteps.add(HttpStep(page(listOf(92)))); f.gateway.disconnect()
        assertTrue("exact REST must remain independent of the socket", f.repository.fetchStoredHistoryPage(scope, budget, 0).isSuccess)
        scope.invalidate(); val before = f.httpCalls.get()
        assertTrue(f.repository.fetchStoredHistoryPage(scope, budget, 0).isFailure)
        assertEquals(before, f.httpCalls.get()); noWrites(f)
    } }

    @Test fun manualRefreshAndFailurePreserveRecoveredSource() = runBlocking(main.testDispatcher) { scenario { f ->
        f.storedJson = tools(); val vm = f.vm(); f.loaded(vm); vm.updateDraft("Synthetic retained draft")
        val body = HttpStep(tools(), held = true); cycle(f, body)
        vm.refreshSession(); entered(f, body)
        assertTrue(vm.uiState.value.isRefreshing); assertFalse(vm.uiState.value.isInitialLoading)
        body.release.complete(Unit); f.joined(vm); state(vm) { !it.isRefreshing }
        assertEquals(1, vm.uiState.value.timeline.filterIsInstance<ToolActivity>().size)
        assertEquals("Synthetic retained draft", vm.uiState.value.composerDraft)
        cycle(f, HttpStep(status = 503), RpcStep(error = true)); vm.refreshSession(); f.joined(vm)
        state(vm) { !it.isRefreshing }
        assertNotNull(vm.uiState.value.refreshErrorMessage)
        assertEquals(1, vm.uiState.value.timeline.filterIsInstance<ToolActivity>().size)
        assertFalse(vm.uiState.value.isInitialLoading); noWrites(f)
    } }

    @Test fun unknownPromptNeverResendsDuringRecovery() = runBlocking(main.testDispatcher) { scenario { f ->
        f.withLocalTurns = true
        f.transport.setRpcHandler("prompt.submit") { id, _ -> f.transport.respondError(id, -32000, "Synthetic ambiguous submission") }
        f.coordinator.submitPrompt("synthetic-session", f.runtimeId, "Synthetic absent pending prompt")
        val pending = f.vault.getAllPendingTurns().single()
        assertEquals(PendingTurnState.UNKNOWN, pending.state)
        val vm = f.vm(); f.loaded(vm)
        disconnect(f, vm); val body = HttpStep(tools()); cycle(f, body); reconnect(f, vm); entered(f, body); f.loaded(vm)
        cycle(f); val later = f.httpSteps.peek(); gap(f, 2); entered(f, later); f.loaded(vm)
        assertEquals(1, f.transport.sentFrames.count { it.contains("\"method\":\"prompt.submit\"") })
        assertEquals(0, f.transport.sentFrames.count { it.contains("\"method\":\"session.interrupt\"") })
        assertEquals(PendingTurnState.UNKNOWN, f.ledger.getPendingTurn(pending.pendingTurnId)!!.state)
    } }

    @Test fun scopedRefreshMustRetainAnActuallyAdmittedLaterLiveOccurrence() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val vm = f.vm()
            f.awaitLoaded(vm)
            val stored = vm.uiState.value.timeline.filterIsInstance<ToolActivity>().first()
            f.transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"synthetic-runtime","payload":{"tool_id":"synthetic-terminal","name":"terminal","args":{"command":"Synthetic later command"}}}}""")
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.uiState.first { s ->
                s.timeline.filterIsInstance<ToolActivity>().any { it.storedSource == null && it.status == ToolStatus.RUNNING }
            } } }
            f.transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","session_id":"synthetic-runtime","payload":{"tool_id":"synthetic-terminal","name":"terminal","result":"Synthetic later result"}}}""")
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.uiState.first { s ->
                s.timeline.filterIsInstance<ToolActivity>().any { it.storedSource == null && it.resultSummary == "Synthetic later result" }
            } } }
            val live = vm.uiState.value.timeline.filterIsInstance<ToolActivity>().single { it.storedSource == null }
            assertFalse(vm.uiState.value.isStreaming)
            vm.refreshSession()
            f.awaitLoaded(vm)
            assertEquals("refresh really executed", 2, f.gets)
            assertFalse(vm.uiState.value.isRefreshing)
            assertNull(vm.uiState.value.refreshErrorMessage)
            assertEquals(stored, vm.uiState.value.timeline.first { it.id == stored.id })
            assertEquals("A later admitted/completed live occurrence must not be deleted merely because the old stored suffix reuses its ID", listOf(live),
                vm.uiState.value.timeline.filterIsInstance<ToolActivity>().filter { it.storedSource == null })
        }
    }

    private fun historyJob(vm: ChatViewModel): Job = ChatViewModel::class.java.getDeclaredField("historyLoadJob")
        .apply { isAccessible = true }.get(vm) as Job
}
