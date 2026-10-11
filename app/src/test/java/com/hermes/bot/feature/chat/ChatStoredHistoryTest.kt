package com.hermes.bot.feature.chat

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.auth.*
import com.hermes.bot.data.cache.VaultConversationCacheRepository
import com.hermes.bot.data.diagnostics.DiagnosticRing
import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.data.history.*
import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.DefaultSessionRepository
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoredHistoryTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun selectedRemoteChangesDuringFinalLeaseAwaitMustNotWriteSelection() = runBlocking(main.testDispatcher) {
        verifyMutation("remote")
    }

    @Test fun newLoginDuringFinalLeaseAwaitMustNotWriteSelection() = runBlocking(main.testDispatcher) {
        verifyMutation("login")
    }

    @Test fun workspaceResetABADuringFinalLeaseAwaitCancelsResumeWithoutPublication() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val terminal = CompletableDeferred<Unit>()
            val gated = object : VaultStorage by f.vault {
                override suspend fun saveSessionSelectionIfCurrent(selection: SessionSelection, expected: AuthCredentialLease, selectedContext: String?): Boolean {
                    entered.complete(Unit)
                    try {
                        withContext(NonCancellable) { release.await() }
                        currentCoroutineContext().ensureActive()
                        return f.vault.saveSessionSelectionIfCurrent(selection, expected, selectedContext)
                    } finally { terminal.complete(Unit) }
                }
            }
            val repo = DefaultSessionRepository(f.gateway, gated,
                storedHistoryAdapter = object : StoredHistoryProtocolAdapter {
                    override suspend fun fetchPage(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int): Result<StoredHistoryPage> = error("cancelled resume must not fetch")
                })
            val owner = com.hermes.bot.navigation.ChatRouteOwnerViewModel { f.vm(gated, repo) }
            val context = com.hermes.bot.data.context.DefaultContextProfileRepository(f.vault)
            val vm = owner.get("synthetic-session")!!
            val job = vm.viewModelScope.coroutineContext[Job]!!
            try {
                withTimeout(5000) { entered.await() }
                val before = f.vault.readAuthCredentialLease(f.origin.originUrl)!!
                com.hermes.bot.navigation.performWorkspaceSelection("synthetic-other", context, owner) {}
                com.hermes.bot.navigation.performWorkspaceSelection("default", context, owner) {}
                release.complete(Unit)
                withTimeout(5000) { terminal.await() }
                assertTrue("reset must synchronously cancel the actual VM scope", job.isCancelled)
                withTimeout(5000) { job.join() }
                assertTrue(owner.activeKeys().isEmpty())
                assertEquals(before.epoch, f.vault.readAuthCredentialLease(f.origin.originUrl)!!.epoch)
                assertNull(f.vault.getSessionSelection())
                assertNull(repo.activeSession.value)
                assertTrue(repo.activeMessages.value.isEmpty())
                assertTrue(f.transport.sentFrames.none { it.contains("session.events.since") })
                assertTrue(f.diagnostics.snapshot().none { it.toString().contains("ResumeOutcome") })
            } finally {
                release.complete(Unit)
                owner.activeKeys().forEach(owner::clear)
                withTimeout(5000) { job.join() }
            }
        }
    }

    private suspend fun verifyMutation(mutation: String) = coroutineScope {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val gatedVault = object : VaultStorage by f.vault {
                override suspend fun readAuthCredentialLease(origin: String): AuthCredentialLease? {
                    val actual = f.vault.readAuthCredentialLease(origin)
                    if (++reads == 4) { entered.complete(Unit); release.await() }
                    return actual
                }
            }
            val repository = DefaultSessionRepository(f.gateway, gatedVault,
                storedHistoryAdapter = object : StoredHistoryProtocolAdapter {
                    override suspend fun fetchPage(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int): Result<StoredHistoryPage> =
                        error("resume must not fetch a stored page")
                })
            val scope = repository.captureStoredHistoryScope("synthetic-local", f.origin, "default", "synthetic-session", 11, 17).getOrThrow()
            val pending = async { repository.resumeStoredHistory(scope) }
            entered.await()
            if (mutation.startsWith("remote")) {
                val before = f.vault.readAuthCredentialLease(f.origin.originUrl)!!
                f.vault.saveSelectedContextProfile("synthetic-other")
                if (mutation == "remote-aba") {
                    f.vault.saveSelectedContextProfile("default")
                    val after = f.vault.readAuthCredentialLease(f.origin.originUrl)!!
                    assertEquals(before.epoch, after.epoch)
                    assertEquals(before.credential, after.credential)
                }
            } else f.vault.saveAuthCredential(f.credential("synthetic-new-login"))
            release.complete(Unit)
            val result = pending.await()
            assertEquals(StoredHistoryFailure.STALE, (result.exceptionOrNull() as? StoredHistoryException)?.failure)
            assertNull("stale scoped resume wrote selection after $mutation changed during final lease await; resultSuccess=${result.isSuccess}", f.vault.getSessionSelection())
            assertNull(repository.activeSession.value)
            assertTrue(repository.activeMessages.value.isEmpty())
            assertTrue(f.transport.sentFrames.none { it.contains("session.events.since") })
        }
    }

    @Test fun selectionCommitRejectsChangedOwnerAndPreservesSameOwner() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val lease = f.vault.readAuthCredentialLease(f.origin.originUrl)!!
            val selection = SessionSelection("synthetic-session", null, profileLocalId = "synthetic-local")
            assertTrue(f.vault.saveSessionSelectionIfCurrent(selection, lease, "default"))
            f.vault.clearSessionSelection()
            f.vault.saveSelectedContextProfile("synthetic-other")
            assertFalse(f.vault.saveSessionSelectionIfCurrent(selection, lease, "default"))
            f.vault.saveSelectedContextProfile("default")
            f.vault.saveAuthCredential(f.credential()) // Same cookie bytes, unrelated new epoch.
            assertFalse(f.vault.saveSessionSelectionIfCurrent(selection, lease, "default"))
            assertNull(f.vault.getSessionSelection())
        }
    }

    @Test fun realLegacyOmitsDefaultWhileScopedIncludesExactCapturedProfile() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            assertTrue(f.gateway.resumeSession("synthetic-session", " DEFAULT ") is GatewayResult.Success)
            assertFalse(f.resumeParams.last().map.containsKey("profile"))
            for (profile in listOf("default", "Synthetic-Exact")) {
                vaultProfile(f, profile)
                val scope = f.scope(profile)
                assertTrue(f.repository.resumeStoredHistory(scope).isSuccess)
                assertEquals(profile, f.resumeParams.last()["profile"]?.asStringOrNull())
            }
        }
    }

    private suspend fun vaultProfile(f: StoredChatFixture, profile: String) = f.vault.saveSelectedContextProfile(profile)

    @Test fun explicitIdleOpensEmptyCacheIntoExistingTurnOwnedActions() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val vm = f.vm()
            f.awaitLoaded(vm)
            assertEquals(1, f.gets)
            val tools = vm.uiState.value.timeline.filterIsInstance<ToolActivity>()
            assertEquals(listOf("terminal", "read_file", "browser"), tools.map { it.name })
            assertTrue(tools.all { it.argsSummary != null && it.resultSummary != null })
            val blocks = ToolBlockPolicy.buildBlockDisplayEntries(vm.uiState.value.timeline)
                .filterIsInstance<TraceDisplayEntry.BlockCard>()
            assertEquals(1, blocks.size)
            assertEquals("stored-9", blocks.single().block.turnId)
            assertEquals(3, blocks.single().block.steps.filterIsInstance<ToolBlockStep.Tool>().size)
            assertNull(vm.uiState.value.savedSnapshotCapturedAtMillis)
            assertFalse(vm.uiState.value.isStreaming)
        }
    }

    @Test fun laterLiveReuseCannotMutateAStoredCallOccurrence() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val vm = f.vm()
            f.awaitLoaded(vm)
            val stored = vm.uiState.value.timeline.filterIsInstance<ToolActivity>().first()
            val admitted = async(start = CoroutineStart.UNDISPATCHED) {
                f.gateway.events.first { it is GatewayEvent.ToolStart }
            }
            f.transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"synthetic-runtime","payload":{"tool_id":"synthetic-terminal","name":"terminal","args":{"command":"Synthetic later command"}}}}""")
            val event = withContext(Dispatchers.Default) { withTimeout(1000) { admitted.await() } }
            assertEquals(vm.uiState.value.runtimeSessionId, event.sessionId)
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.uiState.first { state ->
                state.timeline.filterIsInstance<ToolActivity>().any { it.storedSource == null && it.status == ToolStatus.RUNNING }
            } } }
            assertEquals(1, vm.uiState.value.timeline.filterIsInstance<ToolActivity>().count {
                it.storedSource == null && it.status == ToolStatus.RUNNING
            })
            val completed = async(start = CoroutineStart.UNDISPATCHED) {
                f.gateway.events.first { it is GatewayEvent.ToolComplete }
            }
            f.transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","session_id":"synthetic-runtime","payload":{"tool_id":"synthetic-terminal","name":"terminal","result":"Synthetic later result"}}}""")
            main.testDispatcher.scheduler.runCurrent()
            withContext(Dispatchers.Default) { withTimeout(1000) { completed.await() } }
            withContext(Dispatchers.Default) { withTimeout(5000) { vm.uiState.first { state ->
                state.timeline.filterIsInstance<ToolActivity>().any { it.storedSource == null && it.resultSummary == "Synthetic later result" }
            } } }
            assertEquals(stored, vm.uiState.value.timeline.first { it.id == stored.id })
            assertEquals("Synthetic later result", vm.uiState.value.timeline.filterIsInstance<ToolActivity>()
                .single { it.storedSource == null }.resultSummary)
        }
    }

    @Test fun realWireStrictEvidenceNeverHydratesUnknownMalformedContradictoryOrActive() = runBlocking(main.testDispatcher) {
        val metadata = listOf("", "\"status\":\"idle\"", "\"running\":false",
            "\"running\":null,\"status\":\"idle\"", "\"running\":\"false\",\"status\":\"idle\"",
            "\"running\":0,\"status\":\"idle\"", "\"running\":false,\"status\":null",
            "\"running\":false,\"status\":0", "\"running\":false,\"status\":\"unknown\"",
            "\"running\":false,\"status\":\"running\"", "\"running\":true,\"status\":\"idle\"")
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            for (fields in metadata) {
                f.metadata = fields
                val parsed = (f.gateway.resumeSession("synthetic-session") as GatewayResult.Success).value
                assertNotEquals(ResumeActivityEvidence.NON_RUNNING, parsed.activityEvidence)
                // Legacy defaults remain exactly false/idle when fields are absent or invalid.
                if (fields.isEmpty()) { assertFalse(parsed.running); assertEquals("idle", parsed.status) }
                val vm = f.vm()
                f.awaitLoaded(vm)
                assertEquals(metadata.indexOf(fields) + 1, f.gets)
                assertTrue(vm.uiState.value.timeline.any { it is ToolActivity && it.storedSource != null })
                assertNotNull("UNKNOWN/RUNNING resume must not enable the composer", vm.uiState.value.submitBlockedReason)
                f.clearVMs()
            }
        }
    }

    @Test fun unavailableProductionKeyProviderClosesCaptureWithoutResumeOrStoredGet() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val unavailable = object : VaultStorage by f.vault {
                override suspend fun getActiveProfileId(): String? = throw KeyStoreUnavailableException("Synthetic unavailable")
            }
            val vm = f.vm(unavailable)
            f.awaitLoaded(vm)
            assertTrue(f.resumeParams.isEmpty())
            assertEquals(0, f.gets)
            assertNull(vm.uiState.value.runtimeSessionId)
            assertEquals("Unable to restore chat from storage.", vm.uiState.value.errorMessage)
        }
    }

    @Test fun unsupportedScopedResumeServesRealTextCacheWithoutLegacyResumeOrStoredTools() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            f.cache.replaceRemoteSnapshot("synthetic-session", SessionResumeResult("synthetic-runtime", "synthetic-session", 1,
                listOf(SessionMessage("assistant", "Synthetic saved text"))), System.currentTimeMillis())
            val unsupported = com.hermes.bot.data.session.FakeGatewayProtocolAdapter()
            val repository = DefaultSessionRepository(unsupported, f.vault,
                storedHistoryAdapter = object : StoredHistoryProtocolAdapter {
                    override suspend fun fetchPage(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int): Result<StoredHistoryPage> =
                        error("unsupported resume must never read stored tools")
                })
            val vm = ChatViewModel("synthetic-session", sessionRepository = repository,
                gatewayProtocolAdapter = unsupported, vaultStorage = f.vault, conversationCacheRepository = f.cache)
            val store = ViewModelStore().apply { put("synthetic", vm) }
            try {
                f.awaitLoaded(vm)
                assertTrue(unsupported.resumeCalls.isEmpty())
                assertNotNull(vm.uiState.value.savedSnapshotCapturedAtMillis)
                assertTrue(vm.uiState.value.timeline.none { it is ToolActivity && it.storedSource != null })
            } finally { store.clear() }
        }
    }

    @Test fun failedExplicitScopeCaptureNeverFallsBackToLegacyResume() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val unsupported = DefaultSessionRepository(f.gateway, f.vault)
            val vm = f.vm(repo = unsupported)
            f.awaitLoaded(vm)
            assertTrue("failed explicit scope capture dispatched legacy resume", f.resumeParams.isEmpty())
            assertEquals(0, f.gets)
            assertNull(f.vault.getSessionSelection())
            assertTrue(vm.uiState.value.timeline.isEmpty())
        }
    }

    @Test fun capturedRemoteProfileMustMatchSelectedContextBeforeDispatch() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val before = f.resumeParams.size
            assertTrue(f.repository.resumeStoredHistory(f.scope("synthetic-other")).isFailure)
            assertEquals(before, f.resumeParams.size)
            f.assertNoResumeEffects()
        }
    }

    @Test fun wrongTransportOriginOrGenerationNeverDispatchesScopedResume() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val count = f.resumeParams.size
            val wrong = CanonicalOrigin.parse("http://other.example.test:9711").getOrThrow()
            assertTrue(f.gateway.resumeSessionScoped("synthetic-session", "default", wrong, f.gateway.connectionGeneration) is GatewayResult.Failure)
            assertTrue(f.gateway.resumeSessionScoped("synthetic-session", "default", f.origin, f.gateway.connectionGeneration - 1) is GatewayResult.Failure)
            assertEquals(count, f.resumeParams.size)
        }
    }

    @Test fun socketGenerationCapturedBeforeResumeCannotBeReboundToNewSocket() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val scope = f.scope()
            f.gateway.disconnect()
            assertTrue(f.gateway.connect(f.origin.originUrl, GatewayTicket("synthetic-ticket", 30)))
            val count = f.resumeParams.size
            assertTrue(f.repository.resumeStoredHistory(scope).isFailure)
            assertEquals(count, f.resumeParams.size)
            f.assertNoResumeEffects()
        }
    }

    @Test fun remoteABAWithFreshScopeCannotAdmitOldSameIdsAfterAwait() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val entered = CompletableDeferred<String>()
            f.transport.setRpcHandler("session.resume") { id, _ -> entered.complete(id) }
            val old = f.scope()
            val pending = async { f.repository.resumeStoredHistory(old) }
            entered.await()
            f.vault.saveSelectedContextProfile("synthetic-other")
            f.vault.saveSelectedContextProfile("default")
            f.scope() // new request identity; equal session/profile names do not restore the old owner
            f.transport.respondSuccess(entered.await(), f.resumeBody())
            assertTrue(pending.await().isFailure)
            f.assertNoResumeEffects()
        }
    }

    @Test fun changedSocketAfterAwaitRejectsAdapterEvidenceAndRepositoryEffects() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val pendingId = CompletableDeferred<String>()
            f.transport.setRpcHandler("session.resume") { id, _ -> pendingId.complete(id) }
            val result = async { f.repository.resumeStoredHistory(f.scope()) }
            pendingId.await()
            f.gateway.disconnect()
            assertTrue(f.gateway.connect(f.origin.originUrl, GatewayTicket("synthetic-ticket", 30)))
            f.transport.respondSuccess(pendingId.await(), f.resumeBody())
            assertTrue(result.await().isFailure)
            f.assertNoResumeEffects()
        }
    }

    @Test fun staleLocalOwnerRemoteSelectionCredentialAndABARejectBeforeAnyPublication() = runBlocking(main.testDispatcher) {
        for (mutation in listOf("local", "remote", "origin", "login", "aba", "delete", "wipe", "disconnect", "invalidate")) {
            StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
                f.initialize()
                if (mutation == "delete") {
                    f.vault.saveConnectionProfile(ConnectionProfile("synthetic-spare", f.origin.originUrl, "Synthetic spare"))
                    f.vault.setActiveProfileId("synthetic-local")
                }
                val entered = CompletableDeferred<String>()
                f.transport.setRpcHandler("session.resume") { id, _ -> entered.complete(id) }
                val scope = f.scope()
                val pending = async { f.repository.resumeStoredHistory(scope) }
                entered.await()
                when (mutation) {
                    "local" -> {
                        f.vault.saveConnectionProfile(ConnectionProfile("synthetic-b", f.origin.originUrl, "Synthetic B"))
                        f.vault.setActiveProfileId("synthetic-b")
                    }
                    "remote" -> f.vault.saveSelectedContextProfile("synthetic-other")
                    "origin" -> f.vault.saveConnectionProfile(ConnectionProfile("synthetic-local", "http://other.example.test:9711", "Synthetic"))
                    "login" -> f.vault.saveAuthCredential(f.credential("synthetic-new"))
                    "aba" -> {
                        f.vault.saveConnectionProfile(ConnectionProfile("synthetic-b", f.origin.originUrl, "Synthetic B"))
                        f.vault.setActiveProfileId("synthetic-b")
                        f.vault.setActiveProfileId("synthetic-local")
                        f.vault.saveAuthCredential(f.credential("synthetic-new"))
                    }
                    "delete" -> assertTrue(f.vault.deleteConnectionProfile("synthetic-local"))
                    "wipe" -> f.vault.clearAll()
                    "disconnect" -> f.gateway.disconnect()
                    else -> scope.invalidate()
                }
                f.transport.respondSuccess(entered.await(), f.resumeBody())
                assertTrue("stale mutation: $mutation", pending.await().isFailure)
                f.assertNoResumeEffects()
            }
        }
    }

    @Test fun invalidationDuringFinalLeaseValidationCannotWriteSelection() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val gatedVault = object : VaultStorage by f.vault {
                override suspend fun readAuthCredentialLease(origin: String): AuthCredentialLease? {
                    val actual = f.vault.readAuthCredentialLease(origin)
                    if (++reads == 4) { entered.complete(Unit); release.await() }
                    return actual
                }
            }
            val repository = DefaultSessionRepository(f.gateway, gatedVault,
                storedHistoryAdapter = object : StoredHistoryProtocolAdapter {
                    override suspend fun fetchPage(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int): Result<StoredHistoryPage> =
                        error("resume must not fetch a stored page")
                })
            val scope = repository.captureStoredHistoryScope("synthetic-local", f.origin, "default", "synthetic-session", 11, 17).getOrThrow()
            val pending = async { repository.resumeStoredHistory(scope) }
            entered.await()
            scope.invalidate()
            release.complete(Unit)
            assertTrue(pending.await().isFailure)
            assertNull(f.vault.getSessionSelection())
            assertNull(repository.activeSession.value)
            assertTrue(repository.activeMessages.value.isEmpty())
            assertTrue(f.transport.sentFrames.none { it.contains("session.events.since") })
        }
    }

    @Test fun ownGetCookieSuccessorAcceptedButUnrelatedLoginDuringBodyRejected() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            f.cookie = true
            val scope = f.scope()
            assertTrue(f.repository.resumeStoredHistory(scope).isSuccess)
            val budget = StoredHistoryBudget(scope)
            val read = f.repository.fetchStoredHistoryPage(scope, budget)
            assertTrue("stored read failure category: ${(read.exceptionOrNull() as? StoredHistoryException)?.failure}", read.isSuccess)
            assertTrue(f.repository.isStoredHistoryCurrent(scope, budget))
            f.bodyGate = CompletableDeferred()
            f.bodyEntered = CompletableDeferred()
            val next = async { f.repository.fetchStoredHistoryPage(scope, budget) }
            f.bodyEntered!!.await()
            f.vault.saveAuthCredential(f.credential("synthetic-unrelated"))
            f.bodyGate!!.complete(Unit)
            assertTrue(next.await().isFailure)
            assertFalse(f.repository.isStoredHistoryCurrent(scope, budget))
        }
    }

    @Test fun disposedAndSupersededGetCannotPublishTimelineCacheOrLoading() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            f.bodyGate = CompletableDeferred(); f.bodyEntered = CompletableDeferred()
            f.nonCooperativeBody = true
            val vm = f.vm()
            f.bodyEntered!!.await()
            val before = vm.uiState.value
            f.clearVMs()
            f.bodyGate!!.complete(Unit)
            f.bodyReturned.await()
            main.testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(before, vm.uiState.value)
            assertTrue(f.cache.load("synthetic-session") !is com.hermes.bot.data.cache.ConversationCacheReadResult.Snapshot)
        }
    }

    @Test fun storedFailureRetainsCompactTextAndFixedNoticeWithoutPayloadLeak() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize(); f.body = "SYNTHETIC_PRIVATE_OUTPUT invalid json"
            val vm = f.vm(); f.awaitLoaded(vm)
            assertTrue(vm.uiState.value.timeline.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic compact answer" })
            assertEquals("Stored history unavailable. Live activity may still appear.", vm.uiState.value.errorMessage)
            assertFalse(f.diagnostics.snapshot().toString().contains("SYNTHETIC_PRIVATE_OUTPUT"))
        }
    }
}

