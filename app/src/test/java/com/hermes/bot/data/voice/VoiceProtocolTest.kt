package com.hermes.bot.data.voice

import com.hermes.bot.data.auth.*
import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.gateway.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit

class VoiceProtocolTest {
    @get:Rule val temp = TemporaryFolder()
    private val wav get() = VoiceLimits.wav(byteArrayOf(0, 1, 2, 3))
    @Test fun wavAndTimeoutBounds() {
        val bytes = wav
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_000, header.getInt(24)); assertEquals(4, header.getInt(40)); assertEquals(48, bytes.size)
        assertEquals(180_000L, VoiceLimits.requestTimeoutMillis(1)); assertEquals(600_000L, VoiceLimits.requestTimeoutMillis(Int.MAX_VALUE))
        assertEquals(VoiceLimits.MAX_PCM_BYTES + 44, VoiceLimits.wav(ByteArray(VoiceLimits.MAX_PCM_BYTES)).size)
        for (bad in listOf(ByteArray(0), ByteArray(3), ByteArray(VoiceLimits.MAX_PCM_BYTES + 2))) {
            assertTrue(runCatching { VoiceLimits.wav(bad) }.isFailure)
        }
    }
    @Test fun strictDecodeEmptyUnicodeMalformedAndBounds() {
        fun decode(s: String) = DefaultVoiceProtocolAdapter.decode(s.toByteArray())
        assertEquals("", decode("""{"ok":true,"transcript":"  ","provider":null}"""))
        assertEquals("नमस्ते", decode("""{"ok":true,"transcript":" नमस्ते "}"""))
        for (bad in listOf("{}", "[]", "{", """{"ok":false,"transcript":"x"}""", """{"ok":true,"transcript":3}"""))
            assertEquals(VoiceFailure.INVALID, (runCatching { decode(bad) }.exceptionOrNull() as VoiceException).failure)
        assertTrue(runCatching { DefaultVoiceProtocolAdapter.decode(byteArrayOf(-1)) }.isFailure)
        assertTrue(runCatching { DefaultVoiceProtocolAdapter.decode(ByteArray(VoiceLimits.MAX_RESPONSE_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { decode("""{"ok":true,"transcript":"${"é".repeat(VoiceLimits.MAX_TRANSCRIPT_BYTES / 2 + 1)}"}""") }.isFailure)
    }
    @Test fun realHttpRoutesProfilePostAndAcceptedCookieSuccessor() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val f = VoiceTestFixture(temp.newFolder(), CanonicalOrigin.parse(server.url("/").toString()).getOrThrow())
            f.initialize(); f.vault.saveSelectedContextProfile("synthetic-workspace")
            server.enqueue(MockResponse().setBody("""{"ok":true,"transcript":" synthetic words ","provider":"ignored"}""")
                .addHeader("Set-Cookie", "session=synthetic-new; Path=/"))
            val repository = DefaultVoiceRepository(f.sessions, f.vault, f.auth,
                DefaultVoiceProtocolAdapter(f.auth, OkHttpVoiceTransport()))
            val owner = repository.capture("synthetic-stored", "synthetic-runtime")!!
            val outcome = repository.transcribe(owner, wav)
            assertTrue("transcription result: " + ((outcome as? VoiceResult.Failed)?.failure?.name ?: "success"), outcome is VoiceResult.Transcript)
            assertEquals("synthetic words", (outcome as VoiceResult.Transcript).text)
            assertTrue(repository.isCurrent(owner))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/audio/transcribe", request.requestUrl!!.encodedPath)
            assertEquals("synthetic-workspace", request.requestUrl!!.queryParameter("profile"))
            assertEquals("session=synthetic-old", request.getHeader("Cookie"))
            val body = GatewayJson.parse(request.body.readUtf8()) as JsonObject
            assertEquals("audio/wav", body["mime_type"]?.asStringOrNull())
            assertArrayEquals(wav, Base64.getDecoder().decode(body["data_url"]!!.asStringOrNull()!!.substringAfter(',')))
            assertEquals("synthetic-new", f.vault.getAuthCredential()!!.cookies["session"])
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun statusFailuresAndRedirectNeverReplayPost() = runBlocking {
        for ((code, failure) in listOf(400 to VoiceFailure.REJECTED, 413 to VoiceFailure.REJECTED,
            404 to VoiceFailure.UNAVAILABLE, 401 to VoiceFailure.AUTH, 403 to VoiceFailure.AUTH,
            302 to VoiceFailure.NETWORK, 503 to VoiceFailure.NETWORK, 421 to VoiceFailure.NETWORK)) {
            MockWebServer().use { server ->
                server.start(); val f = VoiceTestFixture(temp.newFolder(), CanonicalOrigin.parse(server.url("/").toString()).getOrThrow()); f.initialize()
                server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", server.url("/forbidden"))
                    .addHeader("Retry-After", "0").setBody("synthetic private error"))
                val lease = (f.vault.captureWorkspaceRecoveryOwner() as WorkspaceRecoveryOwnerResult.Captured).owner.lease
                val error = runCatching { DefaultVoiceProtocolAdapter(f.auth, OkHttpVoiceTransport()).transcribe(f.origin, "default", lease, wav) }.exceptionOrNull()
                assertEquals("status $code", failure, (error as VoiceException).failure)
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun oversizedHttpBodyRejected() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val f = VoiceTestFixture(temp.newFolder(), CanonicalOrigin.parse(server.url("/").toString()).getOrThrow()); f.initialize()
            server.enqueue(MockResponse().setBody("x".repeat(VoiceLimits.MAX_RESPONSE_BYTES + 1)))
            val lease = (f.vault.captureWorkspaceRecoveryOwner() as WorkspaceRecoveryOwnerResult.Captured).owner.lease
            val failure = runCatching { DefaultVoiceProtocolAdapter(f.auth, OkHttpVoiceTransport()).transcribe(f.origin, "default", lease, wav) }.exceptionOrNull() as VoiceException
            assertEquals(VoiceFailure.INVALID, failure.failure)
        }
    }
    @Test fun staleProfileSessionAndEpochRejectBeforeTransport() = runBlocking {
        for (mutation in 0..2) {
            val f = VoiceTestFixture(temp.newFolder()); f.initialize()
            var calls = 0
            val adapter = object : VoiceProtocolAdapter {
                override suspend fun transcribe(origin: CanonicalOrigin, profile: String, lease: AuthCredentialLease, wav: ByteArray, onReceipt: (AuthCredentialLease) -> Unit): VoiceTranscription {
                    calls++; error("must not call transport")
                }
            }
            val repository = DefaultVoiceRepository(f.sessions, f.vault, f.auth, adapter)
            val owner = repository.capture("synthetic-stored", "synthetic-runtime")!!
            when (mutation) {
                0 -> f.vault.saveSelectedContextProfile("synthetic-other")
                1 -> f.sessions.activeSession.value = null
                2 -> f.vault.clearAuthCredential()
            }
            assertFalse(repository.isCurrent(owner))
            assertEquals(VoiceFailure.STALE, (repository.transcribe(owner, wav) as VoiceResult.Failed).failure)
            assertEquals(0, calls)
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun deadlineAndCancellationCancelHeaderCallWithoutRetry() = kotlinx.coroutines.test.runTest {
        for (cancel in listOf(false, true)) {
            val f = VoiceTestFixture(temp.newFolder(), dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)); f.initialize()
            var calls = 0; var cancelled = false
            val entered = CompletableDeferred<Unit>()
            val transport = object : VoiceHttpTransport {
                override fun open(origin: CanonicalOrigin, path: String, profile: String, payload: ByteArray,
                    headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<VoiceResponse> {
                    calls++
                    return object : AuthenticatedHttpCall<VoiceResponse> {
                        override suspend fun awaitHeaders(): VoiceResponse { entered.complete(Unit); awaitCancellation() }
                        override fun cancel() { cancelled = true }
                    }
                }
            }
            val lease = (f.vault.captureWorkspaceRecoveryOwner() as WorkspaceRecoveryOwnerResult.Captured).owner.lease
            val job = async { runCatching { DefaultVoiceProtocolAdapter(f.auth, transport).transcribe(f.origin, "default", lease, wav) } }
            entered.await()
            if (cancel) job.cancelAndJoin() else {
                advanceTimeBy(180_000); runCurrent()
                assertEquals(VoiceFailure.TIMEOUT, (job.await().exceptionOrNull() as VoiceException).failure)
            }
            assertTrue(cancelled); assertEquals(1, calls)
        }
    }
    @Test fun changedOwnerDuringResponseCannotPublishTranscript() = runBlocking {
        val f = VoiceTestFixture(temp.newFolder()); f.initialize()
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        val adapter = object : VoiceProtocolAdapter {
            override suspend fun transcribe(origin: CanonicalOrigin, profile: String, lease: AuthCredentialLease, wav: ByteArray, onReceipt: (AuthCredentialLease) -> Unit): VoiceTranscription {
                assertEquals("default", profile)
                entered.complete(Unit); released.await()
                return VoiceTranscription("obsolete", lease)
            }
        }
        val repository = DefaultVoiceRepository(f.sessions, f.vault, f.auth, adapter)
        val owner = repository.capture("synthetic-stored", "synthetic-runtime")!!
        val result = async { repository.transcribe(owner, wav) }
        entered.await(); f.vault.saveSelectedContextProfile("synthetic-other"); released.complete(Unit)
        assertEquals(VoiceFailure.STALE, (result.await() as VoiceResult.Failed).failure)
    }

    @Test fun acceptedCookieHandoffRemainsCurrentDuringHeldBodyButUnrelatedLoginCannotPublish() = runBlocking {
        for (replaceLogin in listOf(false, true)) {
            val f = VoiceTestFixture(temp.newFolder()); f.initialize()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val transport = object : VoiceHttpTransport {
                override fun open(origin: CanonicalOrigin, path: String, profile: String, payload: ByteArray,
                    headers: Map<String, String>, timeoutMillis: Long) = object : AuthenticatedHttpCall<VoiceResponse> {
                    override suspend fun awaitHeaders() = object : VoiceResponse {
                        override val statusCode = 200
                        override val headers = mapOf("Set-Cookie" to listOf("session=synthetic-successor; Path=/"))
                        override suspend fun readBoundedBody(): ByteArray {
                            entered.complete(Unit); release.await()
                            return """{"ok":true,"transcript":"synthetic words"}""".toByteArray()
                        }
                        override fun close() {}
                    }
                    override fun cancel() {}
                }
            }
            val repository = DefaultVoiceRepository(f.sessions, f.vault, f.auth, DefaultVoiceProtocolAdapter(f.auth, transport))
            val owner = repository.capture("synthetic-stored", "synthetic-runtime")!!
            val originalEpoch = owner.activity.authority.lease.epoch
            val result = async { repository.transcribe(owner, wav) }
            entered.await()
            assertNotEquals(originalEpoch, owner.activity.authority.lease.epoch)
            assertTrue(repository.isCurrent(owner))
            if (replaceLogin) f.vault.saveAuthCredential(AuthCredential("cookie_session", "basic",
                mapOf("session" to "synthetic-unrelated"), f.origin.originUrl))
            release.complete(Unit)
            val outcome = result.await()
            if (replaceLogin) assertEquals(VoiceFailure.STALE, (outcome as VoiceResult.Failed).failure)
            else { assertEquals("synthetic words", (outcome as VoiceResult.Transcript).text); assertTrue(repository.isCurrent(owner)) }
        }
    }

}
