package io.github.anshuman2401.hermesmobile.data.session

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.anshuman2401.hermesmobile.data.auth.*
import io.github.anshuman2401.hermesmobile.data.cache.VaultConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import io.github.anshuman2401.hermesmobile.data.history.*
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryScopeTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun ownHeaderSuccessorAuthorizesSelectionBeforeSlowBody() = runBlocking(main.testDispatcher) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        val read = CompletableDeferred<Result<StoredHistoryPage>>()
        val resumed = CompletableDeferred<Result<SessionResumeResult>>()
        var open: Deferred<Result<Unit>>? = null
        try {
            f.initialize()
            f.rotateCookie = true
            f.holdBody = true
            f.holdResume = true
            val scope = f.repository.captureStoredHistoryScope("synthetic-local", f.origin, "default", "synthetic-session", 11, 17).getOrThrow()
            val budget = StoredHistoryBudget(scope)
            open = async { f.repository.openStoredHistory(scope, budget, { read.complete(it) }, { resumed.complete(it) }) }
            val rpc = f.await(f.resumeEntered)
            f.await(f.bodyEntered)
            assertEquals(StoredHistoryHeaderState.ACCEPTED, budget.awaitHeaderDecision().state)
            assertTrue(budget.headerDecision.isComplete)
            assertNotEquals(scope.lease.epoch, budget.receipt.epoch)
            val committed = f.vault.readAuthCredentialLease(f.origin.originUrl)!!
            // AuthCredentialLease is an identity carrier, not a data class. Compare its owner values.
            assertEquals(budget.receipt.profileLocalId, committed.profileLocalId)
            assertEquals(budget.receipt.origin, committed.origin)
            assertEquals(budget.receipt.epoch, committed.epoch)
            assertEquals(budget.receipt.credential, committed.credential)
            assertFalse(read.isCompleted)
            assertEquals(0, f.bodyReturns.get())
            f.reply(rpc, f.resumeBody())
            val outcome = withContext(Dispatchers.Default) { withTimeout(5000) { resumed.await() } }
            assertTrue("O02: accepted own HTTP successor must authorize scoped selection before the blocked body returns; failure=${(outcome.exceptionOrNull() as? StoredHistoryException)?.failure}", outcome.isSuccess)
            assertEquals("synthetic-session", f.vault.getSessionSelection()?.storedSessionId)
            assertEquals("synthetic-runtime", f.repository.activeSession.value?.runtimeSessionId)
            assertEquals(0, f.bodyReturns.get())
        } finally {
            f.releaseAll()
            try {
                withContext(NonCancellable + Dispatchers.Default) {
                    withTimeout(5000) {
                        open?.await()?.getOrThrow()
                        assertTrue("released HTTP page must decode successfully", read.await().isSuccess)
                    }
                }
                assertEquals(1, f.httpCalls.get())
                assertEquals(1, f.bodyReturns.get())
                assertEquals(1, f.responseCloses.get())
            } finally { f.close() }
        }
    }
    private suspend fun scenario(block: suspend CoroutineScope.(S4bBaselineFixture) -> Unit) = coroutineScope {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try { f.initialize(); block(f) }
        finally {
            f.releaseAll()
            withContext(NonCancellable + Dispatchers.Default) { withTimeout(5000) {
                this@coroutineScope.coroutineContext[Job]!!.children.toList().joinAll()
            } }
            f.close()
            assertEquals("every real HTTP response has one terminal close", f.httpCalls.get(), f.responseCloses.get())
        }
    }
    private suspend fun noAttach(f: S4bBaselineFixture) {
        assertNull(f.vault.getSessionSelection())
        assertNull(f.repository.activeSession.value)
        assertTrue(f.repository.activeMessages.value.isEmpty())
        assertEquals(0, f.seedCalls.get())
        assertTrue(f.cache.load("synthetic-session") !is io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheReadResult.Snapshot)
        assertTrue(f.diagnostics.snapshot().isEmpty())
    }

    @Test fun restCurrentnessDoesNotRequireResumeOwnerOrSocket() = runBlocking(main.testDispatcher) { scenario { f ->
        f.holdResume = true
        val scope = f.scope(); val budget = StoredHistoryBudget(scope)
        val painted = CompletableDeferred<StoredHistoryPage>()
        val open = async { f.repository.openStoredHistory(scope, budget, { it.onSuccess(painted::complete) }, {}) }
        f.await(painted); f.await(f.resumeEntered)
        assertTrue(f.repository.isStoredHistoryPublicationCurrent(scope, budget))
        f.gateway.disconnect()
        assertTrue(f.gateway.connect(f.origin.originUrl, GatewayTicket("synthetic-fresh-ticket", 30)))
        assertTrue("REST publication does not borrow the replacement socket", f.repository.isStoredHistoryPublicationCurrent(scope, budget))
        f.releaseAll(); f.await(open)
        noAttach(f)
        f.vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-unrelated"), f.origin.originUrl))
        assertFalse(f.repository.isStoredHistoryPublicationCurrent(scope, budget))
    } }

    @Test fun unrelatedLoginAndLateAuthFailureCannotSalvageOldOpen() = runBlocking(main.testDispatcher) {
        for (mutation in listOf("login", "context", "origin", "signout")) scenario { f ->
            f.holdResume = true; f.holdBody = true; f.rotateCookie = true
            val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            val effects = AtomicInteger()
            val open = async { f.repository.openStoredHistory(scope, budget, { effects.incrementAndGet() }, { effects.incrementAndGet() }) }
            f.await(f.bodyEntered); f.await(f.resumeEntered)
            when (mutation) {
                "login" -> f.vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-new"), f.origin.originUrl))
                "context" -> f.vault.saveSelectedContextProfile("synthetic-other")
                "origin" -> f.vault.saveConnectionProfile(ConnectionProfile("synthetic-local", "http://other.example.test:9711", "Synthetic"))
                else -> f.vault.clearAuthCredential()
            }
            assertFalse(f.repository.isStoredHistoryPublicationCurrent(scope, budget))
            f.releaseAll(); f.await(open)
            assertEquals(0, effects.get())
            noAttach(f)
            if (mutation == "login") assertEquals("synthetic-new", f.vault.getAuthCredential()?.cookies?.get("session"))
        }
        for (status in listOf(401, 200)) scenario { f ->
            f.holdResume = true; f.statusCode = status; f.deletedCookie = status == 200
            f.headerGate = CompletableDeferred()
            val scope = f.scope(); val budget = StoredHistoryBudget(scope); val effects = AtomicInteger()
            val open = async { f.repository.openStoredHistory(scope, budget,
                { effects.incrementAndGet() }, { effects.incrementAndGet() }) }
            f.await(f.headerEntered); f.await(f.resumeEntered)
            f.vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-new"), f.origin.originUrl))
            f.headerGate!!.complete(Unit); f.releaseAll(); f.await(open)
            assertEquals(0, effects.get()); noAttach(f)
            assertEquals("synthetic-new", f.vault.getAuthCredential()?.cookies?.get("session"))
        }
    }

    @Test fun defaultProfileIsLiteralAndSocketBindingIsCaptured() = runBlocking(main.testDispatcher) { scenario { f ->
        f.holdResume = true
        val scope = f.scope(); val budget = StoredHistoryBudget(scope)
        val stored = CompletableDeferred<StoredHistoryPage>(); val live = AtomicInteger()
        val open = async { f.repository.openStoredHistory(scope, budget, { it.onSuccess(stored::complete) }, { live.incrementAndGet() }) }
        f.await(f.resumeEntered); f.await(stored)
        assertEquals("default", stored.getCompleted().scope.remoteProfile)
        val original = f.gateway.connectionGeneration
        f.gateway.disconnect(); assertTrue(f.gateway.connect(f.origin.originUrl, GatewayTicket("synthetic-replacement-ticket", 30)))
        assertNotEquals(original, f.gateway.connectionGeneration)
        f.releaseAll(); f.await(open)
        assertEquals(0, live.get()); noAttach(f)
        assertEquals(1, f.resumeCalls.get()); assertEquals(1, f.httpCalls.get())
    } }

    @Test fun restFirstDescendantIsFullyValidatedAndHeld() = runBlocking(main.testDispatcher) { scenario { f ->
        f.resolvedId = "synthetic-child"; f.holdResume = true
        val scope = f.scope(); val budget = StoredHistoryBudget(scope)
        val pages = ArrayList<StoredHistoryPage>(); val replies = ArrayList<SessionResumeResult>()
        val open = async { f.repository.openStoredHistory(scope, budget, { it.onSuccess(pages::add) }, { it.onSuccess(replies::add) }) }
        assertTrue(f.await(f.decodedStored).isSuccess); f.await(f.resumeEntered)
        assertTrue(pages.isEmpty()); assertTrue(replies.isEmpty()); noAttach(f)
        f.releaseAll(); f.await(open).getOrThrow()
        assertEquals("synthetic-child", pages.single().resolvedSessionId)
        assertEquals("synthetic-child", replies.single().observedDurableSessionId)
        assertEquals("synthetic-child", f.vault.getSessionSelection()?.storedSessionId)
        assertEquals(1, f.seedCalls.get())
    } }

    @Test fun wsFirstDescendantWaitsForMatchingRest() = runBlocking(main.testDispatcher) { scenario { f ->
        f.resolvedId = "synthetic-child"; f.holdBody = true
        val scope = f.scope(); val budget = StoredHistoryBudget(scope)
        val live = AtomicInteger(); val pages = AtomicInteger()
        val open = async { f.repository.openStoredHistory(scope, budget, { it.onSuccess { pages.incrementAndGet() } }, { it.onSuccess { live.incrementAndGet() } }) }
        f.await(f.bodyEntered); assertEquals("synthetic-child", f.await(f.decodedResume).observedDurableSessionId)
        assertEquals(0, live.get()); assertEquals(0, pages.get()); noAttach(f)
        f.releaseAll(); f.await(open).getOrThrow()
        assertEquals(1, live.get()); assertEquals(1, pages.get())
        assertEquals("synthetic-runtime", f.repository.activeSession.value?.runtimeSessionId)
    } }

    @Test fun descendantConflictMissingBindingOrLaneFailureHasNoEffects() = runBlocking(main.testDispatcher) {
        for (variant in listOf("conflict", "durable-absent", "runtime-absent", "invalid-page", "invalid-count", "invalid-row", "durable-blank", "durable-conflict", "resume-failure")) scenario { f ->
            f.resolvedId = "synthetic-child"
            when (variant) {
                "conflict" -> f.resumeJson = f.resumeBody().replace("synthetic-child", "synthetic-other-child")
                "durable-absent" -> f.resumeJson = f.resumeBody().replace("\"stored_session_id\":\"synthetic-child\",", "")
                "runtime-absent" -> f.resumeJson = f.resumeBody().replace("\"session_id\":\"synthetic-runtime\",", "")
                "invalid-page" -> f.storedJson = f.storedBody().replace("\"profile\":\"default\"", "\"profile\":\"synthetic-foreign\"")
                "invalid-count" -> f.storedJson = f.storedBody().replace("\"returned\":2", "\"returned\":1")
                "invalid-row" -> f.storedJson = f.storedBody().replace("\"id\":2", "\"id\":1")
                "durable-blank" -> f.resumeJson = f.resumeBody().replace("\"stored_session_id\":\"synthetic-child\"", "\"stored_session_id\":\"\"")
                "durable-conflict" -> f.resumeJson = f.resumeBody().replace("\"stored_session_id\":\"synthetic-child\"", "\"stored_session_id\":\"synthetic-child\",\"session_key\":\"synthetic-other-child\"")
                else -> f.resumeError = true
            }
            val admitted = AtomicInteger(); val scope = f.scope()
            f.repository.openStoredHistory(scope, StoredHistoryBudget(scope), { it.onSuccess { admitted.incrementAndGet() } }, { it.onSuccess { admitted.incrementAndGet() } }).getOrThrow()
            assertEquals(0, admitted.get()); noAttach(f)
            assertEquals(1, f.httpCalls.get()); assertEquals(1, f.resumeCalls.get())
        }
        val timed = S4bBaselineFixture(temp.newFolder(), main.testDispatcher, Dispatchers.Default, 1000)
        try {
            timed.initialize(); timed.resolvedId = "synthetic-child"; timed.holdResume = true
            val scope = timed.scope(); val effects = AtomicInteger()
            val completed = withContext(Dispatchers.Default) { withTimeout(5000) {
                timed.repository.openStoredHistory(scope, StoredHistoryBudget(scope),
                    { it.onSuccess { effects.incrementAndGet() } }, { it.onSuccess { effects.incrementAndGet() } })
            } }
            completed.getOrThrow()
            assertTrue(timed.resumeEntered.isCompleted); assertTrue(timed.decodedStored.isCompleted)
            assertEquals(0, effects.get()); noAttach(timed)
        } finally { timed.close(); assertEquals(timed.httpCalls.get(), timed.responseCloses.get()) }
    }

    @Test fun supersededDisposedAndWorkspaceABAJobsTerminateSilently() = runBlocking(main.testDispatcher) { scenario { f ->
        f.holdResume = true; f.holdBody = true; f.nonCooperativeBody = true
        val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel { f.vm() }
        val vm = owner.get("synthetic-session")!!
        f.await(f.bodyEntered); f.await(f.resumeEntered)
        val job = vm.viewModelScope.coroutineContext[Job]!!
        val before = f.vault.readAuthCredentialLease(f.origin.originUrl)!!.epoch
        val context = io.github.anshuman2401.hermesmobile.data.context.DefaultContextProfileRepository(f.vault)
        io.github.anshuman2401.hermesmobile.navigation.performWorkspaceSelection("synthetic-other", context, owner) {}
        io.github.anshuman2401.hermesmobile.navigation.performWorkspaceSelection("default", context, owner) {}
        assertTrue(job.isCancelled); f.releaseAll()
        withContext(Dispatchers.Default) { withTimeout(5000) { job.join() } }
        assertEquals(before, f.vault.readAuthCredentialLease(f.origin.originUrl)!!.epoch)
        assertTrue(owner.activeKeys().isEmpty()); noAttach(f)
        val fresh = owner.get("synthetic-session")!!
        f.loaded(fresh)
        assertEquals("synthetic-runtime", fresh.uiState.value.runtimeSessionId)
        owner.clear("synthetic-session")
        withContext(Dispatchers.Default) { withTimeout(5000) { fresh.viewModelScope.coroutineContext[Job]!!.join() } }
    } }

    @Test fun scopedSeedCannotPublishLateFailureOrCleanUpAnotherHold() = runBlocking(main.testDispatcher) { for (terminal in listOf("failure", "truncated", "valid")) scenario { f ->
        f.holdSeed = true
        val scope = f.scope(); val budget = StoredHistoryBudget(scope)
        val callback = CompletableDeferred<Unit>()
        val old = async { f.repository.openStoredHistory(scope, budget, {}, { it.getOrThrow(); callback.complete(Unit) }) }
        f.await(callback); val oldSeed = f.await(f.seedEntered)
        scope.invalidate()
        val next = f.scope(12); val nextBudget = StoredHistoryBudget(next)
        val fresh = async { f.repository.openStoredHistory(next, nextBudget, {}, {}) }
        if (terminal == "failure") f.transport.respondError(oldSeed, -32000, "Synthetic obsolete failure")
        else f.reply(oldSeed, """{"count":1,"epoch":"${f.gateway.currentReplayEpoch.orEmpty()}","latest_seq":9,"truncated":${terminal == "truncated"},"events":[{"type":"message.delta","seq":9,"session_id":"synthetic-runtime","payload":{"text":"Synthetic stale seed"}}]}""")
        f.await(old)
        withContext(Dispatchers.Default) { withTimeout(5000) { while (f.seedIds.size < 2) delay(5) } }
        assertEquals(2, f.seedCalls.get())
        assertFalse(fresh.isCompleted)
        assertTrue(f.repository.isStoredHistoryCurrent(next, nextBudget))
        assertNull(f.gateway.getLastSeenSeq("synthetic-runtime"))
        val replayed = async(start = CoroutineStart.UNDISPATCHED) { f.gateway.events.take(2).toList() }
        f.transport.emitTaggedFrame(f.gateway.connectionGeneration,
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","seq":2,"epoch":"${f.gateway.currentReplayEpoch.orEmpty()}","session_id":"synthetic-runtime","payload":{"text":"Synthetic held live"}}}""")
        assertTrue(f.gateway.ping() is GatewayResult.Success)
        assertFalse(replayed.isCompleted)
        f.reply(f.seedIds.last(), """{"count":1,"epoch":"${f.gateway.currentReplayEpoch.orEmpty()}","latest_seq":1,"truncated":false,"events":[{"type":"message.delta","seq":1,"session_id":"synthetic-runtime","payload":{"text":"Synthetic fresh seed"}}]}""")
        assertEquals(listOf(1L, 2L), f.await(replayed).map { it.seq })
        f.await(fresh)
        assertEquals(2L, f.gateway.getLastSeenSeq("synthetic-runtime"))
        assertEquals(2, f.seedCalls.get())
    } }

}

