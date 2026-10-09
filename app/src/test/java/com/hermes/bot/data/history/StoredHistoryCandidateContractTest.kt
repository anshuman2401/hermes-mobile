package com.hermes.bot.data.history

import com.hermes.bot.data.auth.*
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoredHistoryCandidateContractTest {
    @get:Rule val temp = TemporaryFolder()
    private fun adapter(f: HistoryFixture, decorate: (StoredHistoryHttpTransport) -> StoredHistoryHttpTransport = { it }) =
        DefaultStoredHistoryProtocolAdapter(f.auth, decorate(OkHttpStoredHistoryTransport()))
    private suspend fun <T> terminal(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(5000) { block() } }
    private fun failure(result: Result<*>) = (result.exceptionOrNull() as StoredHistoryException).failure

    @Test fun exactPageUnchangedAndValidatedDescendantHeldWithoutLegacyAdmissionOrEffects() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val a = adapter(f)
            f.enqueue()
            val exact = terminal { a.fetchPage(scope, StoredHistoryBudget(scope)) }.getOrThrow()
            assertEquals(1, exact.returned); assertEquals("synthetic-session", exact.resolvedSessionId)
            val child = HistoryFixture.page(session = "synthetic-child", rows = HistoryFixture.row(session = "synthetic-child"))
            f.enqueue(child); val heldBudget = StoredHistoryBudget(scope)
            val held = terminal { a.fetchPageForOpen(scope, heldBudget) }.getOrThrow()
            assertTrue(held.isDescendant); assertEquals("synthetic-child", held.page.resolvedSessionId)
            assertEquals(1, held.page.returned); assertEquals(1, heldBudget.successfulPages)
            assertFalse(held.toString().contains("synthetic-child")); f.assertReadOnly()
            f.enqueue(child); val legacyBudget = StoredHistoryBudget(scope)
            val rejected = terminal { a.fetchPage(scope, legacyBudget) }.exceptionOrNull() as StoredHistoryException
            assertEquals(StoredHistoryFailure.UNBOUND_DESCENDANT, rejected.failure)
            assertEquals(scope.requestedSessionId, rejected.requestedSessionId)
            assertEquals("synthetic-child", rejected.resolvedSessionId)
            assertEquals(0, legacyBudget.successfulPages)
            assertEquals(3, f.server.requestCount); f.assertReadOnly()
        }
    }

    @Test fun defaultCapabilityFailsClosedWithoutCallingLegacyImplementation() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); var calls = 0
            val old = object : StoredHistoryProtocolAdapter {
                override suspend fun fetchPage(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int): Result<StoredHistoryPage> {
                    calls++; error("Synthetic forbidden legacy admission")
                }
            }
            assertEquals(StoredHistoryFailure.UNAVAILABLE, failure(old.fetchPageForOpen(scope, StoredHistoryBudget(scope))))
            assertEquals(0, calls); f.assertReadOnly()
        }
    }

    @Test fun candidateRejectsProfileShapeIdentityRowsPaginationCountAndDepthBeforeSuccess() = runBlocking {
        val child = HistoryFixture.page(session = "synthetic-child", rows = HistoryFixture.row(session = "synthetic-child"))
        val malformed = listOf(
            child.replace("\"profile\":\"default\"", "\"profile\":\"synthetic-other\""),
            child.replace("\"messages\":[", "\"messages\":null,\"ignored\":["),
            child.replace("synthetic-child", "../synthetic-child"),
            child.replace("synthetic-child", "."),
            HistoryFixture.page(session = "synthetic-child"),
            HistoryFixture.page(session = "synthetic-child", rows = HistoryFixture.row(0, session = "synthetic-child")),
            HistoryFixture.page(session = "synthetic-child", rows = HistoryFixture.row(session = "synthetic-child") + "," + HistoryFixture.row(session = "synthetic-child"), count = 2),
            child.replace("\"returned\":1", "\"returned\":2"),
            child.replace("\"order\":\"latest\"", "\"order\":\"oldest\""),
            child.replace("\"limit\":120", "\"limit\":30"),
            child.replace("\"offset\":0", "\"offset\":1"),
            HistoryFixture.page(session = "synthetic-child", rows = HistoryFixture.row(extra = "\"content\":" + "[".repeat(30) + "0" + "]".repeat(30), session = "synthetic-child")))
        for ((index, body) in malformed.withIndex()) HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope); f.enqueue(body)
            val result = terminal { adapter(f).fetchPageForOpen(scope, budget) }
            assertEquals(if (index == malformed.lastIndex) StoredHistoryFailure.DEPTH else StoredHistoryFailure.INTEGRITY, failure(result))
            assertEquals(0, budget.successfulPages); assertEquals(1, budget.httpAttempts)
            assertEquals(120, budget.acceptedLimit); assertTrue(budget.terminal); f.assertReadOnly()
        }
    }

    @Test fun candidateOversizeOnlyFallbackIsStickyAndOwnRotationSurvivesMalformedBody() = runBlocking {
        for (malformed in listOf(false, true)) HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            if (malformed) f.enqueue("{", cookie = true) else {
                f.enqueue(" ".repeat(OkHttpStoredHistoryTransport.MAX_BODY_BYTES + 1), cookie = true)
                f.enqueue(HistoryFixture.page(limit = 30, session = "synthetic-child", rows = HistoryFixture.row(session = "synthetic-child")))
            }
            val result = terminal { adapter(f).fetchPageForOpen(scope, budget) }
            if (malformed) assertEquals(StoredHistoryFailure.INTEGRITY, failure(result))
            else { assertTrue(result.getOrThrow().isDescendant); assertEquals(30, budget.acceptedLimit) }
            assertEquals(if (malformed) 1 else 2, budget.httpAttempts)
            assertEquals(if (malformed) 0 else 1, budget.successfulPages)
            assertEquals("synthetic-new", f.vault.getAuthCredential()!!.cookies["session"])
            assertEquals(StoredHistoryHeaderState.ACCEPTED, terminal { budget.awaitHeaderDecision() }.state)
            f.assertReadOnly()
        }
    }

    @Test fun heldCandidateInheritsUnicodeFieldNestedValueAndUnknownFieldBounds() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            val extra = """"content":"${"😀".repeat(10000)}","args":"${"x".repeat(9000)}",
                "display_metadata":{"nested":${"[".repeat(10)}0${"]".repeat(10)}},"unsupported":"SYNTHETIC_UNSUPPORTED""""
            f.enqueue(HistoryFixture.page(session = "synthetic-child", rows = HistoryFixture.row(extra = extra, session = "synthetic-child")))
            val held = terminal { adapter(f).fetchPageForOpen(scope, budget) }.getOrThrow()
            val row = held.page.rows.single()
            assertTrue(row.truncated)
            assertEquals(16384, row.fields.getValue("content").asStringOrNull()!!.toByteArray().size)
            assertEquals(4096, row.fields.getValue("args").asStringOrNull()!!.length)
            assertFalse(row.hasProperty("unsupported"))
            assertTrue(row.canonicalBytes < 22000); f.assertReadOnly()
        }
    }

    @Test fun ownSuccessorAndHeaderOnlyWaiterCompleteWhileActualBodyRemainsHeld() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            assertEquals(StoredHistoryHeaderState.UNAVAILABLE, budget.awaitHeaderDecision().state)
            val body = DeferredHistoryBody(OkHttpStoredHistoryTransport())
            val headersEntered = CompletableDeferred<Unit>(); val headersRelease = CompletableDeferred<Unit>()
            val gatedHeaders = object : StoredHistoryHttpTransport {
                override fun open(origin: CanonicalOrigin, path: String, profile: String, limit: Int, offset: Int,
                    headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<StoredHistoryResponse> {
                    val pending = body.open(origin, path, profile, limit, offset, headers, timeoutMillis)
                    return object : AuthenticatedHttpCall<StoredHistoryResponse> by pending {
                        override suspend fun awaitHeaders(): StoredHistoryResponse {
                            val response = pending.awaitHeaders()
                            headersEntered.complete(Unit); headersRelease.await(); return response
                        }
                    }
                }
            }
            f.enqueue(cookie = true)
            val read = async(Dispatchers.Default) { adapter(f) { gatedHeaders }.fetchPageForOpen(scope, budget) }
            try {
                terminal { headersEntered.await() }
                assertFalse(budget.headerDecision.isComplete)
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { budget.awaitHeaderDecision() }
                assertFalse(waiter.isCompleted)
                headersRelease.complete(Unit)
                val outcome = terminal { waiter.await() }
                terminal { body.entered.await() }
                assertFalse(read.isCompleted)
                assertEquals(StoredHistoryHeaderState.ACCEPTED, outcome.state)
                assertSame(scope.lease, budget.headerDecision.issuingReceipt)
                assertSame(budget.receipt, outcome.acceptedReceipt)
                assertNotEquals(scope.lease.epoch, outcome.acceptedReceipt!!.epoch)
                assertTrue(f.auth.isResponseCurrent(outcome.acceptedReceipt!!))
                f.enqueue("""{"ticket":"synthetic-ticket","ttl":30}""")
                assertTrue(terminal { f.auth.mintWebSocketTicket(f.origin) }.isSuccess)
                assertFalse(read.isCompleted) // auth lock is free before body release
                body.release.complete(Unit)
                assertTrue(terminal { read.await() }.isSuccess)
                assertTrue(body.closed); f.assertReadOnly()
            } finally { headersRelease.complete(Unit); body.release.complete(Unit); terminal { read.cancelAndJoin() } }
        }
    }

    @Test fun deniedExceptionCancellationAndTimeoutCompletePendingHeaderDecision() = runBlocking {
        for (case in 0..3) HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); var now = 0L
            val budget = StoredHistoryBudget(scope) { now }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val transport = object : StoredHistoryHttpTransport {
                override fun open(origin: CanonicalOrigin, path: String, profile: String, limit: Int, offset: Int,
                    headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<StoredHistoryResponse> =
                    object : AuthenticatedHttpCall<StoredHistoryResponse> {
                        override fun cancel() = Unit
                        override suspend fun awaitHeaders(): StoredHistoryResponse {
                            entered.complete(Unit); release.await()
                            if (case == 1) throw IllegalStateException("Synthetic transport failure")
                            return object : StoredHistoryResponse {
                                override val statusCode = 401
                                override val headers = emptyMap<String, List<String>>()
                                override suspend fun readBoundedBody(): ByteArray = error("Denied body must not be read")
                                override fun close() = Unit
                            }
                        }
                    }
            }
            if (case == 3) now = 29_750_000_000L
            val read = async(Dispatchers.Default) { DefaultStoredHistoryProtocolAdapter(f.auth, transport).fetchPageForOpen(scope, budget) }
            try {
                terminal { entered.await() }; assertFalse(budget.headerDecision.isComplete)
                val waiter = async(Dispatchers.Default) { budget.awaitHeaderDecision() }
                when (case) {
                    0, 1 -> release.complete(Unit)
                    2 -> terminal { read.cancelAndJoin() }
                    3 -> Unit // real 250ms timeout, no virtual scheduler proof
                }
                if (case != 2) assertTrue(terminal { read.await() }.isFailure)
                val state = terminal { waiter.await() }.state
                assertEquals(listOf(StoredHistoryHeaderState.DENIED, StoredHistoryHeaderState.FAILED,
                    StoredHistoryHeaderState.CANCELLED, StoredHistoryHeaderState.DEADLINE)[case], state)
                assertTrue(budget.headerDecision.isComplete)
                assertEquals(0, budget.successfulPages)
            } finally { release.complete(Unit); terminal { read.cancelAndJoin() } }
        }
    }

    @Test fun completedOwnHeaderReceiptSurvivesBodyDeadlineWithoutCandidateSuccess() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); var now = 0L
            val budget = StoredHistoryBudget(scope) { now }; val body = DeferredHistoryBody(OkHttpStoredHistoryTransport())
            f.enqueue(cookie = true)
            val read = async(Dispatchers.Default) { adapter(f) { body }.fetchPageForOpen(scope, budget) }
            try {
                terminal { body.entered.await() }
                val accepted = terminal { budget.awaitHeaderDecision() }
                now = 30_000_000_000L; body.release.complete(Unit)
                assertEquals(StoredHistoryFailure.DEADLINE, failure(terminal { read.await() }))
                val completed = terminal { budget.awaitHeaderDecision() }
                assertEquals(StoredHistoryHeaderState.ACCEPTED, completed.state)
                assertSame(accepted.acceptedReceipt, completed.acceptedReceipt)
                assertTrue(f.auth.isResponseCurrent(completed.acceptedReceipt!!))
                assertEquals(0, budget.successfulPages); assertTrue(body.closed); f.assertReadOnly()
            } finally { body.release.complete(Unit); terminal { read.cancelAndJoin() } }
        }
    }

    @Test fun unrelatedNewLoginDeniesQueuedOwnExchangeWithoutAmbientSalvage() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val blocker = async(Dispatchers.Default) {
                f.auth.exchangeAuthenticated(f.origin, "/api/synthetic") {
                    object : AuthenticatedHttpCall<StoredHistoryResponse> {
                        override fun cancel() = Unit
                        override suspend fun awaitHeaders(): StoredHistoryResponse {
                            entered.complete(Unit); release.await()
                            return object : StoredHistoryResponse {
                                override val statusCode = 200
                                override val headers = emptyMap<String, List<String>>()
                                override suspend fun readBoundedBody() = ByteArray(0)
                                override fun close() = Unit
                            }
                        }
                    }
                }
            }
            terminal { entered.await() }
            val read = async(start = CoroutineStart.UNDISPATCHED) { adapter(f).fetchPageForOpen(scope, budget) }
            try {
                terminal { while (budget.headerDecision.issuingReceipt == null) delay(5) }
                assertFalse(budget.headerDecision.isComplete)
                f.vault.saveAuthCredential(f.credential("synthetic-unrelated-login"))
                release.complete(Unit); terminal { blocker.await() }
                assertEquals(StoredHistoryFailure.STALE, failure(terminal { read.await() }))
                assertEquals(StoredHistoryHeaderState.DENIED, terminal { budget.awaitHeaderDecision() }.state)
                assertSame(scope.lease, budget.receipt)
                assertEquals(0, budget.httpAttempts); assertEquals(0, f.server.requestCount)
                assertEquals("synthetic-unrelated-login", f.vault.getAuthCredential()!!.cookies["session"])
                f.assertReadOnly()
            } finally { release.complete(Unit); terminal { read.cancelAndJoin(); blocker.cancelAndJoin() } }
        }
    }
}
