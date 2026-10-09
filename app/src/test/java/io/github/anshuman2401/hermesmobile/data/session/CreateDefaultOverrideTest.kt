package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.local.ProfileModelDefault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CHAT-011 S3 (TEST-14/TEST-15): direct adapter callers may still provide
 * explicit per-session `model` + `reasoning_effort` overrides — sent verbatim
 * (including `none`, which disables reasoning), each omitted when blank
 * (CON-001), and `provider` never sent (SEC-004). Ordinary repository
 * `createDraftSession` calls do not forward the cached record: the host owns
 * the defaults and receives both overrides as null. `session.resume` never
 * sends overrides (ALT-002/H7).
 *
 * Wire-level param inspection follows the SessionProfileRpcRoutingTest
 * conventions (FakeGatewayTransport.sentFrames); repository wiring follows
 * the SessionRepositoryTest conventions (real EncryptedVault over an
 * in-memory key provider).
 *
 * RED first: these tests must fail before the production code is changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateDefaultOverrideTest {

    // ==================================================================
    // Part A — adapter wire param construction (TEST-14)
    // ==================================================================

    private val testDispatcher = StandardTestDispatcher()
    private val validTicket = GatewayTicket("safe-test-ticket-create-override-001", 30)

    private fun newConnectedAdapter(): Pair<FakeGatewayTransport, DefaultGatewayProtocolAdapter> {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(
            transport = transport,
            dispatcher = testDispatcher,
            readyTimeoutMs = 1_000L,
            rpcTimeoutMs = 1_000L
        )
        return transport to adapter
    }

    private fun lastSentParamsFor(transport: FakeGatewayTransport, method: String): JsonObject? {
        val frame = transport.sentFrames.lastOrNull { frame ->
            val parsed = GatewayJson.parseOrNull(frame) as? JsonObject ?: return@lastOrNull false
            parsed["method"]?.asStringOrNull() == method
        } ?: return null
        val parsed = GatewayJson.parseOrNull(frame) as? JsonObject ?: return null
        return parsed["params"] as? JsonObject
    }

    @Test
    fun createSession_modelAndEffortPresent_sentVerbatim() = runTest(testDispatcher) {
        val (transport, adapter) = newConnectedAdapter()
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(
            title = null,
            source = "android",
            closeOnDisconnect = false,
            profile = "work",
            model = "gpt-5.6-luna",
            reasoningEffort = "high"
        )
        advanceUntilIdle()

        val params = lastSentParamsFor(transport, "session.create")
        assertNotNull("session.create frame must have been sent", params)
        assertEquals("model override must be sent verbatim", "gpt-5.6-luna", params?.get("model")?.asStringOrNull())
        assertEquals("reasoning_effort override must be sent verbatim", "high", params?.get("reasoning_effort")?.asStringOrNull())
        assertEquals("profile param keeps its S1 behavior beside the overrides", "work", params?.get("profile")?.asStringOrNull())
    }

    @Test
    fun createSession_noneEffort_sentVerbatim() = runTest(testDispatcher) {
        // 'none' disables reasoning on the created session — exactly the
        // recorded default (H9); it is never remapped or swallowed.
        val (transport, adapter) = newConnectedAdapter()
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(
            title = null,
            source = "android",
            closeOnDisconnect = false,
            profile = "work",
            model = "gpt-5.6-luna",
            reasoningEffort = "none"
        )
        advanceUntilIdle()

        val params = lastSentParamsFor(transport, "session.create")
        assertEquals("literal 'none' effort must be sent verbatim", "none", params?.get("reasoning_effort")?.asStringOrNull())
    }

    @Test
    fun createSession_blankModelAndEffort_eachOmitted() = runTest(testDispatcher) {
        val (transport, adapter) = newConnectedAdapter()
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(
            title = null,
            source = "android",
            closeOnDisconnect = false,
            profile = "work",
            model = "  ",
            reasoningEffort = ""
        )
        advanceUntilIdle()

        val params = lastSentParamsFor(transport, "session.create")
        assertNotNull(params)
        assertNull("blank model must be omitted entirely (CON-001)", params?.get("model"))
        assertNull("blank reasoning_effort must be omitted entirely (CON-001)", params?.get("reasoning_effort"))
    }

    @Test
    fun createSession_nullModelAndEffort_eachOmitted() = runTest(testDispatcher) {
        val (transport, adapter) = newConnectedAdapter()
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(
            title = null,
            source = "android",
            closeOnDisconnect = false,
            profile = null,
            model = null,
            reasoningEffort = null
        )
        advanceUntilIdle()

        val params = lastSentParamsFor(transport, "session.create")
        assertNotNull(params)
        assertNull(params?.get("model"))
        assertNull(params?.get("reasoning_effort"))
    }

    @Test
    fun createSession_modelOnly_effortOmitted() = runTest(testDispatcher) {
        val (transport, adapter) = newConnectedAdapter()
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(
            title = null,
            source = "android",
            closeOnDisconnect = false,
            model = "gpt-5.6-luna",
            reasoningEffort = null
        )
        advanceUntilIdle()

        val params = lastSentParamsFor(transport, "session.create")
        assertEquals("gpt-5.6-luna", params?.get("model")?.asStringOrNull())
        assertNull(params?.get("reasoning_effort"))
    }

    @Test
    fun createSession_effortOnly_modelOmitted() = runTest(testDispatcher) {
        val (transport, adapter) = newConnectedAdapter()
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(
            title = null,
            source = "android",
            closeOnDisconnect = false,
            model = null,
            reasoningEffort = "minimal"
        )
        advanceUntilIdle()

        val params = lastSentParamsFor(transport, "session.create")
        assertEquals("minimal", params?.get("reasoning_effort")?.asStringOrNull())
        assertNull(params?.get("model"))
    }

    @Test
    fun createSession_defaultArgs_todayParamsExactlyAndProviderNeverSent() = runTest(testDispatcher) {
        // Backward compat: the existing no-override call signature produces
        // today's params exactly, and `provider` never appears on the frame
        // (SEC-004).
        val (transport, adapter) = newConnectedAdapter()
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(title = null, source = "android", closeOnDisconnect = false)
        advanceUntilIdle()

        val params = lastSentParamsFor(transport, "session.create")
        assertNotNull(params)
        assertNull(params?.get("model"))
        assertNull(params?.get("reasoning_effort"))
        assertNull("provider must never be sent (SEC-004)", params?.get("provider"))
        assertEquals("android", params?.get("source")?.asStringOrNull())
    }

    @Test
    fun createSession_providerNeverSent_evenWhenOverridesPresent() = runTest(testDispatcher) {
        val (transport, adapter) = newConnectedAdapter()
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.createSession(
            title = null,
            source = "android",
            closeOnDisconnect = false,
            model = "gpt-5.6-luna",
            reasoningEffort = "high"
        )
        advanceUntilIdle()

        val params = lastSentParamsFor(transport, "session.create")
        assertNull("provider must never be sent (SEC-004)", params?.get("provider"))
    }

    @Test
    fun resumeSession_wireParamsNeverCarryOverrides() = runTest(testDispatcher) {
        // ALT-002/H7 pin: the session.resume wire shape is session_id
        // (+ profile when non-default) and NEVER model/reasoning_effort.
        val (transport, adapter) = newConnectedAdapter()
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        adapter.resumeSession(sessionId = "stored-1", profile = "work")
        advanceUntilIdle()

        val params = lastSentParamsFor(transport, "session.resume")
        assertNotNull(params)
        assertEquals("stored-1", params?.get("session_id")?.asStringOrNull())
        assertEquals("work", params?.get("profile")?.asStringOrNull())
        assertNull("resume must never carry a model override (ALT-002/H7)", params?.get("model"))
        assertNull("resume must never carry a reasoning_effort override (ALT-002/H7)", params?.get("reasoning_effort"))
    }

    // ==================================================================
    // Part B — createDraftSession wiring (TEST-15)
    // ==================================================================

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newVault(): Pair<EncryptedVault, File> {
        val storageDir = tempFolder.newFolder("s3_create_default")
        return EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider())) to storageDir
    }

    private fun record(workspaceName: String, model: String, effort: String): ProfileModelDefault =
        ProfileModelDefault(workspaceName = workspaceName, modelName = model, reasoningEffort = effort)

    @Test
    fun createDraftSession_ignoresCachedDefaultAndUsesHostDefaults() = runBlocking {
        val (vault, _) = newVault()
        vault.saveSelectedContextProfile("coding")
        vault.saveProfileModelDefault(record("coding", "gpt-5.6-luna", "high"))
        val fake = FakeGatewayProtocolAdapter()
        val repository = DefaultSessionRepository(fake, vault)

        val result = repository.createDraftSession("New Chat")

        assertTrue(result.isSuccess)
        val call = fake.createCalls.single()
        assertEquals("coding", call.profile)
        assertNull("ordinary create must not forward cached model", call.model)
        assertNull("ordinary create must not forward cached effort", call.reasoningEffort)
        val cached = vault.getProfileModelDefault(ProfileModelDefault.workspaceKey("coding"))
        assertEquals("cached model must remain unchanged", "gpt-5.6-luna", cached?.modelName)
        assertEquals("cached effort must remain unchanged", "high", cached?.reasoningEffort)
    }

    @Test
    fun createDraftSession_doesNotForwardCachedNoneEffort() = runBlocking {
        val (vault, _) = newVault()
        vault.saveSelectedContextProfile("coding")
        vault.saveProfileModelDefault(record("coding", "gpt-5.6-luna", "none"))
        val fake = FakeGatewayProtocolAdapter()
        val repository = DefaultSessionRepository(fake, vault)

        repository.createDraftSession(null)

        val call = fake.createCalls.single()
        assertNull("ordinary create must not forward cached model", call.model)
        assertNull("ordinary create must not forward cached 'none' effort", call.reasoningEffort)
        val cached = vault.getProfileModelDefault(ProfileModelDefault.workspaceKey("coding"))
        assertEquals("cached model must remain unchanged", "gpt-5.6-luna", cached?.modelName)
        assertEquals("cached 'none' effort must remain unchanged", "none", cached?.reasoningEffort)
    }

    @Test
    fun createDraftSession_noCachedRecord_sendsHostDefaultParamsExactly() = runBlocking {
        val (vault, _) = newVault()
        vault.saveSelectedContextProfile("coding")
        val fake = FakeGatewayProtocolAdapter()
        val repository = DefaultSessionRepository(fake, vault)

        repository.createDraftSession(null)

        val call = fake.createCalls.single()
        assertNull("no record -> model must not be threaded (today's params)", call.model)
        assertNull("no record -> effort must not be threaded (today's params)", call.reasoningEffort)
        assertEquals("coding", call.profile)
    }

    @Test
    fun createDraftSession_noSelection_doesNotForwardCachedDefault() = runBlocking {
        // No selection saved (null selection). A record under the legacy
        // "default" key must not become a per-session override.
        val (vault, _) = newVault()
        vault.saveProfileModelDefault(record("default", "gpt-5.6-luna", "high"))
        val fake = FakeGatewayProtocolAdapter()
        val repository = DefaultSessionRepository(fake, vault)

        repository.createDraftSession(null)

        val call = fake.createCalls.single()
        assertNull("ordinary create must not forward cached model", call.model)
        assertNull("ordinary create must not forward cached effort", call.reasoningEffort)
    }

    @Test
    fun createDraftSession_blankVaultFields_omitOverrides() = runBlocking {
        // Blank vault record fields must OMIT the params — the created session
        // then inherits the host profile default (today's behavior).
        val (vault, _) = newVault()
        vault.saveSelectedContextProfile("coding")
        vault.saveProfileModelDefault(record("coding", "  ", ""))
        val fake = FakeGatewayProtocolAdapter()
        val repository = DefaultSessionRepository(fake, vault)

        repository.createDraftSession(null)

        val call = fake.createCalls.single()
        assertNull("blank record model must omit the override (CON-001)", call.model)
        assertNull("blank record effort must omit the override (CON-001)", call.reasoningEffort)
    }

    @Test
    fun createDraftSession_otherWorkspaceRecord_notThreaded() = runBlocking {
        // Workspace isolation: a record for a DIFFERENT workspace must not
        // leak into this workspace's create.
        val (vault, _) = newVault()
        vault.saveSelectedContextProfile("coding")
        vault.saveProfileModelDefault(record("fitness", "claude-sonnet-4-6", "low"))
        val fake = FakeGatewayProtocolAdapter()
        val repository = DefaultSessionRepository(fake, vault)

        repository.createDraftSession(null)

        val call = fake.createCalls.single()
        assertNull(call.model)
        assertNull(call.reasoningEffort)
    }

    @Test
    fun createDraftSession_vaultReadFailClosed_threadsNoOverride() = runBlocking {
        // A corrupted singleton file fail-closes inside the vault read
        // (decrypt failure -> quarantine-delete -> null), so the create must
        // proceed with today's params — no crash, no fabricated values.
        val (vault, storageDir) = newVault()
        vault.saveSelectedContextProfile("coding")
        File(storageDir, "profile_model_defaults.enc").writeBytes(ByteArray(64))
        val fake = FakeGatewayProtocolAdapter()
        val repository = DefaultSessionRepository(fake, vault)

        val result = repository.createDraftSession(null)

        assertTrue("fail-closed read must not fail the create", result.isSuccess)
        val call = fake.createCalls.single()
        assertNull(call.model)
        assertNull(call.reasoningEffort)
    }

    @Test
    fun resumeSession_withCachedDefaultPresent_neverReOverrides() = runBlocking {
        // ALT-002/H7: with a cached default on disk, resume must NOT trigger
        // any create-override path — no create call, resume carries only the
        // two today params.
        val (vault, _) = newVault()
        vault.saveSelectedContextProfile("coding")
        vault.saveProfileModelDefault(record("coding", "gpt-5.6-luna", "high"))
        val fake = FakeGatewayProtocolAdapter()
        val repository = DefaultSessionRepository(fake, vault)

        val result = repository.resumeSession("stored-1")

        assertTrue(result.isSuccess)
        val resume = fake.resumeCalls.single()
        assertEquals("stored-1", resume.sessionId)
        assertEquals("coding", resume.profile)
        assertEquals("resume must never send create overrides (ALT-002/H7)", 0, fake.createCalls.size)
    }
}
