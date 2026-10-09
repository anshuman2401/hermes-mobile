package io.github.anshuman2401.hermesmobile.data.history

import io.github.anshuman2401.hermesmobile.data.auth.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

class StoredHistoryTransportTest {
    @Test fun olderCycleContinuationKeepsReceiptLimitButRenewsDeadline() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); var now = 0L
            val first = StoredHistoryBudget(scope) { now }
            f.enqueue(oversized(), cookie = true); f.enqueue(HistoryFixture.page(limit = 30))
            assertTrue(f.repository.fetchStoredHistoryPage(scope, first).isSuccess)
            val receipt = first.receipt
            now = 31_000_000_000L
            val next = first.nextCycle()
            assertNotSame(first, next); assertSame(receipt, next.receipt)
            assertEquals(30, next.acceptedLimit); assertEquals(30_000L, next.remainingMillis())
            assertEquals(0, next.successfulPages); assertEquals(0, next.httpAttempts)
            assertEquals(StoredHistoryHeaderState.UNAVAILABLE, next.awaitHeaderDecision().state)
            f.enqueue("{synthetic-malformed", cookie = true)
            assertEquals(StoredHistoryFailure.INTEGRITY, failure(f.repository.fetchStoredHistoryPage(scope, next, 1)))
            val retry = next.nextCycle()
            assertSame(first.receipt, retry.receipt)
            f.enqueue(HistoryFixture.page(limit = 30, offset = 1))
            assertTrue(f.repository.fetchStoredHistoryPage(scope, retry, 1).isSuccess)
            now = 61_000_000_000L
            assertEquals(StoredHistoryFailure.DEADLINE, failure(f.repository.fetchStoredHistoryPage(scope, retry, 2)))
            assertEquals(4, f.server.requestCount)
            val requests = (1..4).map { f.server.takeRequest(5, TimeUnit.SECONDS)!! }
            assertEquals(listOf("120", "30", "30", "30"), requests.map { it.requestUrl!!.queryParameter("limit") })
            assertEquals(listOf("0", "0", "1", "1"), requests.map { it.requestUrl!!.queryParameter("offset") })
            f.assertReadOnly()
        }
    }

    @get:Rule val temp = TemporaryFolder()
    private fun oversized() = " ".repeat(OkHttpStoredHistoryTransport.MAX_BODY_BYTES + 1)
    private fun failure(result: Result<*>) = (result.exceptionOrNull() as StoredHistoryException).failure

    @Test fun stickySameOffsetFallbackAndSevenAttemptSharedBudget() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            f.enqueue(oversized(), cookie = true); f.enqueue(HistoryFixture.page(limit = 30))
            val first = f.repository.fetchStoredHistoryPage(scope, budget).getOrThrow()
            assertEquals(30, first.limit); assertEquals(1, first.nextOffset)
            f.enqueue(oversized()); f.enqueue(HistoryFixture.page(limit = 1, offset = 1))
            val second = f.repository.fetchStoredHistoryPage(scope, budget, first.nextOffset).getOrThrow()
            assertEquals(1, second.limit)
            for (offset in 2..4) {
                f.enqueue(HistoryFixture.page(limit = 1, offset = offset))
                assertEquals(offset + 1, f.repository.fetchStoredHistoryPage(scope, budget, offset).getOrThrow().nextOffset)
            }
            assertEquals(5, budget.successfulPages); assertEquals(7, budget.httpAttempts)
            assertEquals(StoredHistoryFailure.BUDGET, failure(f.repository.fetchStoredHistoryPage(scope, budget, 5)))
            val requests = (0..6).map { f.server.takeRequest(5, TimeUnit.SECONDS)!! }
            assertEquals(listOf("120", "30", "30", "1", "1", "1", "1"), requests.map { it.requestUrl!!.queryParameter("limit") })
            assertEquals(listOf("0", "0", "1", "1", "2", "3", "4"), requests.map { it.requestUrl!!.queryParameter("offset") })
            requests.drop(1).forEach { assertEquals("session=synthetic-new", it.getHeader("Cookie")) }
            assertEquals("synthetic-new", f.vault.getAuthCredential()!!.cookies["session"])
            f.assertReadOnly()
        }
    }

    @Test fun exhaustedSingleRowNeverSkipsAndStartsAtSmallerLimitsStayBounded() = runBlocking {
        for (start in listOf(120, 30, 1)) HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            budget.acceptedLimit = start
            val attempts = when (start) { 120 -> 3; 30 -> 2; else -> 1 }
            repeat(attempts) { f.enqueue(oversized(), cookie = it == 0) }
            assertEquals(StoredHistoryFailure.OVERSIZE, failure(f.repository.fetchStoredHistoryPage(scope, budget, 7)))
            assertEquals(attempts, budget.httpAttempts); assertEquals(0, budget.successfulPages)
            repeat(attempts) { assertEquals("7", f.server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("offset")) }
            assertTrue(f.repository.fetchStoredHistoryPage(scope, budget, 8).isFailure)
            assertEquals(attempts, f.server.requestCount)
            assertEquals("synthetic-new", f.vault.getAuthCredential()!!.cookies["session"]); f.assertReadOnly()
        }
    }

    @Test fun decompressedStreamCapRejectsGzipBombBeforeJson() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            val compressed = ByteArrayOutputStream()
            GZIPOutputStream(compressed).use { it.write(oversized().toByteArray()) }
            f.server.enqueue(MockResponse().addHeader("Content-Encoding", "gzip").setBody(Buffer().write(compressed.toByteArray())))
            f.enqueue(HistoryFixture.page(limit = 30))
            assertEquals(30, f.repository.fetchStoredHistoryPage(scope, budget).getOrThrow().limit)
            assertEquals(2, f.server.requestCount); f.assertReadOnly()
        }
    }

    @Test fun exactByteCapAndDepthBoundaryAndMalformedBodyAfterRotation() = runBlocking {
        for (case in 0..3) HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            val body = when (case) {
                0 -> HistoryFixture.page().let { " ".repeat(OkHttpStoredHistoryTransport.MAX_BODY_BYTES - it.toByteArray().size) + it }
                1, 2 -> HistoryFixture.page(rows = HistoryFixture.row(extra = "\"content\":" + "[".repeat(if (case == 1) 29 else 30) + "0" + "]".repeat(if (case == 1) 29 else 30)))
                else -> "{synthetic-malformed"
            }
            f.enqueue(body, cookie = true)
            val result = f.repository.fetchStoredHistoryPage(scope, budget)
            if (case < 2) assertTrue(result.isSuccess)
            else assertEquals(if (case == 2) StoredHistoryFailure.DEPTH else StoredHistoryFailure.INTEGRITY, failure(result))
            assertEquals(1, f.server.requestCount); assertEquals("synthetic-new", f.vault.getAuthCredential()!!.cookies["session"])
            f.assertReadOnly()
        }
    }

    @Test fun redirectAndProviderErrorsNeverRetryAndTerminalAuthUsesExistingOwner() = runBlocking {
        for (status in listOf(302, 503, 401, 403)) HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            f.server.enqueue(MockResponse().setResponseCode(status).addHeader("Location", f.server.url("/synthetic-other"))
                .addHeader("Set-Cookie", "session=synthetic-new; Path=/").setBody("synthetic-untrusted-error"))
            assertTrue(f.repository.fetchStoredHistoryPage(scope, budget).isFailure)
            assertEquals(1, f.server.requestCount); assertEquals(0, budget.successfulPages)
            assertTrue(f.repository.fetchStoredHistoryPage(scope, budget).isFailure)
            assertEquals(1, f.server.requestCount)
            if (status == 401 || status == 403) assertNull(f.vault.getAuthCredential())
            else { assertEquals("synthetic-old", f.vault.getAuthCredential()!!.cookies["session"]); f.assertReadOnly() }
            assertNull(f.repository.activeSession.value); assertTrue(f.gateway.resumeCalls.isEmpty())
            assertFalse(f.diagnostics.snapshotReport().contains("synthetic-untrusted-error"))
        }
    }

    @Test fun retryAfterProviderFailureCannotHideAnExtraHttpAttempt() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            f.server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
                .addHeader("Set-Cookie", "session=synthetic-provider; Path=/").setBody("synthetic-provider-error"))
            f.enqueue()
            assertTrue("Provider failure must be terminal, without a hidden HTTP retry", f.repository.fetchStoredHistoryPage(scope, budget).isFailure)
            assertEquals(1, f.server.requestCount); assertEquals(1, budget.httpAttempts)
            assertEquals("synthetic-old", f.vault.getAuthCredential()!!.cookies["session"]); f.assertReadOnly()
        }
    }

    @Test fun cancellationOfHeadersReleasesAuthOwnerAndOfBodyClosesCall() = runBlocking {
        for (headersPending in listOf(true, false)) {
            lateinit var body: DeferredHistoryBody
            HistoryFixture(temp.newFolder()) { DeferredHistoryBody(it).also { body = it } }.use { f ->
                f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
                if (headersPending) f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                else f.enqueue(cookie = true)
                val read = async(start = CoroutineStart.UNDISPATCHED) { f.repository.fetchStoredHistoryPage(scope, budget) }
                withContext(Dispatchers.IO) { assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)) }
                if (!headersPending) withTimeout(5000) { body.entered.await() }
                read.cancelAndJoin(); assertTrue(read.isCancelled)
                if (!headersPending) { assertTrue(body.closed); body.release.complete(Unit) }
                f.enqueue("""{"ticket":"synthetic-ticket","ttl":30}""")
                assertTrue(withTimeout(5000) { f.auth.mintWebSocketTicket(f.origin) }.isSuccess)
                assertEquals(0, budget.successfulPages); assertTrue(budget.terminal); f.assertReadOnly()
            }
        }
    }

    @Test fun cancellationInterruptsActualBlockedHttpBodyCall() = runBlocking {
        val started = CompletableDeferred<okhttp3.Call>()
        val cleanupFailures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val handler = CoroutineExceptionHandler { _, failure -> cleanupFailures.add(failure) }
        val client = okhttp3.OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
            override fun responseBodyStart(call: okhttp3.Call) { started.complete(call) }
        }).build()
        HistoryFixture(temp.newFolder()) { OkHttpStoredHistoryTransport(client) }.use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            // A deliberately incomplete synthetic response leaves the real stream waiting for bytes.
            f.server.enqueue(MockResponse().setBody("synthetic-partial").setHeader("Content-Length", "100000")
                .addHeader("Set-Cookie", "session=synthetic-new; Path=/"))
            val read = async(context = handler, start = CoroutineStart.UNDISPATCHED) { f.repository.fetchStoredHistoryPage(scope, budget) }
            val actualCall = withTimeout(5000) { started.await() }
            read.cancelAndJoin()
            assertTrue(actualCall.isCanceled()); assertTrue(read.isCancelled)
            assertTrue("Cancellation cleanup must not throw", cleanupFailures.isEmpty())
            assertEquals(0, budget.successfulPages); assertEquals(1, f.server.requestCount)
            assertEquals("synthetic-new", f.vault.getAuthCredential()!!.cookies["session"])
            assertTrue(f.repository.fetchStoredHistoryPage(scope, budget).isFailure)
            assertEquals(1, f.server.requestCount); f.assertReadOnly()
        }
    }

    @Test fun deadlineCoversQueuedHeadersBodyDecodeAndFallbackWithoutReset() = runBlocking {
        for (phase in 0..2) HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); var now = 0L
            val budget = StoredHistoryBudget(scope) { now }
            when (phase) {
                0 -> {
                    now = 30_000_000_000L
                    assertEquals(StoredHistoryFailure.DEADLINE, failure(f.repository.fetchStoredHistoryPage(scope, budget)))
                    assertEquals(0, f.server.requestCount)
                }
                1 -> {
                    now = 29_900_000_000L
                    f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    assertTrue(withTimeout(5000) { f.repository.fetchStoredHistoryPage(scope, budget) }.isFailure)
                    assertEquals(1, f.server.requestCount)
                }
                2 -> {
                    f.enqueue(oversized()); f.enqueue(HistoryFixture.page(limit = 30))
                    assertTrue(f.repository.fetchStoredHistoryPage(scope, budget).isSuccess)
                    now = 30_000_000_000L
                    assertEquals(StoredHistoryFailure.DEADLINE, failure(f.repository.fetchStoredHistoryPage(scope, budget, 1)))
                    assertEquals(2, f.server.requestCount); assertEquals(30, budget.acceptedLimit)
                }
            }
            assertTrue(budget.terminal); f.assertReadOnly()
        }
    }

    @Test fun deadlineExpiredDuringDeferredBodyRetainsRotationButNoPage() = runBlocking {
        lateinit var body: DeferredHistoryBody
        HistoryFixture(temp.newFolder()) { DeferredHistoryBody(it).also { body = it } }.use { f ->
            f.initialize(); val scope = f.scope(); var now = 0L
            val budget = StoredHistoryBudget(scope) { now }; f.enqueue(cookie = true)
            val read = async { f.repository.fetchStoredHistoryPage(scope, budget) }
            withTimeout(5000) { body.entered.await() }
            now = 30_000_000_000L; body.release.complete(Unit)
            assertEquals(StoredHistoryFailure.DEADLINE, failure(read.await()))
            assertEquals(0, budget.successfulPages); assertTrue(body.closed)
            assertEquals("synthetic-new", f.vault.getAuthCredential()!!.cookies["session"]); f.assertReadOnly()
        }
    }

    @Test fun queuedDeadlineCannotDispatchAfterHeaderOwnerRelease() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); var now = 0L
            val budget = StoredHistoryBudget(scope) { now }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
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
            entered.await()
            val read = async(start = CoroutineStart.UNDISPATCHED) { f.repository.fetchStoredHistoryPage(scope, budget) }
            now = 30_000_000_000L; release.complete(Unit); first.await()
            assertEquals(StoredHistoryFailure.DEADLINE, failure(read.await()))
            assertEquals(0, budget.httpAttempts); assertEquals(0, f.server.requestCount); f.assertReadOnly()
        }
    }

    @Test fun fallbackNeverRecapturesUnrelatedOwnerAfterCommittedRotation() = runBlocking {
        lateinit var body: DeferredHistoryBody
        HistoryFixture(temp.newFolder()) { DeferredHistoryBody(it).also { body = it } }.use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope)
            f.enqueue(oversized(), cookie = true)
            val read = async { f.repository.fetchStoredHistoryPage(scope, budget) }
            withTimeout(5000) { body.entered.await() }
            f.vault.saveAuthCredential(f.credential("synthetic-login"))
            body.release.complete(Unit)
            assertEquals(StoredHistoryFailure.STALE, failure(read.await()))
            assertEquals(1, f.server.requestCount); assertEquals(120, budget.acceptedLimit)
            assertEquals("synthetic-login", f.vault.getAuthCredential()!!.cookies["session"]); f.assertReadOnly()
        }
    }
}
