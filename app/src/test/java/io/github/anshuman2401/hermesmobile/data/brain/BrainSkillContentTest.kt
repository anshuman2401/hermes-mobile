package io.github.anshuman2401.hermesmobile.data.brain

import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrainSkillContentTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun contentResultDoesNotExposeDocumentInToString() {
        val result = BrainSkillsContentResult.Available(
            selectedProfile = "default",
            name = "demo-one",
            content = "private synthetic content"
        )

        assertFalse(result.toString().contains("private synthetic content"))
        assertTrue(result.toString().contains("redacted"))
    }

    @Test
    fun emptyContentIsAnAcceptedAvailableValue() {
        val result = BrainSkillsContentResult.Available("default", "demo-one", "")
        assertTrue(result.content.isEmpty())
    }

    @Test
    fun contentUsesNamedAndLiteralDefaultProfilesAndPreservesEncodedFullTail() = runBlocking {
        val name = "space skill/σ?x"
        val content = "# heading\r\n\r\nfull tail 😀 [literal](https://invalid.example)"
        Fixture(temp.newFolder("named")).use { fixture ->
            fixture.select("worker_alpha")
            fixture.server.enqueue(MockResponse().setBody(inventory(name)))
            val loaded = fixture.repository.load() as BrainSkillsLoadResult.Available
            fixture.server.enqueue(MockResponse().setBody(contentResponse(name, content)))

            val result = fixture.repository.readContent(loaded.snapshot.snapshotId, name)
            assertEquals(content, (result as BrainSkillsContentResult.Available).content)
            assertEquals("worker_alpha", result.selectedProfile)
            assertEquals(name, result.name)
            assertEquals("GET", fixture.server.takeRequest()!!.method)
            assertContentRequest(fixture.server.takeRequest(), "worker_alpha", name)
            assertEquals(2, fixture.server.requestCount)
        }

        Fixture(temp.newFolder("default")).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody(inventory("literal-default")))
            val loaded = fixture.repository.load() as BrainSkillsLoadResult.Available
            fixture.server.enqueue(MockResponse().setBody(contentResponse("literal-default", "default body")))

            val result = fixture.repository.readContent(loaded.snapshot.snapshotId, "literal-default")
            assertEquals("default body", (result as BrainSkillsContentResult.Available).content)
            assertEquals("default", result.selectedProfile)
            assertEquals("GET", fixture.server.takeRequest()!!.method)
            assertContentRequest(fixture.server.takeRequest(), "default", "literal-default")
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test
    fun contentAcceptsEmptyAndWhitespaceWithoutChangingTheLoadedSnapshot() = runBlocking {
        Fixture(temp.newFolder("empty-whitespace")).use { fixture ->
            val name = "ordinary-skill"
            fixture.server.enqueue(MockResponse().setBody(inventory(name)))
            val loaded = fixture.repository.load() as BrainSkillsLoadResult.Available
            fixture.server.enqueue(MockResponse().setBody(contentResponse(name, "")))
            fixture.server.enqueue(MockResponse().setBody(contentResponse(name, " \r\n\t")))

            val empty = fixture.repository.readContent(loaded.snapshot.snapshotId, name)
            val whitespace = fixture.repository.readContent(loaded.snapshot.snapshotId, name)
            assertEquals("", (empty as BrainSkillsContentResult.Available).content)
            assertEquals(" \r\n\t", (whitespace as BrainSkillsContentResult.Available).content)
            assertEquals(3, fixture.server.requestCount)
            assertEquals("GET", fixture.server.takeRequest()!!.method)
            assertContentRequest(fixture.server.takeRequest(), "default", name)
            assertContentRequest(fixture.server.takeRequest(), "default", name)
        }
    }

    @Test
    fun nonMemberAndInvalidatedSnapshotsMakeNoContentRequest() = runBlocking {
        Fixture(temp.newFolder("eligibility")).use { fixture ->
            val name = "ordinary-skill"
            fixture.server.enqueue(MockResponse().setBody(inventory(name)))
            val loaded = fixture.repository.load() as BrainSkillsLoadResult.Available

            val nonMember = fixture.repository.readContent(loaded.snapshot.snapshotId, "not-installed")
            val obsolete = fixture.repository.readContent(loaded.snapshot.snapshotId + 1, name)
            assertEquals(BrainSkillsContentFailureReason.STALE, (nonMember as BrainSkillsContentResult.Unavailable).reason)
            assertEquals(BrainSkillsContentFailureReason.STALE, (obsolete as BrainSkillsContentResult.Unavailable).reason)
            assertEquals(1, fixture.server.requestCount)

            fixture.select("worker_alpha")
            val invalidated = fixture.repository.readContent(loaded.snapshot.snapshotId, name)
            assertEquals(BrainSkillsContentFailureReason.STALE, (invalidated as BrainSkillsContentResult.Unavailable).reason)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun notFoundRotatesCookieAndMalformedContentIsRejectedWithoutNewEligibility() = runBlocking {
        Fixture(temp.newFolder("failures")).use { fixture ->
            val name = "ordinary-skill"
            val content = "final-tail-without-newline"
            fixture.server.enqueue(MockResponse().setBody(inventory(name)))
            val loaded = fixture.repository.load() as BrainSkillsLoadResult.Available
            fixture.server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .addHeader("Set-Cookie", "session=synthetic-successor; Path=/")
            )
            fixture.server.enqueue(MockResponse().setBody(contentResponse("different-name", content)))
            fixture.server.enqueue(MockResponse().setBody(contentResponse(name, 7)))
            fixture.server.enqueue(MockResponse().setBody("not-json"))
            fixture.server.enqueue(MockResponse().setBody(contentResponse(name, content)))
            val snapshotId = loaded.snapshot.snapshotId

            val missing = fixture.repository.readContent(snapshotId, name)
            val wrongName = fixture.repository.readContent(snapshotId, name)
            val wrongType = fixture.repository.readContent(snapshotId, name)
            val malformed = fixture.repository.readContent(snapshotId, name)
            val retried = fixture.repository.readContent(snapshotId, name)

            assertEquals(BrainSkillsContentFailureReason.MISSING, (missing as BrainSkillsContentResult.Unavailable).reason)
            assertEquals(BrainSkillsContentFailureReason.MALFORMED, (wrongName as BrainSkillsContentResult.Unavailable).reason)
            assertEquals(BrainSkillsContentFailureReason.MALFORMED, (wrongType as BrainSkillsContentResult.Unavailable).reason)
            assertEquals(BrainSkillsContentFailureReason.MALFORMED, (malformed as BrainSkillsContentResult.Unavailable).reason)
            assertEquals(content, (retried as BrainSkillsContentResult.Available).content)
            assertEquals("default", retried.selectedProfile)
            assertEquals(name, retried.name)
            assertEquals(6, fixture.server.requestCount)

            fixture.server.takeRequest()
            val firstContentRequest = fixture.server.takeRequest()
            assertContentRequest(firstContentRequest, "default", name)
            val retryAfter404 = fixture.server.takeRequest()
            assertEquals("session=synthetic-successor", retryAfter404.getHeader("Cookie"))
            assertContentRequest(retryAfter404, "default", name)
            assertContentRequest(fixture.server.takeRequest(), "default", name)
            assertContentRequest(fixture.server.takeRequest(), "default", name)
        }
    }

    private class Fixture(folder: java.io.File) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
        val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        val auth: AuthRepository = DefaultAuthRepository(vault, OkHttpAuthTransport())
        val repository = BrainSkillsRepository(vault, auth)

        init {
            runBlocking {
                vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
                vault.setActiveProfileId("synthetic-local")
                vault.saveAuthCredential(
                    AuthCredential(
                        "cookie_session",
                        "basic",
                        mapOf("session" to "synthetic-cookie"),
                        origin.originUrl
                    )
                )
                vault.saveSelectedContextProfile("default")
            }
        }

        fun select(profile: String) = runBlocking { vault.saveSelectedContextProfile(profile) }

        override fun close() {
            server.shutdown()
        }
    }

    private fun inventory(name: String): String = GatewayJson.serialize(
        GatewayJson.fromAny(
            listOf(
                linkedMapOf(
                    "name" to name,
                    "description" to "Synthetic description",
                    "enabled" to true,
                    "ignored" to "metadata"
                )
            )
        )
    )

    private fun contentResponse(name: String, content: Any): String = GatewayJson.serialize(
        GatewayJson.fromAny(
            linkedMapOf(
                "name" to name,
                "content" to content,
                "path" to "<synthetic-private-path>",
                "extra" to "ignored"
            )
        )
    )

    private fun assertContentRequest(request: okhttp3.mockwebserver.RecordedRequest?, profile: String, name: String) {
        assertTrue(request != null)
        assertEquals("GET", request!!.method)
        assertEquals("/api/skills/content", request.requestUrl?.encodedPath)
        assertEquals(profile, request.requestUrl?.queryParameter("profile"))
        assertEquals(name, request.requestUrl?.queryParameter("name"))
        assertEquals(2, request.requestUrl?.querySize)
        assertEquals(setOf("profile", "name"), request.requestUrl?.queryParameterNames)
        assertFalse(request.requestUrl?.queryParameter("extra") != null)
    }
}
