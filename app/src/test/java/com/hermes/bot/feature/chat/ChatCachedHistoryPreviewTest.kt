package com.hermes.bot.feature.chat

import com.hermes.bot.data.cache.*
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.local.AuthCredentialLease
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.hermes.bot.test.MainDispatcherRule

/** Real scoped repository and encrypted test vault, with synthetic host responses only. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatCachedHistoryPreviewTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    private suspend fun seed(f: StoredChatFixture) {
        assertEquals(ConversationCacheWriteResult.Written, f.cache.replaceRemoteSnapshot("synthetic-session",
            SessionResumeResult("synthetic-old-runtime", "synthetic-session", 2, listOf(
                SessionMessage("user", "Synthetic cached question"),
                SessionMessage("assistant", "Synthetic cached answer"))), System.currentTimeMillis()))
    }

    private fun holdHost(f: StoredChatFixture): CompletableDeferred<String> {
        f.bodyGate = CompletableDeferred()
        f.bodyEntered = CompletableDeferred()
        return CompletableDeferred<String>().also { entered ->
            f.transport.setRpcHandler("session.resume") { id, _ -> entered.complete(id) }
        }
    }

    private suspend fun <T> await(block: suspend () -> T): T = withContext(Dispatchers.Default) {
        withTimeout(5000) { block() }
    }

    @Test fun defaultSelectionSavesHistoryAndPreviewsItOnReopenWhileHostWaits() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            f.vault.clearSelectedContextProfile()
            val first = f.vm()
            f.awaitLoaded(first)
            assertTrue(f.cache.load("synthetic-session") is ConversationCacheReadResult.Snapshot)
            f.clearVMs()
            val resume = CompletableDeferred<String>()
            f.transport.setRpcHandler("session.resume") { id, _ -> resume.complete(id) }
            val reopened = f.vm()
            try {
                await { resume.await(); reopened.uiState.first { it.isCachedHistoryPreview } }
                assertEquals("Synthetic compact answer", reopened.uiState.value.timeline.filterIsInstance<AssistantMessage>().single().text)
                assertFalse(reopened.uiState.value.showInitialLoading)
                assertFalse(reopened.uiState.value.showSavedSnapshot)
                assertNull(ChatRestoreNoticePolicy.forState(reopened.uiState.value))
                assertNull(reopened.uiState.value.runtimeSessionId)
                assertFalse(reopened.uiState.value.isComposerReady)
                assertEquals(0, f.gets)
                f.transport.respondSuccess(resume.await(), f.resumeBody())
                f.awaitLoaded(reopened)
                assertFalse(reopened.uiState.value.isCachedHistoryPreview)
                assertEquals("synthetic-runtime", reopened.uiState.value.runtimeSessionId)
            } finally { f.clearVMs() }
        }
    }

    @Test fun selectedWorkspaceSavesHistoryAndPreviewsItAfterBackWhileBothHostReadsWait() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val first = f.vm()
            f.awaitLoaded(first)
            assertTrue(first.uiState.value.hasAcceptedStoredHistory)
            assertTrue(f.cache.load("synthetic-session") is ConversationCacheReadResult.Snapshot)
            // Back disposes the keyed chat owner; reopening constructs a fresh ViewModel.
            f.clearVMs()
            val resume = holdHost(f)
            val reopened = f.vm()
            try {
                await { resume.await(); f.bodyEntered!!.await(); reopened.uiState.first { it.isCachedHistoryPreview } }
                val preview = reopened.uiState.value
                assertEquals("Synthetic compact answer", preview.timeline.filterIsInstance<AssistantMessage>().single().text)
                assertFalse(preview.showInitialLoading)
                assertFalse(preview.showSavedSnapshot)
                assertNull(ChatRestoreNoticePolicy.forState(preview))
                assertNull(preview.runtimeSessionId)
                assertFalse(preview.isComposerReady)
                assertEquals(2, f.gets)
                reopened.updateDraft("Synthetic draft during refresh")
                assertFalse(reopened.uiState.value.isComposerReady)

                f.transport.respondSuccess(resume.await(), f.resumeBody())
                f.bodyGate!!.complete(Unit)
                f.awaitLoaded(reopened)
                val fresh = reopened.uiState.value
                assertFalse(fresh.isCachedHistoryPreview)
                assertNull(fresh.savedSnapshotCapturedAtMillis)
                assertEquals("synthetic-runtime", fresh.runtimeSessionId)
                assertTrue(fresh.timeline.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic stored answer" })
                assertEquals("Synthetic draft during refresh", fresh.composerDraft)
                assertTrue(fresh.isComposerReady)
            } finally { f.clearVMs() }
        }
    }

    @Test fun defaultSelectionRejectsLateCacheAfterWorkspaceLoginOrOwnerDisposal() = runBlocking(main.testDispatcher) {
        for (mutation in listOf("workspace", "login", "dispose")) {
            StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
                f.initialize(); f.vault.clearSelectedContextProfile(); seed(f)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val checked = CompletableDeferred<Unit>()
                val base = delayedCache(f, entered, release)
                val cache = object : ConversationCacheRepository by base {
                    override suspend fun isReadOwnerCurrent(owner: com.hermes.bot.data.local.WorkspaceRecoveryOwner): Boolean {
                        val current = f.cache.isReadOwnerCurrent(owner)
                        checked.complete(Unit)
                        return current
                    }
                }
                val resume = CompletableDeferred<String>()
                f.transport.setRpcHandler("session.resume") { id, _ -> resume.complete(id) }
                val vm = f.vm(cacheRepository = cache)
                try {
                    await { entered.await(); resume.await() }
                    when (mutation) {
                        "workspace" -> f.vault.saveSelectedContextProfile("synthetic-other")
                        "login" -> f.vault.saveAuthCredential(f.credential("synthetic-new-login"))
                        else -> f.clearVMs()
                    }
                    release.complete(Unit)
                    if (mutation == "dispose") f.awaitHistoryRead(vm) else await { checked.await() }
                    assertTrue(vm.uiState.value.timeline.isEmpty())
                    assertFalse(vm.uiState.value.isCachedHistoryPreview)
                    assertNull(vm.uiState.value.savedSnapshotCapturedAtMillis)
                } finally { release.complete(Unit); f.clearVMs() }
            }
        }
    }

    @Test fun defaultSelectionSlowCacheCannotReplaceHostHistoryOrDelayAttachment() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize(); f.vault.clearSelectedContextProfile(); seed(f)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<String>()
            f.transport.setRpcHandler("session.resume") { id, _ -> resume.complete(id) }
            val vm = f.vm(cacheRepository = delayedCache(f, entered, release))
            try {
                await { entered.await(); resume.await() }
                f.transport.respondSuccess(resume.await(), f.resumeBody())
                await { vm.uiState.first { it.runtimeSessionId == "synthetic-runtime" } }
                val before = vm.uiState.value.timeline
                release.complete(Unit)
                f.awaitLoaded(vm)
                assertEquals(before, vm.uiState.value.timeline)
                assertFalse(vm.uiState.value.isCachedHistoryPreview)
                assertNull(vm.uiState.value.savedSnapshotCapturedAtMillis)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun unreadableSelectionMustNotBecomeDefaultCachePreview() = runBlocking(main.testDispatcher) {
        val folder = temp.newFolder()
        StoredChatFixture(folder, main.testDispatcher).use { f ->
            f.initialize(); f.vault.clearSelectedContextProfile(); seed(f)
            java.io.File(folder, "context_profile_selection.enc").writeText("Synthetic damaged selection")
            val attempted = CompletableDeferred<Unit>()
            val cache = object : ConversationCacheRepository by f.cache {
                override suspend fun captureReadOwner(): com.hermes.bot.data.local.WorkspaceRecoveryOwner? {
                    val captured = f.cache.captureReadOwner()
                    assertNull(captured)
                    attempted.complete(Unit)
                    return captured
                }
            }
            val resume = CompletableDeferred<String>()
            f.transport.setRpcHandler("session.resume") { id, _ -> resume.complete(id) }
            val vm = f.vm(cacheRepository = cache)
            await { attempted.await(); resume.await() }
            assertTrue(vm.uiState.value.timeline.isEmpty())
            assertFalse(vm.uiState.value.isCachedHistoryPreview)
            f.transport.respondSuccess(resume.await(), f.resumeBody())
            f.awaitLoaded(vm)
            assertEquals("synthetic-runtime", vm.uiState.value.runtimeSessionId)
        }
    }

    @Test fun showsCachedMessagesWhileBothHostReadsWaitThenReplacesWithoutMerging() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize(); seed(f)
            val capture = (f.cache.load("synthetic-session") as ConversationCacheReadResult.Snapshot).value.capturedAtMillis
            val resume = holdHost(f)
            val vm = f.vm()
            await { resume.await(); f.bodyEntered!!.await(); vm.uiState.first { it.isCachedHistoryPreview } }
            val state = vm.uiState.value
            assertEquals("Synthetic cached answer", state.timeline.filterIsInstance<AssistantMessage>().single().text)
            assertTrue(state.isInitialLoading)
            assertFalse(state.showInitialLoading)
            assertFalse(state.showHistoryRefresh)
            assertFalse(state.showSavedSnapshot)
            assertNull(ChatRestoreNoticePolicy.forState(state))
            assertNull(state.runtimeSessionId)
            assertFalse(state.isComposerReady)
            vm.updateDraft("Synthetic pending draft")
            vm.submitPrompt()
            vm.loadEarlier()
            assertTrue(f.transport.sentFrames.none { it.contains("prompt.submit") })
            assertEquals(1, f.gets)
            assertEquals(capture, (f.cache.load("synthetic-session") as ConversationCacheReadResult.Snapshot).value.capturedAtMillis)

            f.transport.respondSuccess(resume.await(), f.resumeBody())
            f.bodyGate!!.complete(Unit)
            f.awaitLoaded(vm)
            val fresh = vm.uiState.value
            assertFalse(fresh.isCachedHistoryPreview)
            assertNull(fresh.savedSnapshotCapturedAtMillis)
            assertEquals("synthetic-runtime", fresh.runtimeSessionId)
            assertTrue(fresh.timeline.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic stored answer" })
            assertTrue(fresh.timeline.filterIsInstance<AssistantMessage>().none { it.text == "Synthetic cached answer" })
            assertEquals("Synthetic pending draft", fresh.composerDraft)
            assertTrue(fresh.isComposerReady)
        }
    }

    @Test fun cacheMissAndReadFailureKeepHostLoadingWithoutAdditionalHostRequests() = runBlocking(main.testDispatcher) {
        for (throws in listOf(false, true)) {
            StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
                f.initialize()
                val resume = holdHost(f)
                val attempted = CompletableDeferred<Unit>()
                val cache = object : ConversationCacheRepository by f.cache {
                    override suspend fun loadScoped(storedSessionId: String, expected: AuthCredentialLease,
                        selectedContext: String?): ConversationCacheReadResult {
                        attempted.complete(Unit)
                        if (throws) throw IllegalStateException("Synthetic unreadable cache")
                        return f.cache.loadScoped(storedSessionId, expected, selectedContext)
                    }
                }
                val vm = f.vm(cacheRepository = cache)
                await { attempted.await(); resume.await(); f.bodyEntered!!.await() }
                assertFalse(vm.uiState.value.isCachedHistoryPreview)
                assertTrue(vm.uiState.value.showInitialLoading)
                f.transport.respondSuccess(resume.await(), f.resumeBody())
                f.bodyGate!!.complete(Unit)
                f.awaitLoaded(vm)
                assertTrue(vm.uiState.value.hasAcceptedStoredHistory)
                assertEquals(1, f.gets)
            }
        }
    }

    @Test fun storedRowsArrivingBeforeRuntimeDoNotShowRestoreSession() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize(); seed(f)
            val resume = holdHost(f)
            val vm = f.vm()
            await { resume.await(); vm.uiState.first { it.isCachedHistoryPreview } }
            f.bodyGate!!.complete(Unit)
            await { vm.uiState.first { it.hasAcceptedStoredHistory } }
            val state = vm.uiState.value
            assertTrue(state.isHistoryReadInFlight)
            assertNull(state.runtimeSessionId)
            assertEquals(SubmitBlockedReason.NO_RUNTIME, state.submitBlockedReason)
            assertNull(ChatRestoreNoticePolicy.forState(state))
            assertFalse(state.isCachedHistoryPreview)
            assertNull(state.savedSnapshotCapturedAtMillis)
            f.transport.respondSuccess(resume.await(), f.resumeBody())
            f.awaitLoaded(vm)
            assertFalse(vm.uiState.value.isHistoryReadInFlight)
            assertNull(ChatRestoreNoticePolicy.forState(vm.uiState.value))
        }
    }

    @Test fun slowCacheDoesNotDelayHostAndCannotOverwriteHostPublication() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize(); seed(f)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val resume = holdHost(f)
            val cache = delayedCache(f, entered, release)
            try {
                val vm = f.vm(cacheRepository = cache)
                await { entered.await(); resume.await(); f.bodyEntered!!.await() }
                f.transport.respondSuccess(resume.await(), f.resumeBody())
                f.bodyGate!!.complete(Unit)
                await { vm.uiState.first { it.hasAcceptedStoredHistory } }
                val before = vm.uiState.value.timeline
                release.complete(Unit)
                f.awaitLoaded(vm)
                assertEquals(before, vm.uiState.value.timeline)
                assertFalse(vm.uiState.value.isCachedHistoryPreview)
                assertNull(vm.uiState.value.savedSnapshotCapturedAtMillis)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun cacheAwaitRejectsWorkspaceOrLoginChangeAndDisposedOwner() = runBlocking(main.testDispatcher) {
        for (mutation in listOf("workspace", "login", "dispose")) {
            StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
                f.initialize(); seed(f)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                holdHost(f)
                try {
                    val vm = f.vm(cacheRepository = delayedCache(f, entered, release))
                    await { entered.await(); f.bodyEntered!!.await() }
                    when (mutation) {
                        "workspace" -> f.vault.saveSelectedContextProfile("synthetic-other")
                        "login" -> f.vault.saveAuthCredential(f.credential("synthetic-new-login"))
                        else -> f.clearVMs()
                    }
                    val before = vm.uiState.value.timeline
                    release.complete(Unit)
                    f.bodyGate!!.complete(Unit)
                    if (mutation != "dispose") f.gateway.disconnect()
                    f.awaitHistoryRead(vm)
                    assertEquals(before, vm.uiState.value.timeline)
                    assertFalse(vm.uiState.value.isCachedHistoryPreview)
                    assertNull(vm.uiState.value.savedSnapshotCapturedAtMillis)
                } finally { release.complete(Unit) }
            }
        }
    }

    @Test fun failedHostFallsBackWithoutRewritingCacheOrEnablingSending() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize(); seed(f)
            val before = (f.cache.load("synthetic-session") as ConversationCacheReadResult.Snapshot).value
            f.body = "Synthetic invalid stored response"
            val resume = holdHost(f)
            val vm = f.vm()
            await { vm.uiState.first { it.isCachedHistoryPreview }; resume.await() }
            f.transport.respondError(resume.await(), -32000, "Synthetic host unavailable")
            f.bodyGate!!.complete(Unit)
            f.awaitLoaded(vm)
            assertFalse(vm.uiState.value.isCachedHistoryPreview)
            assertTrue(vm.uiState.value.showSavedSnapshot)
            assertEquals("Synthetic cached answer", vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().single().text)
            assertFalse(vm.uiState.value.isComposerReady)
            assertNull(vm.uiState.value.runtimeSessionId)
            assertEquals(before, (f.cache.load("synthetic-session") as ConversationCacheReadResult.Snapshot).value)
        }
    }

    @Test fun failedStoredRefreshRetainsPreviouslyPublishedCompactAndStoredRows() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize(); seed(f)
            val resume = holdHost(f)
            val vm = f.vm()
            await { resume.await(); vm.uiState.first { it.isCachedHistoryPreview } }
            f.transport.respondSuccess(resume.await(), f.resumeBody())
            await { vm.uiState.first { it.runtimeSessionId == "synthetic-runtime" } }
            f.bodyGate!!.complete(Unit)
            f.awaitLoaded(vm)
            val before = vm.uiState.value.timeline
            assertTrue(before.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic compact answer" })
            assertTrue(before.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic stored answer" })
            f.body = "Synthetic invalid stored response"
            f.transport.setRpcHandler("session.resume") { id, _ -> f.transport.respondSuccess(id, f.resumeBody()) }
            vm.refreshSession()
            f.awaitLoaded(vm)
            // A compact tool duplicate may be removed by the existing stored-tool reconciliation.
            assertEquals(before.filter { it is UserMessage || it is AssistantMessage },
                vm.uiState.value.timeline.filter { it is UserMessage || it is AssistantMessage })
            assertEquals(before.filterIsInstance<ToolActivity>().filter { it.storedSource != null },
                vm.uiState.value.timeline.filterIsInstance<ToolActivity>().filter { it.storedSource != null })
            assertNotNull(vm.uiState.value.refreshErrorMessage)
            assertFalse(vm.uiState.value.isCachedHistoryPreview)
        }
    }

    private fun delayedCache(f: StoredChatFixture, entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) =
        object : ConversationCacheRepository by f.cache {
            override suspend fun loadScoped(storedSessionId: String, expected: AuthCredentialLease,
                selectedContext: String?): ConversationCacheReadResult {
                val cached = f.cache.loadScoped(storedSessionId, expected, selectedContext)
                entered.complete(Unit)
                // Deliberately ignores cancellation to exercise the post-await publication guard.
                withContext(NonCancellable) { release.await() }
                return cached
            }
        }
}
