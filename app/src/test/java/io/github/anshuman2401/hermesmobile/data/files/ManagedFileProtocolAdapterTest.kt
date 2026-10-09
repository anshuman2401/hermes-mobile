package io.github.anshuman2401.hermesmobile.data.files

import io.github.anshuman2401.hermesmobile.data.auth.AuthenticatedHttpCall
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ManagedFileProtocolAdapterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val origin = CanonicalOrigin.parse("http://example.test:9119").getOrThrow()
    private fun owner() = runBlocking {
        val vault = EncryptedVault(temporary.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        vault.saveConnectionProfile(ConnectionProfile("synthetic-owner", origin.originUrl, "Synthetic"))
        vault.setActiveProfileId("synthetic-owner")
        vault.saveAuthCredential(AuthCredential("cookie_session", "basic",
            mapOf("session" to "synthetic-cookie"), origin.originUrl))
        val lease = vault.readAuthCredentialLease(origin.originUrl)!!
        DefaultAuthRepository(vault) to lease
    }
    private class Response(private val status: Int, private val body: String) : ManagedResponse {
        override val statusCode = status
        override val headers: Map<String, List<String>> = emptyMap()
        var closed = false
        override fun close() { closed = true }
        override suspend fun readBounded(limit: Int): ByteArray {
            val bytes = body.toByteArray()
            if (bytes.size > limit) throw IOException("synthetic oversized body")
            return bytes
        }
        override suspend fun copyBounded(destination: File, limit: Long): Long {
            val bytes = body.toByteArray()
            if (bytes.size > limit) throw ManagedFileException(ManagedFileFailure.TOO_LARGE)
            destination.writeBytes(bytes)
            return bytes.size.toLong()
        }
    }
    private class Transport : ManagedFileTransport {
        data class Sent(val url: String, val method: String, val target: String?, val overwrite: Boolean)
        val sent = mutableListOf<Sent>()
        var response = Response(200, "{}")
        var fail = false
        override fun open(url: String, method: String, headers: Map<String, String>,
            source: ManagedUploadSource?, target: String?, overwrite: Boolean): AuthenticatedHttpCall<ManagedResponse> {
            assertEquals("session=synthetic-cookie", headers["Cookie"])
            sent += Sent(url, method, target, overwrite)
            return object : AuthenticatedHttpCall<ManagedResponse> {
                override suspend fun awaitHeaders(): ManagedResponse {
                    if (fail) throw IOException("synthetic failure")
                    return response
                }
                override fun cancel() { response.close() }
            }
        }
    }

    @Test fun listUsesExactOriginAndEncodedHostPath() = runBlocking {
        val (auth, lease) = owner()
        val transport = Transport().apply { response = Response(200,
            """{"path":"/workspace","parent":"/","locked_root":"/","can_change_path":true,"entries":[]}""") }
        val listing = DefaultManagedFileProtocolAdapter(auth, transport).list(origin, lease, "/workspace")
        assertEquals("/workspace", listing.listing.path)
        assertEquals("GET", transport.sent.single().method)
        assertEquals("http://example.test:9119/api/files?path=%2Fworkspace", transport.sent.single().url)
        assertTrue(transport.response.closed)
    }

    @Test fun definiteConflictAndAmbiguousWriteAreSingleAttempts() = runBlocking {
        val (auth, lease) = owner()
        val transport = Transport().apply { response = Response(409, "{}") }
        val adapter = DefaultManagedFileProtocolAdapter(auth, transport)
        val source = ManagedUploadSource("note.txt", 4) { "test".byteInputStream() }
        val conflict = assertThrows(ManagedFileException::class.java) {
            runBlocking { adapter.upload(origin, lease, "/workspace/note.txt", source, false) }
        }
        assertEquals(ManagedFileFailure.CONFLICT, conflict.failure)
        assertEquals(1, transport.sent.size)
        assertEquals(false, transport.sent.single().overwrite)
        transport.fail = true
        val unknown = assertThrows(ManagedFileException::class.java) {
            runBlocking { adapter.upload(origin, lease, "/workspace/note.txt", source, true) }
        }
        assertEquals(ManagedFileFailure.UNKNOWN, unknown.failure)
        assertEquals(2, transport.sent.size)
    }

    @Test fun downloadPassesPreviewLimitToBodyReader() = runBlocking {
        val (auth, lease) = owner()
        val transport = Transport().apply { response = Response(200, "some bytes") }
        val target = temporary.newFile()
        val failure = assertThrows(ManagedFileException::class.java) {
            runBlocking { DefaultManagedFileProtocolAdapter(auth, transport).download(origin, lease,
                "/workspace/note.txt", target, 4) }
        }
        assertEquals(ManagedFileFailure.TOO_LARGE, failure.failure)
        assertEquals(1, transport.sent.size)
        assertTrue(transport.response.closed)
    }
}
