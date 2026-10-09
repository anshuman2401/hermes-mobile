package com.hermes.bot.data.files

import com.hermes.bot.data.auth.*
import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.voice.VoiceTestFixture
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class FileProtocolTest {
    @get:Rule val temp = TemporaryFolder()
    private val reference get() = ConversationFileReferences.reference("reports/synthetic brief.pdf")!!
    @Test fun sessionProfileRoutingCookieSuccessorAndBinaryBytes() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val fixture = VoiceTestFixture(temp.newFolder(), CanonicalOrigin.parse(server.url("/").toString()).getOrThrow())
            fixture.initialize(); fixture.vault.saveSelectedContextProfile("synthetic-profile")
            server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0, 1, -1, 5))).addHeader("Set-Cookie", "session=synthetic-new; Path=/"))
            val repository = DefaultConversationFileRepository(fixture.sessions, fixture.vault, fixture.auth, DefaultFileProtocolAdapter(fixture.auth, OkHttpFileTransport()))
            val owner = repository.capture("synthetic-stored", "synthetic-runtime")!!
            val content = repository.download(owner, reference)
            assertArrayEquals(byteArrayOf(0, 1, -1, 5), content.bytes)
            assertTrue(repository.isCurrent(owner))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method); assertEquals("/api/fs/download", request.requestUrl!!.encodedPath)
            assertEquals("synthetic-stored", request.requestUrl!!.queryParameter("session_id"))
            assertEquals("synthetic-profile", request.requestUrl!!.queryParameter("profile"))
            assertEquals("reports/synthetic brief.pdf", request.requestUrl!!.queryParameter("path"))
            assertEquals("session=synthetic-old", request.getHeader("Cookie"))
            assertEquals("synthetic-new", fixture.vault.getAuthCredential()!!.cookies["session"])
            content.discard(); assertArrayEquals(ByteArray(4), content.bytes)
        }
    }
    @Test fun errorsRedirectsAndRetryAfterNeverFollowOrExposeServerBody() = runBlocking {
        for (code in listOf(302, 307, 401, 403, 404, 413, 503, 421)) MockWebServer().use { server ->
            server.start(); val fixture = VoiceTestFixture(temp.newFolder(), CanonicalOrigin.parse(server.url("/").toString()).getOrThrow()); fixture.initialize()
            server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", "https://example.test/forbidden")
                .addHeader("Retry-After", "0").setBody("synthetic-private-error"))
            val lease = (fixture.vault.captureWorkspaceRecoveryOwner() as WorkspaceRecoveryOwnerResult.Captured).owner.lease
            val failure = runCatching { DefaultFileProtocolAdapter(fixture.auth, OkHttpFileTransport()).download(fixture.origin, "default", "synthetic-stored", lease, reference) {} }.exceptionOrNull()
            assertTrue(failure is ConversationFileException)
            assertFalse(failure.toString().contains("synthetic-private-error")); assertEquals(1, server.requestCount)
        }
    }
    @Test fun decompressedBodyBoundRejectsBeforePublication() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val f = VoiceTestFixture(temp.newFolder(), CanonicalOrigin.parse(server.url("/").toString()).getOrThrow()); f.initialize()
            server.enqueue(MockResponse().setChunkedBody("x".repeat(129), 8))
            val lease = (f.vault.captureWorkspaceRecoveryOwner() as WorkspaceRecoveryOwnerResult.Captured).owner.lease
            val failure = runCatching { DefaultFileProtocolAdapter(f.auth, OkHttpFileTransport(maxBodyBytes = 128)).download(f.origin, "default", "synthetic-stored", lease, reference) {} }.exceptionOrNull()
            assertEquals(FileFailure.TOO_LARGE, (failure as ConversationFileException).failure)
        }
    }
    @Test fun staleSessionProfileAuthAndConnectionRejectWithoutTransport() = runBlocking {
        for (mutation in 0..3) {
            val f = VoiceTestFixture(temp.newFolder()); f.initialize()
            var calls = 0
            val adapter = object : FileProtocolAdapter {
                override suspend fun download(origin: CanonicalOrigin, profile: String, sessionId: String, lease: AuthCredentialLease,
                    reference: ConversationFile, onReceipt: (AuthCredentialLease) -> Unit): FileDownload { calls++; error("Unexpected download") }
            }
            val repo = DefaultConversationFileRepository(f.sessions, f.vault, f.auth, adapter)
            val owner = repo.capture("synthetic-stored", "synthetic-runtime")!!
            when(mutation) { 0 -> f.sessions.activeSession.value = null; 1 -> f.vault.saveSelectedContextProfile("other"); 2 -> f.vault.clearAuthCredential(); 3 -> f.sessions.scopeCurrent = false }
            val failure = runCatching { repo.download(owner, reference) }.exceptionOrNull()
            assertEquals(FileFailure.STALE, (failure as ConversationFileException).failure); assertEquals(0, calls)
        }
    }
    @Test fun requestTimeoutReturnsRetryableFailureAndCancelsTransport() = runBlocking {
        val f = VoiceTestFixture(temp.newFolder()); f.initialize()
        var cancelled = false
        val transport = object : FileHttpTransport {
            override fun open(origin: CanonicalOrigin, path: String, profile: String, sessionId: String,
                reference: ConversationFile, headers: Map<String, String>, timeoutMillis: Long) = object : AuthenticatedHttpCall<FileResponse> {
                override suspend fun awaitHeaders(): FileResponse = try { awaitCancellation() } finally { cancelled = true }
                override fun cancel() { cancelled = true }
            }
        }
        val lease = (f.vault.captureWorkspaceRecoveryOwner() as WorkspaceRecoveryOwnerResult.Captured).owner.lease
        val failure = runCatching { DefaultFileProtocolAdapter(f.auth, transport, timeoutMillis = 5_000).download(f.origin, "default", "synthetic-stored", lease, reference) {} }.exceptionOrNull()
        assertEquals(FileFailure.NETWORK, (failure as ConversationFileException).failure)
        assertTrue(cancelled)
    }
    @Test fun lateResponseAfterOwnerChangeIsZeroed() = runBlocking {
        val f = VoiceTestFixture(temp.newFolder()); f.initialize()
        val bytes = "synthetic".toByteArray()
        val adapter = object : FileProtocolAdapter {
            override suspend fun download(origin: CanonicalOrigin, profile: String, sessionId: String, lease: AuthCredentialLease,
                reference: ConversationFile, onReceipt: (AuthCredentialLease) -> Unit): FileDownload {
                f.sessions.activeSession.value = null
                return FileDownload(FileContent(reference, bytes), lease)
            }
        }
        val repo = DefaultConversationFileRepository(f.sessions, f.vault, f.auth, adapter)
        val owner = repo.capture("synthetic-stored", "synthetic-runtime")!!
        assertTrue(runCatching { repo.download(owner, reference) }.isFailure)
        assertArrayEquals(ByteArray(bytes.size), bytes)
    }
}
