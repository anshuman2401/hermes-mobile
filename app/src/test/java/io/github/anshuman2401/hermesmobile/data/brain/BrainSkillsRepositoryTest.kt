package io.github.anshuman2401.hermesmobile.data.brain

import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class BrainSkillsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun inventoryIncludesDisabledRowsDropsMetadataAndRejectsMalformedOrDuplicateLists() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody(
                "[{\"name\":\"demo-one\",\"description\":\"Enabled\",\"enabled\":true," +
                    "\"path\":\"synthetic-ignored\",\"category\":\"ignored\"}," +
                    "{\"name\":\"demo-two\",\"description\":\"Disabled\",\"enabled\":false}]"
            ))
            val available = fixture.repository.load() as BrainSkillsLoadResult.Available
            assertEquals(listOf("demo-one", "demo-two"), available.snapshot.skills.map { it.name })
            assertEquals(false, available.snapshot.skills[1].enabled)
            assertFalse(available.snapshot.toString().contains("synthetic-ignored"))

            fixture.server.enqueue(MockResponse().setBody("[]"))
            val empty = fixture.repository.load() as BrainSkillsLoadResult.Available
            assertTrue(empty.snapshot.skills.isEmpty())

            fixture.server.enqueue(MockResponse().setBody("{\"skills\":[]}"))
            assertEquals(
                BrainSkillsFailureReason.MALFORMED,
                (fixture.repository.load() as BrainSkillsLoadResult.Unavailable).failure.reason
            )

            fixture.server.enqueue(MockResponse().setBody(
                "[{\"name\":\"same\",\"description\":\"one\",\"enabled\":true}," +
                    "{\"name\":\"same\",\"description\":\"two\",\"enabled\":false}]"
            ))
            assertEquals(
                BrainSkillsFailureReason.MALFORMED,
                (fixture.repository.load() as BrainSkillsLoadResult.Unavailable).failure.reason
            )
        }
    }

    @Test
    fun inventoryAcceptsOrdinaryMultilineDescriptionWhitespaceAndKeepsBothRows() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            val ordinaryName = "ordinary-skill"
            val multilineName = "multiline-skill"
            val multilineDescription = "First line\nSecond line\rThird line\twith a tab"
            val response = GatewayJson.serialize(
                GatewayJson.fromAny(
                    listOf(
                        linkedMapOf(
                            "name" to ordinaryName,
                            "description" to "Ordinary description",
                            "enabled" to true,
                            "path" to "<synthetic-private-path>"
                        ),
                        linkedMapOf(
                            "name" to multilineName,
                            "description" to multilineDescription,
                            "enabled" to false,
                            "provenance" to "<synthetic-private-provenance>"
                        )
                    )
                )
            )
            fixture.server.enqueue(MockResponse().setBody(response))

            val result = fixture.repository.load()
            assertTrue("ordinary multiline inventory should remain available", result is BrainSkillsLoadResult.Available)
            val available = result as BrainSkillsLoadResult.Available
            assertEquals(2, available.snapshot.skills.size)
            assertEquals(
                listOf(ordinaryName, multilineName),
                available.snapshot.skills.map { it.name }
            )
            assertEquals("Ordinary description", available.snapshot.skills[0].description)
            assertEquals(multilineDescription, available.snapshot.skills[1].description)
            assertTrue(available.snapshot.skills[0].enabled)
            assertFalse(available.snapshot.skills[1].enabled)
            assertFalse(available.snapshot.toString().contains("synthetic-private"))
        }
    }

    @Test
    fun inventoryRejectsUnsafeNamesDescriptionsAndOverlongDescriptions() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fun response(name: String, description: String): String = GatewayJson.serialize(
                GatewayJson.fromAny(
                    listOf(
                        linkedMapOf(
                            "name" to name,
                            "description" to description,
                            "enabled" to true
                        )
                    )
                )
            )
            val invalidRows = listOf(
                "name with newline" to response("invalid\nname", "Safe description"),
                "name with carriage return" to response("invalid\rname", "Safe description"),
                "name with tab" to response("invalid\tname", "Safe description"),
                "description with NUL" to response("valid-name", "Unsafe\u0000description"),
                "description with escape" to response("valid-name", "Unsafe\u001bdescription"),
                "description over bound" to response("valid-name", "x".repeat(2049))
            )

            invalidRows.forEach { (label, body) ->
                fixture.server.enqueue(MockResponse().setBody(body))
                val result = fixture.repository.load()
                assertTrue("$label should be unavailable", result is BrainSkillsLoadResult.Unavailable)
                assertEquals(
                    BrainSkillsFailureReason.MALFORMED,
                    (result as BrainSkillsLoadResult.Unavailable).failure.reason
                )
            }
        }
    }

    @Test
    fun namedAndLiteralDefaultProfilesAreSentExactlyWithoutModelDiscovery() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            fixture.server.enqueue(MockResponse().setBody("[]"))
            fixture.repository.load()
            fixture.select("worker_alpha")
            fixture.server.enqueue(MockResponse().setBody("[]"))
            fixture.repository.load()

            val first = fixture.server.takeRequest()!!
            val second = fixture.server.takeRequest()!!
            assertEquals("GET", first.method)
            assertEquals("/api/skills", first.requestUrl?.encodedPath)
            assertEquals("profile=default", first.requestUrl?.encodedQuery)
            assertEquals("profile=worker_alpha", second.requestUrl?.encodedQuery)
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test
    fun toggleRequiresMatchingReadbackAndUsesExactProfileBody() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody(inventory(true)))
            val loaded = fixture.repository.load() as BrainSkillsLoadResult.Available
            assertTrue(
                fixture.repository.toggle(loaded.snapshot.snapshotId, "demo-one", true)
                    is BrainSkillsToggleResult.Saved
            )
            assertEquals(1, fixture.server.requestCount)
            val initialGet = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(initialGet)
            assertEquals("GET", initialGet!!.method)
            assertEquals("/api/skills", initialGet.requestUrl?.encodedPath)
            assertEquals("profile=default", initialGet.requestUrl?.encodedQuery)
            fixture.server.enqueue(MockResponse().setBody(ack(false)))
            fixture.server.enqueue(MockResponse().setBody(inventory(false)))

            val saved = fixture.repository.toggle(loaded.snapshot.snapshotId, "demo-one", false)
            assertTrue(saved is BrainSkillsToggleResult.Saved)
            val put = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(put)
            assertEquals("PUT", put!!.method)
            assertEquals("/api/skills/toggle", put.requestUrl?.encodedPath)
            assertEquals("profile=default", put.requestUrl?.encodedQuery)
            val body = GatewayJson.parseOrNull(put.body.readUtf8()) as JsonObject
            assertEquals(setOf("name", "enabled", "profile"), body.map.keys)
            assertEquals("demo-one", body["name"]?.asStringOrNull())
            assertEquals(false, body["enabled"]?.asBooleanOrNull())
            assertEquals("default", body["profile"]?.asStringOrNull())
            val readback = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(readback)
            assertEquals("GET", readback!!.method)
            assertEquals("/api/skills", readback.requestUrl?.encodedPath)
            assertEquals("profile=default", readback.requestUrl?.encodedQuery)
            assertEquals(3, fixture.server.requestCount)
        }
    }

    @Test
    fun protectedFalseEchoIsNotSavedAndInvalidatesOldSnapshotUntilFreshLoad() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody(inventory(true, protected = true)))
            val loaded = fixture.repository.load() as BrainSkillsLoadResult.Available
            fixture.server.enqueue(MockResponse().setBody(ack(false, name = "hermes-agent")))
            fixture.server.enqueue(MockResponse().setBody(inventory(true, protected = true)))
            val refused = fixture.repository.toggle(loaded.snapshot.snapshotId, "hermes-agent", false)
            assertTrue(refused is BrainSkillsToggleResult.NotConfirmed)
            assertEquals(
                BrainSkillsFailureReason.READBACK_MISMATCH,
                (refused as BrainSkillsToggleResult.NotConfirmed).reason
            )
            val beforeRetry = fixture.server.requestCount
            assertTrue(
                fixture.repository.toggle(loaded.snapshot.snapshotId, "hermes-agent", false)
                    is BrainSkillsToggleResult.NotConfirmed
            )
            assertEquals(beforeRetry, fixture.server.requestCount)

            fixture.server.enqueue(MockResponse().setBody(inventory(true, protected = true)))
            val fresh = fixture.repository.load() as BrainSkillsLoadResult.Available
            assertNotEquals(loaded.snapshot.snapshotId, fresh.snapshot.snapshotId)
        }
    }

    @Test
    fun acceptedRotationOnKnownRefusalIsUsedByNextLoadAndUnknownOutcomeNeverReplays() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.server.enqueue(MockResponse().addHeader("Set-Cookie", "session=synthetic-successor; Path=/")
                .setBody(inventory(true)))
            val loaded = fixture.repository.load() as BrainSkillsLoadResult.Available
            fixture.server.enqueue(MockResponse().addHeader("Set-Cookie", "session=synthetic-refusal; Path=/")
                .setResponseCode(409).setBody("{\"ok\":false}"))
            assertTrue(
                fixture.repository.toggle(loaded.snapshot.snapshotId, "demo-one", false)
                    is BrainSkillsToggleResult.Rejected
            )
            fixture.server.enqueue(MockResponse().setBody(inventory(true)))
            val reloaded = fixture.repository.load() as BrainSkillsLoadResult.Available
            fixture.server.takeRequest()
            val refusal = fixture.server.takeRequest()!!
            val nextLoad = fixture.server.takeRequest()!!
            assertEquals("session=synthetic-successor", refusal.getHeader("Cookie"))
            assertEquals("session=synthetic-refusal", nextLoad.getHeader("Cookie"))

            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val before = fixture.server.requestCount
            val uncertain = fixture.repository.toggle(reloaded.snapshot.snapshotId, "demo-one", false)
            assertTrue(uncertain is BrainSkillsToggleResult.NotConfirmed)
            assertTrue((uncertain as BrainSkillsToggleResult.NotConfirmed).mayHaveSaved)
            val afterUncertain = fixture.server.requestCount
            assertTrue(afterUncertain >= before)
            fixture.repository.toggle(reloaded.snapshot.snapshotId, "demo-one", false)
            assertEquals(afterUncertain, fixture.server.requestCount)
        }
    }

    @Test
    fun cancellationDuringCapturedReadClosesWorkAndLeavesNoEligibleSnapshot() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val loading = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.load() }
            val request = withContext(Dispatchers.IO) {
                fixture.server.takeRequest(5, TimeUnit.SECONDS)
            }
            assertNotNull(request)
            loading.cancelAndJoin()
            assertTrue(loading.isCancelled)
            assertTrue(
                fixture.repository.toggle(1L, "demo-one", false)
                    is BrainSkillsToggleResult.NotConfirmed
            )
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
                vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-cookie"), origin.originUrl))
                vault.saveSelectedContextProfile("default")
            }
        }

        fun select(profile: String) = runBlocking { vault.saveSelectedContextProfile(profile) }
        override fun close() { server.shutdown() }
    }

    private fun inventory(enabled: Boolean, protected: Boolean = false) =
        if (protected) {
            "[{\"name\":\"hermes-agent\",\"description\":\"Protected\",\"enabled\":$enabled}]"
        } else {
            "[{\"name\":\"demo-one\",\"description\":\"Demo\",\"enabled\":$enabled}]"
        }

    private fun ack(enabled: Boolean, name: String = "demo-one") =
        "{\"ok\":true,\"name\":\"$name\",\"enabled\":$enabled}"
}
