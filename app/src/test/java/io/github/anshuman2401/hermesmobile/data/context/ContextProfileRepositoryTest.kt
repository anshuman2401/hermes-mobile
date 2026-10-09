package io.github.anshuman2401.hermesmobile.data.context

import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.HttpResponse
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartitionResult
import io.github.anshuman2401.hermesmobile.data.local.CacheReadResult
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * TASK-B5 / B1: Deterministic JVM tests for DefaultContextProfileRepository.
 * Verifies:
 * - Parsing sanitized array and object fixtures from GET /api/profiles and GET /api/profiles/active.
 * - Active workspace and running status determination.
 * - Fail-closed handling on unauthorized (401/403), unavailable (500/timeout), and malformed JSON.
 * - Error messages never leak raw hostnames, URLs, or credentials.
 * - Invariant: profile reads dispatch only GET requests; ordinary selection is covered separately.
 */
class ContextProfileRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var fakeTransport: FakeContextProfileTransport
    private lateinit var repository: DefaultContextProfileRepository

    private val origin = "http://127.0.0.1:9119"

    class FakeContextProfileTransport : AuthHttpTransport {
        val recordedRequests = mutableListOf<RecordedRequest>()
        var activeResponse = HttpResponse(200, body = """{"active":"default","current":"default"}""")
        var profilesResponse = HttpResponse(200, body = """[{"name":"default","running":true},{"name":"ca","running":false}]""")
        var activationResponse: HttpResponse = HttpResponse(200, body = """{"ok":true,"active":"architect"}""")
        var scopedSessionsResponse: HttpResponse = HttpResponse(200, body = """{"sessions":[]}""")
        var throwExceptionOnExecute: Exception? = null

        data class RecordedRequest(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: String?
        )

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?
        ): HttpResponse {
            recordedRequests.add(RecordedRequest(method, url, headers, body))
            throwExceptionOnExecute?.let { throw it }
            return when {
                url.contains("/api/profiles/sessions") -> scopedSessionsResponse
                url.endsWith("/api/profiles/active") -> activeResponse
                url.endsWith("/api/profiles") -> profilesResponse
                else -> HttpResponse(404, body = "Not Found")
            }
        }
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("context_profile_repo_test")
        vault = EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        fakeTransport = FakeContextProfileTransport()
        repository = DefaultContextProfileRepository(vaultStorage = vault, httpTransport = fakeTransport)

        // Seed an active connection credential
        runBlocking {
            vault.saveConnectionProfile(
                ConnectionProfile(
                    localId = "conn-1",
                    normalizedOrigin = origin,
                    displayLabel = "Test Server"
                )
            )
            vault.setActiveProfileId("conn-1")
            vault.saveAuthCredential(
                AuthCredential(
                    driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                    providerId = "password",
                    cookies = mapOf("session_id" to "sess-test-token"),
                    canonicalOrigin = origin
                ),
                profileLocalId = "conn-1"
            )
        }
    }

    @Test
    fun getProfiles_withArrayFixture_parsesNamesRunningAndActive() = runBlocking {
        fakeTransport.activeResponse = HttpResponse(200, body = """{"active":"default","current":"default"}""")
        fakeTransport.profilesResponse = HttpResponse(
            200,
            body = """[{"name":"default","running":true},{"name":"ca","running":false}]"""
        )

        val result = repository.getProfiles()
        assertTrue("Result must be Success", result is ContextProfilesResult.Success)
        val success = result as ContextProfilesResult.Success
        assertEquals("default", success.activeProfileName)
        assertEquals(2, success.profiles.size)

        val defaultProfile = success.profiles.first { it.name == "default" }
        assertTrue("default profile must be active", defaultProfile.isActive)
        assertTrue("default profile must be running", defaultProfile.isRunning)

        val caProfile = success.profiles.first { it.name == "ca" }
        assertFalse("ca profile must not be active", caProfile.isActive)
        assertFalse("ca profile must not be running", caProfile.isRunning)

        // Verify request invariants: all GET, correct cookie header, exact endpoints
        assertEquals(2, fakeTransport.recordedRequests.size)
        assertTrue(fakeTransport.recordedRequests.all { it.method == "GET" })
        assertTrue(fakeTransport.recordedRequests.all { it.headers["Cookie"] == "session_id=sess-test-token" })
        assertTrue(fakeTransport.recordedRequests.any { it.url == "$origin/api/profiles/active" })
        assertTrue(fakeTransport.recordedRequests.any { it.url == "$origin/api/profiles" })
    }

    @Test
    fun getProfiles_withObjectWrapperFixture_parsesCorrectly() = runBlocking {
        fakeTransport.activeResponse = HttpResponse(200, body = """{"active":"ca"}""")
        fakeTransport.profilesResponse = HttpResponse(
            200,
            body = """{"profiles":[{"name":"default","gateway_state":"running"},{"name":"ca","status":"running"}]}"""
        )

        val result = repository.getProfiles()
        assertTrue("Result must be Success", result is ContextProfilesResult.Success)
        val success = result as ContextProfilesResult.Success
        assertEquals("ca", success.activeProfileName)
        assertEquals(2, success.profiles.size)

        val defaultProfile = success.profiles.first { it.name == "default" }
        assertFalse("default profile must not be active", defaultProfile.isActive)
        assertTrue("default profile must be running", defaultProfile.isRunning)

        val caProfile = success.profiles.first { it.name == "ca" }
        assertTrue("ca profile must be active", caProfile.isActive)
        assertTrue("ca profile must be running", caProfile.isRunning)
    }

    @Test
    fun getProfiles_withStringArrayFixture_parsesCorrectly() = runBlocking {
        fakeTransport.activeResponse = HttpResponse(200, body = """"staging"""")
        fakeTransport.profilesResponse = HttpResponse(200, body = """["default","staging"]""")

        val result = repository.getProfiles()
        assertTrue(result is ContextProfilesResult.Success)
        val success = result as ContextProfilesResult.Success
        assertEquals("staging", success.activeProfileName)
        assertEquals(2, success.profiles.size)
        assertTrue(success.profiles.first { it.name == "staging" }.isActive)
        assertFalse(success.profiles.first { it.name == "default" }.isActive)
    }

    @Test
    fun getProfiles_whenActiveProfileUnauthorized_failsClosedWithSafeMessage() = runBlocking {
        fakeTransport.activeResponse = HttpResponse(401, body = "Unauthorized")

        val result = repository.getProfiles()
        assertTrue("Must fail closed with Error", result is ContextProfilesResult.Error)
        val error = result as ContextProfilesResult.Error
        assertEquals(ContextProfileErrorReason.UNAUTHORIZED, error.reason)
        assertFalse("Error message must not contain host origin", error.safeMessage.contains(origin))
    }

    @Test
    fun getProfiles_whenProfilesListUnauthorized_failsClosedWithSafeMessage() = runBlocking {
        fakeTransport.activeResponse = HttpResponse(200, body = """{"active":"default"}""")
        fakeTransport.profilesResponse = HttpResponse(403, body = "Forbidden")

        val result = repository.getProfiles()
        assertTrue("Must fail closed with Error", result is ContextProfilesResult.Error)
        val error = result as ContextProfilesResult.Error
        assertEquals(ContextProfileErrorReason.UNAUTHORIZED, error.reason)
        assertFalse("Error message must not contain host origin", error.safeMessage.contains(origin))
    }

    @Test
    fun getProfiles_whenMalformedJson_failsClosedWithSafeMessage() = runBlocking {
        fakeTransport.activeResponse = HttpResponse(200, body = """{"active":"default"}""")
        fakeTransport.profilesResponse = HttpResponse(200, body = "{not valid json at all")

        val result = repository.getProfiles()
        assertTrue("Must fail closed with Error", result is ContextProfilesResult.Error)
        val error = result as ContextProfilesResult.Error
        assertEquals(ContextProfileErrorReason.MALFORMED, error.reason)
        assertFalse("Error message must not leak raw body", error.safeMessage.contains("not valid"))
        assertFalse("Error message must not contain origin", error.safeMessage.contains(origin))
    }

    @Test
    fun getProfiles_whenHttp500_failsClosedWithSafeMessage() = runBlocking {
        fakeTransport.activeResponse = HttpResponse(500, body = "Internal Server Error")

        val result = repository.getProfiles()
        assertTrue("Must fail closed with Error", result is ContextProfilesResult.Error)
        val error = result as ContextProfilesResult.Error
        assertEquals(ContextProfileErrorReason.UNAVAILABLE, error.reason)
        assertFalse("Error message must not leak origin", error.safeMessage.contains(origin))
    }

    @Test
    fun getProfiles_whenNetworkException_failsClosedWithSafeMessage() = runBlocking {
        fakeTransport.throwExceptionOnExecute = java.net.SocketTimeoutException("Timeout")

        val result = repository.getProfiles()
        assertTrue("Must fail closed with Error", result is ContextProfilesResult.Error)
        val error = result as ContextProfilesResult.Error
        assertEquals(ContextProfileErrorReason.UNAVAILABLE, error.reason)
        assertFalse("Error message must not leak origin", error.safeMessage.contains(origin))
    }

    @Test
    fun getProfiles_whenNoConnection_failsClosed() = runBlocking {
        // Clear auth credential so no connection is available
        vault.clearAuthCredential("conn-1")

        val result = repository.getProfiles()
        assertTrue("Must fail closed with Error", result is ContextProfilesResult.Error)
        val error = result as ContextProfilesResult.Error
        assertEquals(ContextProfileErrorReason.NO_ACTIVE_CONNECTION, error.reason)
    }

    @Test
    fun getProfiles_neverDispatchesMutations() = runBlocking {
        repository.getProfiles()
        for (request in fakeTransport.recordedRequests) {
            assertEquals("All requests must be GET", "GET", request.method)
            assertFalse("Never dispatch POST", request.method == "POST")
            assertFalse("Never dispatch PUT", request.method == "PUT")
            assertFalse("Never dispatch DELETE", request.method == "DELETE")
        }
    }

    @Test
    fun getProfiles_fallbackToHost_whenNoPreferenceSet() = runBlocking {
        assertNull(repository.getSelectedProfile())
        fakeTransport.activeResponse = HttpResponse(200, body = """{"active":"default"}""")
        fakeTransport.profilesResponse = HttpResponse(
            200,
            body = """[{"name":"default","running":true},{"name":"ca","running":false}]"""
        )

        val result = repository.getProfiles()
        assertTrue(result is ContextProfilesResult.Success)
        val success = result as ContextProfilesResult.Success
        assertEquals("default", success.activeProfileName)
        assertTrue(success.profiles.first { it.name == "default" }.isActive)
        assertFalse(success.profiles.first { it.name == "ca" }.isActive)
    }

    @Test
    fun getProfiles_usesPersistedPreference_whenPreferenceSet() = runBlocking {
        repository.setSelectedProfile("ca")
        assertEquals("ca", repository.getSelectedProfile())

        // Host says "default" is active, but persisted app preference is "ca"
        fakeTransport.activeResponse = HttpResponse(200, body = """{"active":"default"}""")
        fakeTransport.profilesResponse = HttpResponse(
            200,
            body = """[{"name":"default","running":true},{"name":"ca","running":false}]"""
        )

        val result = repository.getProfiles()
        assertTrue(result is ContextProfilesResult.Success)
        val success = result as ContextProfilesResult.Success
        assertEquals("ca", success.activeProfileName)
        assertTrue("ca must be active due to preference", success.profiles.first { it.name == "ca" }.isActive)
        assertFalse("default must not be active", success.profiles.first { it.name == "default" }.isActive)
    }

    @Test
    fun getScopedSessions_success_parsesSummaries_andRedactsPreview() = runBlocking {
        fakeTransport.scopedSessionsResponse = HttpResponse(
            200,
            body = """{
                "sessions": [
                    {
                        "id": "sess-arch-1",
                        "title": "Architecture discussion",
                        "preview": "token=secret-token-here",
                        "started_at": 1726000000.0,
                        "message_count": 5,
                        "source": "android"
                    }
                ],
                "total": 1
            }"""
        )

        val result = repository.getScopedSessions("architect", limit = 50)
        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        val session = list[0]
        assertEquals("sess-arch-1", session.id)
        assertEquals("Architecture discussion", session.title)
        assertEquals("token=[REDACTED]", session.preview)
        assertEquals(1726000000.0, session.startedAt)
        assertEquals(5, session.messageCount)
        assertEquals("android", session.source)

        // Verify request parameters and headers
        val req = fakeTransport.recordedRequests.first { it.url.contains("/api/profiles/sessions") }
        assertEquals("GET", req.method)
        assertTrue(req.url.contains("profile=architect"))
        assertTrue(req.url.contains("limit=50"))
        assertEquals("session_id=sess-test-token", req.headers["Cookie"])
    }

    @Test
    fun getScopedSessions_whenRealUnauthorized_mapsTo401FixedMessage() = runBlocking {
        fakeTransport.scopedSessionsResponse = HttpResponse(401, body = "Unauthorized")

        val result = repository.getScopedSessions("architect")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Real 401 must stay a SecurityException", ex is SecurityException)
        assertEquals(
            "Real 401 must keep its fixed 401 mapping string",
            "Authentication expired or unauthorized (401)",
            ex!!.message
        )
    }

    @Test
    fun getScopedSessions_whenOriginPolicySecurityException_mapsToDistinctOriginPolicyMessage() = runBlocking {
        fakeTransport.throwExceptionOnExecute =
            SecurityException("Rejected request URL because its origin is not canonical.")

        val result = repository.getScopedSessions("architect")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Origin-policy rejection must remain fail-closed SecurityException", ex is SecurityException)
        assertEquals(
            "Origin-policy rejection must surface the distinct fixed message",
            "Request rejected by origin policy",
            ex!!.message
        )
        assertFalse(
            "Raw exception text must never surface",
            (ex.message ?: "").contains("canonical")
        )
    }

    @Test
    fun getScopedSessions_whenHttp500_failsWithIllegalStateException() = runBlocking {
        fakeTransport.scopedSessionsResponse = HttpResponse(500, body = "Server Error")

        val result = repository.getScopedSessions("architect")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun getScopedSessions_whenMalformedJson_failsWithIllegalStateException() = runBlocking {
        fakeTransport.scopedSessionsResponse = HttpResponse(200, body = "{not-json")

        val result = repository.getScopedSessions("architect")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun getScopedSessions_whenInvalidProfileName_failsWithIllegalArgumentException() = runBlocking {
        val result = repository.getScopedSessions("bad name with spaces!")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun getScopedSessions_whenNetworkException_failsClosedWithSafeTypedException() = runBlocking {
        // Raw OkHttp/transport exceptions embed the host IP, e.g.
        // "Failed to connect to /127.0.0.1:9119". The catch block must convert
        // them to a fixed safe typed error mirroring the activation path — the
        // raw exception (and its message) must never propagate to the UI.
        fakeTransport.throwExceptionOnExecute =
            java.net.ConnectException("Failed to connect to /127.0.0.1:9119")

        val result = repository.getScopedSessions("architect")
        assertTrue("Result must be failure", result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue("Failure must be IllegalStateException", exception is IllegalStateException)
        val message = exception?.message ?: ""
        assertTrue("Message must be the fixed safe string", message == "Context profiles service unavailable")
        assertFalse("Error message must not leak host/IP", message.contains("127.0.0.1"))
        assertFalse("Error message must not leak raw exception text", message.contains("Failed to connect"))
    }

    // ---- PR-2 TEST-07: offline roster cache integration ----

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun resolvedPartition(): io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartition =
        (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    @Test
    fun getProfiles_captureWritesRosterUnderResolvedScope_noUnscopedFallback() = runBlocking {
        // Unscoped capture first (no local selection).
        assertNull(vault.getSelectedContextProfile())
        val unscopedPartition = resolvedPartition()
        val unscopedLive = repository.getProfiles()
        assertTrue(unscopedLive is ContextProfilesResult.Success)
        assertEquals(
            RosterSource.LIVE,
            (unscopedLive as ContextProfilesResult.Success).source
        )
        assertTrue(vault.readContextProfileCache(unscopedPartition) is CacheReadResult.Available)

        // Selecting a workspace resolves a NEW scope; before its capture lands,
        // an offline load must fail closed — never fall back to the unscoped roster.
        assertTrue(repository.selectProfile("ca") is ContextProfileSelectionResult.Success)
        val scopedPartition = resolvedPartition()
        assertEquals("w:" + sha256Hex("ca"), scopedPartition.workspaceScopeTag)

        fakeTransport.throwExceptionOnExecute = java.net.SocketTimeoutException("Timeout")
        val scopedOfflineWithoutCapture = repository.getProfiles()
        assertTrue(
            "No selected->unscoped roster fallback is allowed",
            scopedOfflineWithoutCapture is ContextProfilesResult.Error
        )
        fakeTransport.throwExceptionOnExecute = null

        // Selection changed between calls: the next live capture writes the NEW
        // scope only (staging appears only in the scoped roster's payload).
        fakeTransport.profilesResponse = HttpResponse(
            200,
            body = """[{"name":"default","running":true},{"name":"ca","running":false},{"name":"staging","running":false}]"""
        )
        val scopedLive = repository.getProfiles()
        assertTrue(scopedLive is ContextProfilesResult.Success)
        assertEquals(
            RosterSource.LIVE,
            (scopedLive as ContextProfilesResult.Success).source
        )
        assertTrue(vault.readContextProfileCache(scopedPartition) is CacheReadResult.Available)

        // Offline under the scoped partition serves the scoped roster.
        fakeTransport.throwExceptionOnExecute = java.net.SocketTimeoutException("Timeout")
        val scopedOffline = repository.getProfiles()
        assertTrue(scopedOffline is ContextProfilesResult.Success)
        val cached = scopedOffline as ContextProfilesResult.Success
        assertEquals(RosterSource.CACHED, cached.source)
        assertTrue(
            "The scoped capture must be the served roster",
            cached.profiles.any { it.name == "staging" }
        )
        // Cache load never mutates the local selection.
        assertEquals("ca", vault.getSelectedContextProfile())
    }

    @Test
    fun getProfiles_everyLiveFailure_withSeededCache_servesLabelledCachedSuccess() = runBlocking {
        // Seed one valid capture (LIVE writes the roster after both GETs parse).
        val live = repository.getProfiles()
        assertTrue(live is ContextProfilesResult.Success)
        val requestsAfterSeed = fakeTransport.recordedRequests.size

        val failures: List<Pair<String, () -> Unit>> = listOf(
            "unauthorized active GET" to {
                fakeTransport.activeResponse = HttpResponse(401, body = "Unauthorized")
                fakeTransport.profilesResponse = HttpResponse(
                    200,
                    body = """[{"name":"default","running":true},{"name":"ca","running":false}]"""
                )
            },
            "forbidden roster GET" to {
                fakeTransport.activeResponse =
                    HttpResponse(200, body = """{"active":"default","current":"default"}""")
                fakeTransport.profilesResponse = HttpResponse(403, body = "Forbidden")
            },
            "server error" to {
                fakeTransport.activeResponse = HttpResponse(500, body = "Server error")
                fakeTransport.profilesResponse = HttpResponse(
                    200,
                    body = """[{"name":"default","running":true},{"name":"ca","running":false}]"""
                )
            },
            "transport exception" to {
                fakeTransport.activeResponse =
                    HttpResponse(200, body = """{"active":"default","current":"default"}""")
                fakeTransport.throwExceptionOnExecute =
                    java.net.ConnectException("Failed to connect to /127.0.0.1:9119")
            },
            "malformed body" to {
                fakeTransport.throwExceptionOnExecute = null
                fakeTransport.activeResponse = HttpResponse(200, body = "{not json")
            }
        )

        // Transport cost per branch: an active-profile-GET failure stops after
        // attempt 1; a roster-GET failure costs the successful active GET plus
        // the failing roster GET. The cached read itself issues none.
        val expectedAttempts = mapOf(
            "unauthorized active GET" to 1,
            "forbidden roster GET" to 2,
            "server error" to 1,
            "transport exception" to 1,
            "malformed body" to 1
        )

        for ((label, breakTransport) in failures) {
            breakTransport()
            val requestsBefore = fakeTransport.recordedRequests.size
            val result = repository.getProfiles()
            assertTrue("$label must fall back to the cached roster", result is ContextProfilesResult.Success)
            val cached = result as ContextProfilesResult.Success
            assertEquals("$label must be labelled CACHED", RosterSource.CACHED, cached.source)
            assertEquals(
                "$label must keep the captured local label",
                "default",
                cached.activeProfileName
            )
            assertEquals(
                "$label: only this branch's live attempt(s) count — the cached read adds zero",
                requestsBefore + expectedAttempts.getValue(label),
                fakeTransport.recordedRequests.size
            )
        }

        // Fallback keeps the flow read-only end to end.
        assertTrue(
            "Every attempt across all failure branches is read-only",
            fakeTransport.recordedRequests.all { it.method == "GET" }
        )
        assertEquals(
            "Every attempt across all failure branches is read-only, none from cached reads",
            requestsAfterSeed + expectedAttempts.values.sum(),
            fakeTransport.recordedRequests.size
        )
    }

}