/** Synthetic provider only. All identity/header/projection carriers are decoded by real adapters. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S4bBaselineFixture(folder: File, dispatcher: CoroutineDispatcher,
    gatewayDispatcher: CoroutineDispatcher = dispatcher, rpcTimeoutMillis: Long = 10000) {
    // Opt-in S5 scripts; the original S4b path and its one-shot handles stay unchanged.
    class HttpStep(val body: String? = null, val failure: StoredHistoryFailure? = null,
        val status: Int = 200, val held: Boolean = false, val nonCooperative: Boolean = false,
        val responseHeaders: Map<String, List<String>>? = null,
        val headersGate: CompletableDeferred<Unit>? = null) {
        val entered = CompletableDeferred<Unit>()
        val headersEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
    }
    class RpcStep(val body: String? = null, val error: Boolean = false, val held: Boolean = false) {
        val entered = CompletableDeferred<String>()
        var generation = 0L
    }
    data class Request(val limit: Int, val offset: Int, val timeout: Long)
    var scripted = false
    val httpSteps = java.util.concurrent.ConcurrentLinkedQueue<HttpStep>()
    val resumeSteps = java.util.concurrent.ConcurrentLinkedQueue<RpcStep>()
    val seedSteps = java.util.concurrent.ConcurrentLinkedQueue<RpcStep>()
    val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
    val budgets = java.util.concurrent.CopyOnWriteArrayList<StoredHistoryBudget>()
    private val usedHttp = java.util.concurrent.CopyOnWriteArrayList<HttpStep>()
    private val usedRpc = java.util.concurrent.CopyOnWriteArrayList<RpcStep>()
    val origin = CanonicalOrigin.parse("http://example.test:9711").getOrThrow()
    val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = dispatcher)
    val cache = VaultConversationCacheRepository(vault)
    val transport = FakeGatewayTransport()
    val gateway = DefaultGatewayProtocolAdapter(transport, gatewayDispatcher, rpcTimeoutMs = rpcTimeoutMillis)
    val resumeEntered = CompletableDeferred<String>()
    val seedEntered = CompletableDeferred<String>()
    val seedFinished = CompletableDeferred<Unit>()
    val bodyEntered = CompletableDeferred<Unit>()
    val headerEntered = CompletableDeferred<Unit>()
    var headerGate: CompletableDeferred<Unit>? = null
    var deletedCookie = false
    var nonCooperativeBody = false
    private val bodyRelease = CompletableDeferred<Unit>()
    val decodedResume = CompletableDeferred<SessionResumeResult>()
    val decodedStored = CompletableDeferred<Result<StoredHistoryCandidate>>()
    val seedIds = java.util.concurrent.CopyOnWriteArrayList<String>()
    val httpCalls = AtomicInteger()
    val bodyReturns = AtomicInteger()
    val responseCloses = AtomicInteger()
    val resumeCalls = AtomicInteger()
    val seedCalls = AtomicInteger()
    var holdResume = false
    var holdSeed = false
    var holdBody = false
    var rotateCookie = false
    var active = false
    var withLocalTurns = false
    val ledger = io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger(vault)
    val coordinator = io.github.anshuman2401.hermesmobile.data.turn.TurnCoordinator(gateway, ledger)
    var storedJson: String? = null
    var resumeJson: String? = null
    var statusCode = 200
    var resumeError = false
    var resolvedId = "synthetic-session"
    var runtimeId = "synthetic-runtime"
    var bodyGate: CompletableDeferred<Unit>? = null
    var bodyHook: (() -> Unit)? = null
    var decodedStoredHook: ((Result<StoredHistoryCandidate>) -> Unit)? = null
    val diagnostics = io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticRing()
    private val stores = ArrayList<ViewModelStore>()
    private val jobs = ArrayList<Job>()
    private val observed = object : GatewayProtocolAdapter by gateway {
        override suspend fun replayEventsSinceScoped(
            sessionId: String, lastSeen: Long, durableSessionId: String?, origin: CanonicalOrigin,
            generation: Long, isCurrent: suspend () -> Boolean
        ): GatewayResult<ReplayEventsResult> {
            val result = gateway.replayEventsSinceScoped(sessionId,lastSeen,durableSessionId,origin,generation,isCurrent)
            if (result is GatewayResult.Success) seedFinished.complete(Unit)
            return result
        }
        override suspend fun resumeSessionScoped(sessionId: String, profile: String, origin: CanonicalOrigin, generation: Long): GatewayResult<SessionResumeResult> {
            val result = gateway.resumeSessionScoped(sessionId, profile, origin, generation)
            if (result is GatewayResult.Success) decodedResume.complete(result.value)
            return result
        }
    }
    private val http = object : StoredHistoryHttpTransport {
        override fun open(origin: CanonicalOrigin, path: String, profile: String, limit: Int, offset: Int,
            headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<StoredHistoryResponse> {
            check(origin == this@S4bBaselineFixture.origin)
            check(profile == "default")
            if (!scripted) check(limit == 120 && offset == 0)
            val step = if (scripted) httpSteps.poll() else null
            if (scripted) check(step != null) { "Missing S5 HTTP script" }
            step?.let { usedHttp.add(it) }
            requests.add(Request(limit, offset, timeoutMillis))
            httpCalls.incrementAndGet()
            return object : AuthenticatedHttpCall<StoredHistoryResponse> {
                private val closed = java.util.concurrent.atomic.AtomicBoolean()
                private var returned = false
                private var cancelled = false
                private fun closeResponse() { if (returned && closed.compareAndSet(false, true)) { responseCloses.incrementAndGet(); step?.closed?.complete(Unit) } }
                override fun cancel() { cancelled = true; bodyRelease.complete(Unit); if (step?.nonCooperative != true) step?.release?.complete(Unit); closeResponse() }
                override suspend fun awaitHeaders(): StoredHistoryResponse {
                    headerEntered.complete(Unit)
                    step?.headersEntered?.complete(Unit)
                    step?.headersGate?.let { withContext(NonCancellable) { it.await() } }
                    headerGate?.let { withContext(NonCancellable) { it.await() } }
                    returned = true
                    if (cancelled) closeResponse()
                    return object : StoredHistoryResponse {
                    override val statusCode get() = step?.status ?: this@S4bBaselineFixture.statusCode
                    override val headers = step?.responseHeaders ?: if (deletedCookie) mapOf("Set-Cookie" to listOf("session=; Max-Age=0; Path=/")) else if (rotateCookie) mapOf("Set-Cookie" to listOf("session=synthetic-successor; Path=/")) else emptyMap()
                    override suspend fun readBoundedBody(): ByteArray {
                        bodyEntered.complete(Unit)
                        step?.entered?.complete(Unit)
                        if (step?.held == true) {
                            if (step.nonCooperative) withContext(NonCancellable) { step.release.await() }
                            else step.release.await()
                        }
                        step?.failure?.let { throw StoredHistoryException(it) }
                        bodyHook?.invoke()
                        bodyGate?.await()
                        if (holdBody) { if (nonCooperativeBody) withContext(NonCancellable) { bodyRelease.await() } else bodyRelease.await() }
                        bodyReturns.incrementAndGet()
                        return (step?.body ?: storedBody()).toByteArray()
                    }
                    override fun close() { closeResponse() }
                    }
                }
            }
        }
    }
    val repository = DefaultSessionRepository(observed, vault, diagnosticRing = diagnostics,
        storedHistoryAdapter = object : StoredHistoryProtocolAdapter {
            private val real = DefaultStoredHistoryProtocolAdapter(DefaultAuthRepository(vault), http)
            override suspend fun fetchPage(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int) = real.fetchPage(scope, budget, offset)
            override suspend fun fetchPageForOpen(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int): Result<StoredHistoryCandidate> =
                real.fetchPageForOpen(scope, budget, offset).also { budgets.add(budget); decodedStored.complete(it); decodedStoredHook?.invoke(it) }
        })
    init {
        transport.setRpcHandler("session.resume") { id, params ->
            check(params["profile"]?.asStringOrNull() == "default")
            check(params["session_id"]?.asStringOrNull() == "synthetic-session")
            resumeCalls.incrementAndGet()
            resumeEntered.complete(id)
            val step = if (scripted) resumeSteps.poll() else null
            if (scripted) check(step != null) { "Missing S5 resume script" }
            step?.let { it.generation = gateway.connectionGeneration; usedRpc.add(it); it.entered.complete(id) }
            if (step != null) { if (!step.held) respond(step, id, resumeBody()) }
            else if (!holdResume) { if (resumeError) transport.respondError(id, -32000, "Synthetic unavailable") else reply(id, resumeBody()) }
        }
        transport.setRpcHandler("session.events.since") { id, params ->
            check(params["session_id"]?.asStringOrNull() == runtimeId)
            seedCalls.incrementAndGet()
            seedIds.add(id)
            seedEntered.complete(id)
            val step = if (scripted) seedSteps.poll() else null
            if (scripted) check(step != null) { "Missing S5 seed script" }
            step?.let { it.generation = gateway.connectionGeneration; usedRpc.add(it); it.entered.complete(id) }
            if (step != null) { if (!step.held) respond(step, id, seedBody()) }
            else if (!holdSeed) reply(id, seedBody())
        }
    }
    suspend fun initialize() {
        vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
        vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-original"), origin.originUrl))
        vault.saveSelectedContextProfile("default")
        check(gateway.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)))
    }
    fun respond(step: RpcStep, id: String, fallback: String) = transport.emitTaggedFrame(step.generation,
        if (step.error) """{"jsonrpc":"2.0","id":"$id","error":{"code":-32000,"message":"Synthetic unavailable"}}"""
        else """{"jsonrpc":"2.0","id":"$id","result":${step.body ?: fallback}}""")
    fun reply(id: String, body: String) = transport.emitTaggedFrame(gateway.connectionGeneration,
        """{"jsonrpc":"2.0","id":"$id","result":$body}""")
    fun event(type: String, text: String, status: String? = null, session: String? = runtimeId, generation: Long = gateway.connectionGeneration) {
        val encoded = io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson.serialize(io.github.anshuman2401.hermesmobile.data.gateway.json.JsonString(text))
        transport.emitTaggedFrame(generation,
            """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":${session?.let { "\"$it\"" } ?: "null"},"payload":{"text":$encoded${if (status != null) ",\"status\":\"$status\"" else ""}}}}""")
    }
    fun resumeBody() = resumeJson ?: """{"session_id":"$runtimeId","stored_session_id":"$resolvedId","message_count":2,"messages":[{"role":"user","content":"Synthetic compact question"},{"role":"assistant","content":"Synthetic compact answer"}],${if (active) "\"running\":true,\"status\":\"running\",\"turn_started_at\":100,\"inflight\":{\"user\":\"Synthetic active question\",\"assistant\":\"Synthetic partial\",\"streaming\":true},\"queued\":{\"user\":\"Synthetic queued question\"}" else "\"running\":false,\"status\":\"idle\""}}"""
    fun seedBody() = """{"count":0,"epoch":"${gateway.currentReplayEpoch.orEmpty()}","latest_seq":0,"truncated":false,"events":[]}"""
    fun storedBody() = storedJson ?: """{"session_id":"$resolvedId","profile":"default","messages":[{"id":1,"session_id":"$resolvedId","role":"user","content":"Synthetic stored question"},{"id":2,"session_id":"$resolvedId","role":"assistant","content":"Synthetic stored answer"}],"pagination":{"limit":120,"offset":0,"order":"latest","returned":2}}"""
    fun vm(contextUsage: io.github.anshuman2401.hermesmobile.data.context.ContextUsageRepository? = null,
        slashCommands: io.github.anshuman2401.hermesmobile.data.commands.SlashCommandRepository? = null): ChatViewModel = ChatViewModel("synthetic-session", sessionRepository = repository,
        gatewayProtocolAdapter = gateway, vaultStorage = vault, conversationCacheRepository = cache,
        pendingTurnLedger = if (withLocalTurns) ledger else null, turnCoordinator = if (withLocalTurns) coordinator else null,
        deltaPublicationContext = Dispatchers.Default, contextUsageRepository = contextUsage, slashCommands = slashCommands).also {
        stores.add(ViewModelStore().apply { put("synthetic", it) })
        jobs.add(it.viewModelScope.coroutineContext[Job]!!)
    }
    suspend fun <T> await(value: Deferred<T>): T = withContext(Dispatchers.Default) { withTimeout(5000) { value.await() } }
    suspend fun loaded(vm: ChatViewModel) = withContext(Dispatchers.Default) {
        withTimeout(5000) {
            vm.uiState.first { !it.isInitialLoading && it.runtimeSessionId == runtimeId }
            // Read the existing operation job, so REST readability cannot masquerade as live/seed completion.
            val field = ChatViewModel::class.java.getDeclaredField("historyLoadJob").apply { isAccessible = true }
            (field.get(vm) as Job).join()
            vm.uiState.value
        }
    }
    suspend fun scope(request: Long = 11) = repository.captureStoredHistoryScope("synthetic-local", origin,
        "default", "synthetic-session", request, request).getOrThrow()
    suspend fun joined(vm: ChatViewModel) = withContext(Dispatchers.Default) { withTimeout(5000) {
        val field = ChatViewModel::class.java.getDeclaredField("historyLoadJob").apply { isAccessible = true }
        (field.get(vm) as Job).join()
        vm.uiState.value
    } }
    suspend fun joinedEarlier(vm: ChatViewModel) = withContext(Dispatchers.Default) { withTimeout(5000) {
        val field = ChatViewModel::class.java.getDeclaredField("earlierHistoryJob").apply { isAccessible = true }
        (field.get(vm) as? Job)?.join()
        vm.uiState.value
    } }
    suspend fun delivered(vm: ChatViewModel, type: String, text: String, status: String? = null): io.github.anshuman2401.hermesmobile.feature.chat.ChatUiState = coroutineScope {
        val decoded = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.first { it.sessionId == runtimeId && when(type) {
            "message.delta" -> it is GatewayEvent.MessageDelta
            "message.complete" -> it is GatewayEvent.MessageComplete
            "message.interim" -> it is GatewayEvent.MessageInterim
            else -> true
        } } }
        event(type, text, status)
        await(decoded)
        vm.uiState.value
    }
    fun releaseAll() {
        usedHttp.forEach { it.release.complete(Unit); it.headersGate?.complete(Unit) }
        usedRpc.filter { it.held && it.entered.isCompleted }.forEach { respond(it, it.entered.getCompleted(), seedBody()) }
        headerGate?.complete(Unit)
        holdResume = false; holdSeed = false; holdBody = false
        if (resumeEntered.isCompleted) reply(resumeEntered.getCompleted(), resumeBody())
        if (seedEntered.isCompleted) reply(seedEntered.getCompleted(), seedBody())
        bodyRelease.complete(Unit)
        bodyGate?.complete(Unit)
    }
    suspend fun close() {
        releaseAll()
        stores.forEach { it.clear() }
        withContext(NonCancellable + Dispatchers.Default) { withTimeout(5000) { jobs.joinAll() } }
        gateway.disconnect()
    }
}