/** Real adapter/repository/auth/encrypted cache with synthetic in-memory transport bodies. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StoredChatFixture(folder: File, private val dispatcher: CoroutineDispatcher) : AutoCloseable {
    val origin = CanonicalOrigin.parse("http://example.test:9711").getOrThrow()
    var serializedConversation = ""
        private set
    private val aes = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    private val cipher = object : VaultCipherEngine by aes {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
            val text = plaintext.toString(Charsets.UTF_8)
            if (text.contains("\"snapshot\":")) serializedConversation = text
            return aes.encrypt(plaintext, associatedData)
        }
    }
    val vault = EncryptedVault(folder, cipher, ioDispatcher = dispatcher)
    val diagnostics = DiagnosticRing()
    val transport = FakeGatewayTransport()
    val gateway = DefaultGatewayProtocolAdapter(transport, dispatcher, diagnosticRing = diagnostics)
    val resumeParams = ArrayList<JsonObject>()
    var metadata = "\"running\":false,\"status\":\"idle\""
    var body = storedBody()
    var cookie = false
    var cookieValue = "synthetic-rotated"
    var bodyGate: CompletableDeferred<Unit>? = null
    var bodyEntered: CompletableDeferred<Unit>? = null
    var nonCooperativeBody = false
    val bodyReturned = CompletableDeferred<Unit>()
    var gets = 0
    val cache = VaultConversationCacheRepository(vault)
    private val http = object : StoredHistoryHttpTransport {
        override fun open(origin: CanonicalOrigin, path: String, profile: String, limit: Int, offset: Int,
            headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<StoredHistoryResponse> {
            gets++
            check(profile == "default")
            return object : AuthenticatedHttpCall<StoredHistoryResponse> {
                override fun cancel() {}
                override suspend fun awaitHeaders(): StoredHistoryResponse = object : StoredHistoryResponse {
                    override val statusCode = 200
                    override val headers = if (cookie) mapOf("Set-Cookie" to listOf("session=$cookieValue; Path=/")) else emptyMap()
                    override suspend fun readBoundedBody(): ByteArray {
                        bodyEntered?.complete(Unit)
                        if (nonCooperativeBody) withContext(NonCancellable) { bodyGate?.await() }
                        else bodyGate?.await()
                        bodyReturned.complete(Unit)
                        return body.toByteArray()
                    }
                    override fun close() {}
                }
            }
        }
    }
    val repository = DefaultSessionRepository(gateway, vault, diagnosticRing = diagnostics,
        storedHistoryAdapter = DefaultStoredHistoryProtocolAdapter(DefaultAuthRepository(vault), http))
    private var store = ViewModelStore()
    private var vmCount = 0
    private val vmJobs = ArrayList<Job>()
    init {
        transport.setRpcHandler("session.resume") { id, params ->
            resumeParams.add(params); transport.respondSuccess(id, resumeBody())
        }
        transport.setRpcHandler("session.events.since") { id, _ ->
            transport.respondSuccess(id, GatewayJson.serialize(JsonObject(
                "count" to JsonNumber(0L), "epoch" to JsonString(gateway.currentReplayEpoch.orEmpty()),
                "latest_seq" to JsonNumber(0L), "truncated" to JsonBoolean(false), "events" to JsonArray())))
        }
    }
    fun credential(value: String = "synthetic-cookie") = AuthCredential("cookie_session", "basic", mapOf("session" to value), origin.originUrl)
    suspend fun initialize() {
        vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
        vault.saveAuthCredential(credential())
        vault.saveSelectedContextProfile("default")
        check(gateway.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)))
    }
    suspend fun scope(profile: String = "default") = repository.captureStoredHistoryScope("synthetic-local", origin,
        profile, "synthetic-session", 11, 17).getOrThrow()
    fun resumeBody() = """{"session_id":"synthetic-runtime","stored_session_id":"synthetic-session","message_count":3,"messages":[{"role":"user","content":"Synthetic compact question"},{"role":"tool","content":"SYNTHETIC_PRIVATE_COMPACT_OUTPUT","name":"terminal","context":"SYNTHETIC_PRIVATE_COMPACT_ARGUMENT"},{"role":"assistant","content":"Synthetic compact answer"}]${if (metadata.isEmpty()) "" else ",$metadata"}}"""
    fun vm(storage: VaultStorage = vault, repo: com.hermes.bot.data.session.SessionRepository = repository,
        cacheRepository: com.hermes.bot.data.cache.ConversationCacheRepository = cache,
        previewRepository: ChatPreviewRepository? = null): ChatViewModel = ChatViewModel("synthetic-session", sessionRepository = repo,
        gatewayProtocolAdapter = gateway, vaultStorage = storage, conversationCacheRepository = cacheRepository,
        chatPreviewRepository = previewRepository).also {
        vmJobs.add(it.viewModelScope.coroutineContext[Job]!!)
        store.put("synthetic-${vmCount++}", it)
    }
    suspend fun awaitLoaded(vm: ChatViewModel) {
        awaitHistoryRead(vm)
        withContext(Dispatchers.Default) { withTimeout(5000) {
            vm.uiState.first { !it.isInitialLoading }
        } }
    }
    suspend fun awaitHistoryRead(vm: ChatViewModel) {
        withContext(Dispatchers.Default) { withTimeout(5000) {
            val field = ChatViewModel::class.java.getDeclaredField("historyLoadJob").apply { isAccessible = true }
            (field.get(vm) as Job).join()
        } }
    }
    suspend fun assertNoResumeEffects() {
        assertNull(repository.activeSession.value)
        assertTrue(repository.activeMessages.value.isEmpty())
        assertNull(vault.getSessionSelection())
        assertTrue(transport.sentFrames.none { it.contains("session.events.since") })
        assertTrue(diagnostics.snapshot().none { it.toString().contains("ResumeOutcome") })
    }
    fun clearVMs() { store.clear(); store = ViewModelStore() }
    override fun close() {
        clearVMs()
        bodyGate?.complete(Unit)
        gateway.disconnect()
        // A Default-dispatched stored read can finish after cancellation; join before Main is replaced.
        runBlocking(dispatcher) { withTimeout(5000) { vmJobs.joinAll() } }
    }
    companion object {
        fun storedBody(): String = """{"session_id":"synthetic-session","profile":"default","messages":[
            {"id":9,"session_id":"synthetic-session","role":"user","content":"Synthetic stored question"},
            {"id":2,"session_id":"synthetic-session","role":"assistant","content":"","tool_calls":[
                {"id":"synthetic-terminal","function":{"name":"terminal","arguments":{"command":"SYNTHETIC_PRIVATE_ARGUMENT"}}},
                {"id":"synthetic-file","function":{"name":"read_file","arguments":{"path":"/synthetic/file"}}},
                {"id":"synthetic-browser","function":{"name":"browser","arguments":{"url":"https://example.test"}}}]},
            {"id":3,"session_id":"synthetic-session","role":"tool","tool_call_id":"synthetic-terminal","content":"SYNTHETIC_PRIVATE_OUTPUT"},
            {"id":4,"session_id":"synthetic-session","role":"tool","tool_call_id":"synthetic-file","content":false},
            {"id":5,"session_id":"synthetic-session","role":"tool","tool_call_id":"synthetic-browser","content":"<script>synthetic()</script>"},
            {"id":6,"session_id":"synthetic-session","role":"assistant","content":"Synthetic stored answer"}
            ],"pagination":{"limit":120,"offset":0,"order":"latest","returned":6}}"""
    }
}
