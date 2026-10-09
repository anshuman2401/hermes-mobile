package com.hermes.bot.feature.chat

import com.hermes.bot.data.session.S4bBaselineFixture
import com.hermes.bot.data.session.S4bBaselineFixture.HttpStep
import com.hermes.bot.data.session.S4bBaselineFixture.RpcStep
import com.hermes.bot.data.history.*
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoredHistoryPaginationTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()
    private suspend fun scenario(block: suspend (S4bBaselineFixture) -> Unit) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try { f.initialize(); block(f) } finally { f.close() }
    }
    private fun row(id: Int, content: String = "Synthetic row $id", role: String = "assistant") =
        """{"id":$id,"session_id":"synthetic-session","role":"$role","content":"$content"}"""
    private fun page(ids: List<Int>, limit: Int = 120, offset: Int = 0) = rawPage(ids.map { row(it, if (it % 19 == 0) "" else "Synthetic row $it") }, limit, offset)
    private fun rawPage(rows: List<String>, limit: Int, offset: Int) =
        """{"session_id":"synthetic-session","profile":"default","messages":[${rows.joinToString(",")}],"pagination":{"limit":$limit,"offset":$offset,"order":"latest","returned":${rows.size}}}"""
    private fun open(f: S4bBaselineFixture, body: String) {
        f.scripted = true; f.httpSteps.add(HttpStep(body)); f.resumeSteps.add(RpcStep()); f.seedSteps.add(RpcStep())
    }
    private suspend fun entered(step: HttpStep) {
        val actual = withContext(Dispatchers.Default) { withTimeoutOrNull(1500) { step.entered.await(); true } }
        assertEquals("Existing loadEarlier must dispatch a stored HTTP page", true, actual)
    }
    @Suppress("UNCHECKED_CAST")
    private fun rows(vm: ChatViewModel): List<StoredHistoryRow> = ChatViewModel::class.java.getDeclaredField("acceptedHistoryRows")
        .apply { isAccessible = true }.get(vm) as List<StoredHistoryRow>

    @Test fun olderGesturesUseWireCursorAndSingleFlight() = runBlocking(main.testDispatcher) {
        scenario { f ->
            open(f, page((241..360).toList())); val vm = f.vm(); f.loaded(vm)
            val rpc = f.transport.sentFrames.toList()
            fun privateSinkBytes() = temp.root.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(temp.root).path to java.util.Arrays.hashCode(it.readBytes())
            }
            val sinks = privateSinkBytes()
            val messages = f.repository.activeMessages.value
            val diagnostic = f.diagnostics.snapshot()
            val selection = f.vault.getSessionSelection()
            val active = f.repository.getActiveSession()
            val older = HttpStep(page((121..240).toList(), offset = 120), held = true)
            f.httpSteps.add(older); vm.loadEarlier(); entered(older)
            vm.loadEarlier(); assertEquals(2, f.requests.size)
            older.release.complete(Unit); f.joinedEarlier(vm); f.await(older.closed)
            f.httpSteps.add(HttpStep(page((1..120).toList(), offset = 240)))
            vm.loadEarlier(); f.joinedEarlier(vm)
            assertEquals(360, rows(vm).size)
            val before = vm.uiState.value.timeline
            f.httpSteps.add(HttpStep(page(emptyList(), offset = 360)))
            vm.loadEarlier(); f.joinedEarlier(vm)
            assertEquals(listOf(0, 120, 240, 360), f.requests.map { it.offset })
            assertEquals(before, vm.uiState.value.timeline)
            assertEquals(ChatUiState.HistoryBeginState.NON_EXHAUSTIVE, vm.uiState.value.historyBeginState)
            vm.loadEarlier(); assertEquals(4, f.requests.size)
            assertEquals(rpc, f.transport.sentFrames.toList())
            assertEquals(selection, f.vault.getSessionSelection())
            assertEquals(active, f.repository.getActiveSession())
            assertEquals(messages, f.repository.activeMessages.value)
            assertEquals(diagnostic, f.diagnostics.snapshot())
            assertEquals("Earlier reads must not write snapshot/cache/selection/trace sinks", sinks, privateSinkBytes())
        }
    }
    private suspend fun state(vm: ChatViewModel, predicate: (ChatUiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(5000) { vm.uiState.first(predicate) } }
    private fun job(vm: ChatViewModel) = ChatViewModel::class.java.getDeclaredField("earlierHistoryJob")
        .apply { isAccessible = true }.get(vm) as Job
    private fun tools(limit: Int = 120, offset: Int = 0): String = rawPage(listOf(
        row(121, "Synthetic stored user", "user"),
        """{"id":122,"session_id":"synthetic-session","role":"assistant","tool_calls":[{"id":"synthetic-call","function":{"name":"terminal","arguments":{}}}]}""",
        """{"id":123,"session_id":"synthetic-session","role":"tool","tool_call_id":"synthetic-call","content":"Synthetic output"}"""
    ) + (124..240).map { row(it) }, limit, offset)
    private suspend fun toolEvent(f: S4bBaselineFixture, vm: ChatViewModel, type: String) {
        f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
            """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"${f.runtimeId}","payload":{"tool_id":"synthetic-call","name":"terminal","args":{},"result":"Synthetic later output"}}}""")
        f.gateway.ping()
        state(vm) { it.timeline.filterIsInstance<ToolActivity>().any { tool -> tool.storedSource == null && tool.argsSummary != null &&
            (type == "tool.start" || tool.status == ToolStatus.COMPLETED) } }
    }

    @Test fun overlapKeepsDistinctEvidenceAndReassociatesOrphans() = runBlocking(main.testDispatcher) {
        scenario { f ->
            // A full newest page starts with a tool result whose stable call/user are in the older page.
            val orphan = """{"id":3,"session_id":"synthetic-session","role":"tool","tool_call_id":"synthetic-call","content":"Synthetic output"}"""
            open(f, rawPage(listOf(orphan, row(4, "same"), row(5, "same")) + (10..126).map { row(it) }, 120, 0))
            val vm = f.vm(); f.loaded(vm)
            assertTrue(vm.uiState.value.timeline.any { it.id.startsWith("stored-3") })
            val call = """{"id":2,"session_id":"synthetic-session","role":"assistant","tool_calls":[{"id":"synthetic-call","function":{"name":"terminal","arguments":{}}}]}"""
            f.httpSteps.add(HttpStep(rawPage(listOf(row(1, "Synthetic user", "user"), call, orphan, row(6)), 120, 120)))
            vm.loadEarlier(); val after = f.joinedEarlier(vm)
            assertEquals(listOf(1L, 2L, 3L, 6L, 4L, 5L), rows(vm).take(6).map { it.id })
            assertEquals(1, after.timeline.filterIsInstance<ToolActivity>().size)
            assertFalse(after.timeline.any { it.id == "stored-3" })
            assertEquals(2, after.timeline.filterIsInstance<AssistantMessage>().count { it.text == "same" })
            val tool = after.timeline.filterIsInstance<ToolActivity>().single()
            vm.toggleToolExpanded(tool.id)
            assertTrue(vm.uiState.value.timeline.filterIsInstance<ToolActivity>().single().isExpanded)
        }
    }

    @Test fun olderPublicationRetainsDraftAndConcurrentActiveSource() = runBlocking(main.testDispatcher) {
        scenario { f ->
            f.active = true; open(f, tools()); val vm = f.vm(); f.loaded(vm)
            vm.updateDraft("Synthetic retained draft")
            // Seed already-queued presentation state: older reads do not call attachment providers.
            @Suppress("UNCHECKED_CAST")
            val presentation = ChatViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
                .get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ChatUiState>
            presentation.value = presentation.value.copy(
                pendingImages = listOf(PendingImageAttachment("synthetic-image", "Synthetic photo", "synthetic-ref")),
                pendingFiles = listOf(PendingFileAttachment("synthetic-file-ref", "Synthetic file", 12)),
                sessionModelName = "Synthetic retained model")
            val stored = vm.uiState.value.timeline.filterIsInstance<ToolActivity>().single { it.storedSource != null }
            vm.toggleToolExpanded(stored.id)
            val older = HttpStep(page((1..120).toList(), offset = 120), held = true)
            f.httpSteps.add(older); vm.loadEarlier(); entered(older)
            f.delivered(vm, "message.delta", " Synthetic delta")
            state(vm) { it.timeline.filterIsInstance<AssistantMessage>().any { a -> a.text.contains("Synthetic delta") } }
            f.delivered(vm, "message.complete", "Synthetic completed active", "complete")
            state(vm) { !it.isStreaming && it.timeline.filterIsInstance<AssistantMessage>().any { a -> a.text == "Synthetic completed active" } }
            // This is a separate later occurrence after the persisted same-ID call.
            toolEvent(f, vm, "tool.start"); toolEvent(f, vm, "tool.complete")
            val before = vm.uiState.value
            older.release.complete(Unit); val after = f.joinedEarlier(vm); f.await(older.closed)
            assertEquals(before.composerDraft, after.composerDraft)
            assertEquals(before.pendingImages, after.pendingImages); assertEquals(before.pendingFiles, after.pendingFiles)
            assertEquals(before.runtimeSessionId, after.runtimeSessionId)
            assertEquals(before.submitBlockedReason, after.submitBlockedReason)
            assertEquals(before.sessionModelName, after.sessionModelName); assertEquals(before.pickerOptions, after.pickerOptions)
            assertFalse(after.isInitialLoading); assertFalse(after.isStreaming)
            assertTrue(after.timeline.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic completed active" })
            assertTrue(after.timeline.filterIsInstance<ToolActivity>().single { it.storedSource != null }.isExpanded)
            assertEquals(1, after.timeline.filterIsInstance<ToolActivity>().count { it.storedSource == null && it.argsSummary != null })
            assertEquals(1, f.resumeCalls.get()); assertEquals(1, f.seedCalls.get())
        }
    }

    @Test fun pageCyclesCarryStickyLimitAndOwnReceipt() = runBlocking(main.testDispatcher) {
        scenario { f ->
            fun rotate(value: String) = mapOf("Set-Cookie" to listOf("session=$value; Path=/"))
            open(f, page((31..60).toList(), 30))
            f.httpSteps.clear()
            f.httpSteps.add(HttpStep(failure = StoredHistoryFailure.OVERSIZE, responseHeaders = rotate("synthetic-first")))
            f.httpSteps.add(HttpStep(page((31..60).toList(), 30)))
            val heldResume = RpcStep(held = true); f.resumeSteps.clear(); f.resumeSteps.add(heldResume)
            val finalInitial = f.httpSteps.last(); val vm = f.vm()
            f.await(finalInitial.closed)
            val resumeId = f.await(heldResume.entered); f.respond(heldResume, resumeId, f.resumeBody())
            f.loaded(vm)
            f.httpSteps.add(HttpStep(failure = StoredHistoryFailure.OVERSIZE, responseHeaders = rotate("synthetic-second")))
            f.httpSteps.add(HttpStep(page(listOf(30), 1, 30)))
            vm.loadEarlier(); f.joinedEarlier(vm)
            val rotatedBadBody = HttpStep("{synthetic-malformed", responseHeaders = rotate("synthetic-third"), held = true)
            f.httpSteps.add(rotatedBadBody)
            vm.loadEarlier(); entered(rotatedBadBody)
            toolEvent(f, vm, "tool.start"); toolEvent(f, vm, "tool.complete")
            rotatedBadBody.release.complete(Unit); f.joinedEarlier(vm); f.await(rotatedBadBody.closed)
            assertEquals("synthetic-third", f.vault.getAuthCredential()!!.cookies["session"])
            assertEquals(1L, vm.uiState.value.olderHistoryRevision)
            f.httpSteps.add(HttpStep(page(listOf(29), 1, 31)))
            vm.loadEarlier(); f.joinedEarlier(vm)
            assertEquals(listOf(120, 30, 30, 1, 1, 1), f.requests.map { it.limit })
            assertEquals(listOf(0, 0, 30, 30, 31, 31), f.requests.map { it.offset })
            assertEquals(4, f.budgets.size)
            assertNotSame(f.budgets[0], f.budgets[1]); assertNotSame(f.budgets[1], f.budgets[2])
            assertEquals(listOf(2, 2, 1, 1), f.budgets.map { it.httpAttempts })
            assertEquals(listOf(1, 1, 0, 1), f.budgets.map { it.successfulPages })
            assertTrue(vm.uiState.value.timeline.filterIsInstance<ToolActivity>().any { it.storedSource == null && it.argsSummary != null })
            f.httpSteps.add(HttpStep(page(listOf(60), 1))); f.resumeSteps.add(RpcStep()); f.seedSteps.add(RpcStep())
            vm.refreshSession(); f.joined(vm)
            assertEquals(1, f.requests.last().limit)
            f.httpSteps.add(HttpStep(page((31..60).toList()))); f.resumeSteps.add(RpcStep()); f.seedSteps.add(RpcStep())
            vm.loadSession(); f.joined(vm)
            assertEquals(120, f.requests.last().limit)
        }
    }

    @Test fun failedPageKeepsWindowAndRetryOffset() = runBlocking(main.testDispatcher) {
        for (failure in listOf(StoredHistoryFailure.UNAVAILABLE, StoredHistoryFailure.INTEGRITY,
            StoredHistoryFailure.DEPTH, StoredHistoryFailure.OVERSIZE, StoredHistoryFailure.DEADLINE)) scenario { f ->
            open(f, page((121..240).toList())); val vm = f.vm(); f.loaded(vm)
            val before = vm.uiState.value
            val attempts = if (failure == StoredHistoryFailure.OVERSIZE) 3 else 1
            repeat(attempts) { f.httpSteps.add(HttpStep(failure = failure, status = if (failure == StoredHistoryFailure.UNAVAILABLE) 503 else 200)) }
            vm.loadEarlier(); val failed = f.joinedEarlier(vm)
            assertEquals(before.timeline, failed.timeline); assertEquals(before.olderHistoryRevision, failed.olderHistoryRevision)
            assertFalse(failed.isLoadingEarlier)
            assertEquals(if (failure == StoredHistoryFailure.OVERSIZE || failure == StoredHistoryFailure.DEPTH)
                "Stored history exceeds this app's limit. Live activity may still appear."
                else "Stored history unavailable. Live activity may still appear.", failed.errorMessage)
            val limit = if (failure == StoredHistoryFailure.OVERSIZE) 1 else 120
            f.httpSteps.add(HttpStep(page(listOf(1), limit, 120)))
            vm.loadEarlier(); val retry = f.joinedEarlier(vm)
            assertEquals(120, f.requests.last().offset); assertEquals(limit, f.requests.last().limit)
            assertEquals(attempts + 2, f.requests.size); assertEquals(before.olderHistoryRevision + 1, retry.olderHistoryRevision)
        }
        for (stale in listOf(false, true)) scenario { f ->
            open(f, page((121..240).toList())); val vm = f.vm(); f.loaded(vm)
            val before = vm.uiState.value
            val headersGate = CompletableDeferred<Unit>()
            val denied = HttpStep(status = 401, headersGate = headersGate)
            f.httpSteps.add(denied); vm.loadEarlier(); f.await(denied.headersEntered)
            if (stale) f.vault.saveAuthCredential(com.hermes.bot.data.local.AuthCredential("cookie_session", "basic",
                mapOf("session" to "synthetic-new-login"), f.origin.originUrl))
            headersGate.complete(Unit); f.joinedEarlier(vm); f.await(denied.closed)
            assertEquals(before.timeline, vm.uiState.value.timeline)
            assertEquals(before.olderHistoryRevision, vm.uiState.value.olderHistoryRevision)
            assertEquals(before.errorMessage, vm.uiState.value.errorMessage)
            if (stale) assertEquals("synthetic-new-login", f.vault.getAuthCredential()!!.cookies["session"])
            else assertNull(f.vault.getAuthCredential())
        }
    }

    @Test fun retentionRejectsAtomicallyUntilFreshReopen() = runBlocking(main.testDispatcher) {
        scenario { f ->
            val text = "x".repeat(16000)
            fun heavy(ids: IntRange, offset: Int) = rawPage(ids.map {
                """{"id":$it,"session_id":"synthetic-session","role":"assistant","content":"$text","display_metadata":"$text"}"""
            }, 120, offset)
            open(f, heavy(121..240, 0)); val vm = f.vm(); f.loaded(vm)
            val before = vm.uiState.value; val canonical = rows(vm)
            val older = HttpStep(heavy(1..120, 120), held = true)
            f.httpSteps.add(older); vm.loadEarlier(); entered(older)
            assertEquals(canonical, rows(vm)); older.release.complete(Unit)
            val after = f.joinedEarlier(vm)
            assertEquals(canonical, rows(vm)); assertEquals(before.timeline, after.timeline)
            assertEquals(before.olderHistoryRevision, after.olderHistoryRevision)
            assertEquals(false, after.storedHistoryEarlierAvailable)
            assertEquals("Stored history limit reached. Reopen to load the latest history.", after.errorMessage)
            vm.loadEarlier(); assertEquals(2, f.requests.size)
            f.httpSteps.add(HttpStep(page((121..240).toList()))); f.resumeSteps.add(RpcStep()); f.seedSteps.add(RpcStep())
            vm.refreshSession(); f.joined(vm); vm.loadEarlier(); assertEquals(3, f.requests.size)
            assertEquals("Stored history limit reached. Reopen to load the latest history.", vm.uiState.value.errorMessage)
            f.httpSteps.add(HttpStep(page((121..240).toList()))); f.resumeSteps.add(RpcStep()); f.seedSteps.add(RpcStep())
            vm.loadSession(); f.joined(vm)
            assertEquals(true, vm.uiState.value.storedHistoryEarlierAvailable)
            f.httpSteps.add(HttpStep(page(listOf(1), offset = 120))); vm.loadEarlier(); f.joinedEarlier(vm)
            assertEquals(5, f.requests.size)
        }
    }

    @Test fun tailReplacementAndOwnerChangesDiscardEarlierCompletion() = runBlocking(main.testDispatcher) {
        for (mode in listOf("tail", "context", "invalidate", "dispose")) scenario { f ->
            open(f, page((121..240).toList())); val vm = f.vm(); f.loaded(vm)
            val held = HttpStep(page((1..120).toList(), offset = 120), held = true, nonCooperative = true)
            f.httpSteps.add(held); vm.loadEarlier(); entered(held); val oldJob = job(vm)
            if (mode == "tail") {
                val newest = HttpStep(page((241..360).toList()), held = true)
                val seed = RpcStep(held = true)
                f.httpSteps.add(newest); f.resumeSteps.add(RpcStep()); f.seedSteps.add(seed)
                vm.loadSession(); entered(newest); newest.release.complete(Unit)
                state(vm) { rows(vm).any { row -> row.id == 241L } }
                val calls = f.requests.size; vm.loadEarlier(); assertEquals(calls, f.requests.size)
                val seedId = f.await(seed.entered); f.respond(seed, seedId, f.seedBody()); f.joined(vm)
            } else if (mode == "context") f.vault.saveSelectedContextProfile("synthetic-other")
            else if (mode == "invalidate") {
                val scope = ChatViewModel::class.java.getDeclaredField("storedHistoryScope").apply { isAccessible = true }.get(vm) as StoredHistoryScope
                scope.invalidate()
            } else f.close()
            val before = vm.uiState.value; val canonical = rows(vm)
            held.release.complete(Unit); withContext(Dispatchers.Default) { withTimeout(5000) { oldJob.join() } }; f.await(held.closed)
            assertEquals(before, vm.uiState.value); assertEquals(canonical, rows(vm))
            if (mode == "context") { f.vault.saveSelectedContextProfile("default"); vm.loadEarlier(); assertEquals(2, f.requests.size) }
        }
        for (emptyNewest in listOf(true, false)) scenario { f ->
            open(f, page((121..240).toList())); val vm = f.vm(); f.loaded(vm)
            f.httpSteps.add(if (emptyNewest) HttpStep(page(emptyList())) else HttpStep(status = 503))
            f.resumeSteps.add(RpcStep()); f.seedSteps.add(RpcStep())
            vm.refreshSession(); f.joined(vm)
            assertEquals((121L..240L).toList(), rows(vm).map { it.id })
            assertEquals(true, vm.uiState.value.storedHistoryEarlierAvailable)
            f.httpSteps.add(HttpStep(page(listOf(1), offset = 120)))
            vm.loadEarlier(); f.joinedEarlier(vm)
            assertEquals(120, f.requests.last().offset)
            assertEquals(1L, rows(vm).first().id)
        }
    }

    @Test fun exactOfflineAndUnboundDescendantReadsFailClosed() = runBlocking(main.testDispatcher) {
        for (descendant in listOf(false, true)) scenario { f ->
            open(f, page((121..240).toList())); val vm = f.vm(); f.loaded(vm)
            val before = vm.uiState.value.timeline
            if (!descendant) { f.transport.emitClose(1006, "Synthetic disconnected"); state(vm) { !it.pickerConnected } }
            val body = page(listOf(1), offset = 120).let { if (descendant) it.replace("synthetic-session", "synthetic-descendant") else it }
            f.httpSteps.add(HttpStep(body)); vm.loadEarlier(); f.joinedEarlier(vm)
            assertEquals(2, f.requests.size); assertEquals(1, f.resumeCalls.get())
            if (descendant) { assertEquals(before, vm.uiState.value.timeline); assertEquals(0L, vm.uiState.value.olderHistoryRevision) }
            else { assertEquals(1L, vm.uiState.value.olderHistoryRevision); assertEquals(1L, rows(vm).first().id) }
        }
        scenario { f ->
            open(f, "{synthetic-malformed"); val vm = f.vm(); f.joined(vm)
            vm.loadEarlier(); assertEquals(1, f.requests.size); assertEquals(1, f.resumeCalls.get())
        }
        scenario { f ->
            open(f, page((121..240).toList())); val vm = f.vm(); f.loaded(vm)
            @Suppress("UNCHECKED_CAST")
            val presentation = ChatViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
                .get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ChatUiState>
            presentation.value = presentation.value.copy(savedSnapshotCapturedAtMillis = 123L)
            vm.loadEarlier(); assertEquals(1, f.requests.size); assertEquals(1, f.resumeCalls.get())
            val newVm = ChatViewModel("new-session", sessionRepository = f.repository, gatewayProtocolAdapter = f.gateway, vaultStorage = f.vault)
            val store = androidx.lifecycle.ViewModelStore().apply { put("synthetic-new", newVm) }
            try { newVm.loadEarlier(); assertEquals(1, f.requests.size); assertEquals(1, f.resumeCalls.get()) }
            finally { store.clear() }
        }
    }

    @Test fun olderGestureNeverArmsHydrationFollow() = runBlocking(main.testDispatcher) {
        scenario { f ->
            // One-row accepted limit yields a short visible list pinned at both ends.
            open(f, page(listOf(2), 1)); f.httpSteps.clear()
            f.httpSteps.add(HttpStep(failure = StoredHistoryFailure.OVERSIZE)); f.httpSteps.add(HttpStep(failure = StoredHistoryFailure.OVERSIZE))
            f.httpSteps.add(HttpStep(page(listOf(2), 1)))
            val heldResume = RpcStep(held = true); f.resumeSteps.clear(); f.resumeSteps.add(heldResume)
            val finalInitial = f.httpSteps.last(); val vm = f.vm(); f.await(finalInitial.closed)
            val resumeId = f.await(heldResume.entered); f.respond(heldResume, resumeId, f.resumeBody())
            val before = f.loaded(vm)
            f.httpSteps.add(HttpStep(page(listOf(1), 1, 1))); vm.loadEarlier(); val after = f.joinedEarlier(vm)
            assertFalse(after.isInitialLoading); assertEquals(before.olderHistoryRevision + 1, after.olderHistoryRevision)
            val policy = TimelineFollowStateMachine(); policy.markHydrationStarted()
            assertEquals(FollowDecision.PRESERVE_VIEWPORT, policy.onTimelineChanged(true, true, after.olderHistoryRevision))
            assertEquals(FollowDecision.FOLLOW_LATEST, policy.onTimelineChanged(true, true, after.olderHistoryRevision))
        }
    }

}
