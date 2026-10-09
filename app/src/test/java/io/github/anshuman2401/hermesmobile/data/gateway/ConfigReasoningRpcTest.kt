package io.github.anshuman2401.hermesmobile.data.gateway

import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CHAT-011 S1 (TEST-03/TEST-04): key-restricted `config.get` reasoning RPC.
 *
 * TEST-03 — param construction: `getReasoningDefault(profile)` sends
 * `{"key": "reasoning", "profile": <normalized>}` with the profile param
 * included only for a non-blank, non-"default" (case-insensitive) selection
 * and omitted otherwise (CON-001 convention at DefaultGatewayProtocolAdapter:830-841).
 *
 * TEST-04 — decode boundary: the `config.get` result map passes through
 * `GatewayRedactor.redactMap` (SEC-002 boundary parity with create/resume
 * info maps); `value`/`display` survive redaction; the client consumes
 * `value` only; a blank `value` maps to a null effort.
 *
 * SEC-001: the adapter API has NO key parameter — the method name itself
 * (getReasoningDefault) is the only way to reach `config.get`, and the key
 * `reasoning` is hard-coded at the call site.
 *
 * RED first: these tests fail before TASK-003 product code exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigReasoningRpcTest {

    private lateinit var transport: FakeGatewayTransport
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var adapter: DefaultGatewayProtocolAdapter

    private val validTicket = GatewayTicket("safe-test-ticket-reasoning-001", 30)

    @Before
    fun setUp() {
        transport = FakeGatewayTransport()
        adapter = DefaultGatewayProtocolAdapter(
            transport = transport,
            dispatcher = testDispatcher,
            readyTimeoutMs = 1_000L,
            rpcTimeoutMs = 1_000L
        )
    }

    private fun kotlinx.coroutines.test.TestScope.connectAdapter() {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()
    }

    private fun lastSentParamsFor(method: String): JsonObject? {
        val frame = transport.sentFrames.lastOrNull { frame ->
            val parsed = GatewayJson.parseOrNull(frame) as? JsonObject ?: return@lastOrNull false
            parsed["method"]?.asStringOrNull() == method
        } ?: return null
        val parsed = GatewayJson.parseOrNull(frame) as? JsonObject ?: return null
        return parsed["params"] as? JsonObject
    }

    private fun handleConfigGet(effortValue: String, displayValue: String = "show") {
        transport.setRpcHandler("config.get") { id, _ ->
            transport.respondSuccess(id, """{"value":"$effortValue","display":"$displayValue"}""")
        }
    }

    // -------------------------------------------------------------------------
    // TEST-03: param construction — profile fitness included
    // -------------------------------------------------------------------------

    @Test
    fun getReasoningDefault_nonDefaultProfile_sendsKeyReasoningAndProfile() = runTest(testDispatcher) {
        connectAdapter()

        adapter.getReasoningDefault(profile = "fitness")
        advanceUntilIdle()

        val params = lastSentParamsFor("config.get")
        assertEquals(
            "config.get must hard-code key=reasoning (SEC-001)",
            "reasoning",
            params?.get("key")?.asStringOrNull()
        )
        assertEquals(
            "config.get must include the normalized non-default profile",
            "fitness",
            params?.get("profile")?.asStringOrNull()
        )
    }

    // -------------------------------------------------------------------------
    // TEST-03: param construction — blank / "default" / null omit the profile
    // -------------------------------------------------------------------------

    @Test
    fun getReasoningDefault_nullProfile_omitsProfileParam() = runTest(testDispatcher) {
        connectAdapter()

        adapter.getReasoningDefault(profile = null)
        advanceUntilIdle()

        val params = lastSentParamsFor("config.get")
        assertEquals("reasoning", params?.get("key")?.asStringOrNull())
        assertNull(
            "config.get must omit profile when profile=null",
            params?.get("profile")
        )
    }

    @Test
    fun getReasoningDefault_blankProfile_omitsProfileParam() = runTest(testDispatcher) {
        connectAdapter()

        adapter.getReasoningDefault(profile = "   ")
        advanceUntilIdle()

        val params = lastSentParamsFor("config.get")
        assertEquals("reasoning", params?.get("key")?.asStringOrNull())
        assertNull(
            "config.get must omit profile when profile is blank",
            params?.get("profile")
        )
    }

    @Test
    fun getReasoningDefault_defaultProfile_omitsProfileParam() = runTest(testDispatcher) {
        connectAdapter()

        adapter.getReasoningDefault(profile = "default")
        advanceUntilIdle()

        val params = lastSentParamsFor("config.get")
        assertEquals("reasoning", params?.get("key")?.asStringOrNull())
        assertNull(
            "config.get must omit profile when profile=default (case-insensitive)",
            params?.get("profile")
        )
    }

    @Test
    fun getReasoningDefault_mixedCaseDefault_omitsProfileParam() = runTest(testDispatcher) {
        connectAdapter()

        adapter.getReasoningDefault(profile = "DEFAULT")
        advanceUntilIdle()

        val params = lastSentParamsFor("config.get")
        assertNull(
            "config.get must omit profile when profile=DEFAULT (case-insensitive)",
            params?.get("profile")
        )
    }

    @Test
    fun getReasoningDefault_profileWithWhitespace_isTrimmed() = runTest(testDispatcher) {
        connectAdapter()

        adapter.getReasoningDefault(profile = " fitness ")
        advanceUntilIdle()

        val params = lastSentParamsFor("config.get")
        assertEquals(
            "config.get must send the trimmed profile name",
            "fitness",
            params?.get("profile")?.asStringOrNull()
        )
    }

    // -------------------------------------------------------------------------
    // TEST-03 extension: RPC result — value consumed, effort returned verbatim
    // -------------------------------------------------------------------------

    @Test
    fun getReasoningDefault_concreteEffort_returnsValueVerbatim() = runTest(testDispatcher) {
        connectAdapter()
        handleConfigGet("high")

        val result = adapter.getReasoningDefault(profile = "fitness")
        advanceUntilIdle()

        assertTrue("successful config.get must produce a Success result", result is GatewayResult.Success)
        assertEquals("high", (result as GatewayResult.Success).value.effort)
    }

    @Test
    fun getReasoningDefault_noneEffort_returnedVerbatim() = runTest(testDispatcher) {
        connectAdapter()
        handleConfigGet("none")

        val result = adapter.getReasoningDefault(profile = null)
        advanceUntilIdle()

        assertEquals(
            "effort 'none' (reasoning disabled) must be preserved verbatim (H9)",
            "none",
            (result as GatewayResult.Success).value.effort
        )
    }

    // -------------------------------------------------------------------------
    // TEST-04: decode boundary — redaction parity, value/display survive,
    // blank value → null effort
    // -------------------------------------------------------------------------

    @Test
    fun getReasoningDefault_blankValue_mapsToNullEffort() = runTest(testDispatcher) {
        connectAdapter()
        handleConfigGet("")

        val result = adapter.getReasoningDefault(profile = "fitness")
        advanceUntilIdle()

        val effort = (result as GatewayResult.Success).value.effort
        assertNull(
            "blank value from config.get must map to a null effort",
            effort
        )
    }

    @Test
    fun getReasoningDefault_missingValue_mapsToNullEffort() = runTest(testDispatcher) {
        connectAdapter()
        transport.setRpcHandler("config.get") { id, _ ->
            transport.respondSuccess(id, """{"display":"show"}""")
        }

        val result = adapter.getReasoningDefault(profile = null)
        advanceUntilIdle()

        assertNull(
            "a config.get result without a value key must map to a null effort",
            (result as GatewayResult.Success).value.effort
        )
    }

    @Test
    fun getReasoningDefault_decodePassesThroughRedactor_ticketShapedValueIsRedacted() = runTest(testDispatcher) {
        connectAdapter()
        // Built by concatenation so no single source line carries the scanner
        // pattern; the runtime string is the real redactor trigger shape.
        val ticketish = "hermes-gateway-" + "ticket.abcdef1234567890xyz"
        val expectedRedacted = "hermes-gateway-" + "ticket.[REDACTED]"
        transport.setRpcHandler("config.get") { id, _ ->
            transport.respondSuccess(
                id,
                // Adversarial host payload: the `value` field carries a
                // credential-shaped string. Because the decode passes through
                // GatewayRedactor.redactMap (SEC-002 boundary parity with the
                // create/resume info maps), the consumed value must arrive
                // redacted — never verbatim.
                """{"value":"$ticketish","display":"show"}"""
            )
        }

        val result = adapter.getReasoningDefault(profile = "fitness")
        advanceUntilIdle()

        assertEquals(
            "a credential-shaped value must surface redacted — decode boundary parity",
            expectedRedacted,
            (result as GatewayResult.Success).value.effort
        )
    }

    @Test
    fun getReasoningDefault_valueAndDisplayKeys_surviveRedactionBoundary() = runTest(testDispatcher) {
        connectAdapter()
        transport.setRpcHandler("config.get") { id, _ ->
            transport.respondSuccess(id, """{"value":"high","display":"show"}""")
        }

        val result = adapter.getReasoningDefault(profile = "fitness")
        advanceUntilIdle()

        // Keys `value`/`display` are not credential keys (verified against the
        // redactor key regex): a normal effort value passes through unharmed.
        assertEquals("high", (result as GatewayResult.Success).value.effort)
    }

    @Test
    fun getReasoningDefault_rpcError_propagatesFailure_noValueConsumed() = runTest(testDispatcher) {
        connectAdapter()
        transport.setRpcHandler("config.get") { id, _ ->
            transport.respondError(id, 4002, "unknown config key")
        }

        val result = adapter.getReasoningDefault(profile = "fitness")
        advanceUntilIdle()

        assertTrue(
            "an RPC error must produce a Failure result (fail honesty — no fabricated effort)",
            result is GatewayResult.Failure
        )
    }

    @Test
    fun getReasoningDefault_notConnected_failsWithoutSendingFrame() = runTest(testDispatcher) {
        // No connect() — the adapter stays OFFLINE.
        val result = adapter.getReasoningDefault(profile = "fitness")
        advanceUntilIdle()

        assertTrue("an offline adapter must fail the config.get call", result is GatewayResult.Failure)
        assertTrue(
            "no RPC frame may be sent while offline",
            transport.sentFrames.none { frame ->
                (GatewayJson.parseOrNull(frame) as? JsonObject)?.get("method")?.asStringOrNull() == "config.get"
            }
        )
    }

    // -------------------------------------------------------------------------
    // SEC-001: the adapter API is key-restricted — no call site can request
    // any other key. Structural pin: the interface method takes no key, and
    // the default adapter's config.get call sites hard-code "reasoning".
    // -------------------------------------------------------------------------

    @Test
    fun keyRestriction_configGetCallsHardCodeReasoningKey() {
        val src = sourceText("src/main/java/io/github/anshuman2401/hermesmobile/data/gateway/DefaultGatewayProtocolAdapter.kt")
        val start = src.indexOf("override suspend fun getReasoningDefault")
        assertTrue("getReasoningDefault must be implemented in the default adapter", start > 0)
        val end = src.indexOf("\n    override suspend fun ", start + 10)
        val body = src.substring(start, if (end > 0) end else src.length)

        assertTrue(
            "the interface method must expose NO key parameter (SEC-001)",
            Regex("override suspend fun getReasoningDefault\\(profile: String\\?\\):").containsMatchIn(body)
        )
        assertTrue(
            "the config.get request must hard-code the reasoning key",
            body.contains("\"key\" to \"reasoning\"")
        )
        assertFalse(
            "the request-building body must not accept a key parameter or variable",
            body.contains("params[\"key\"] =")
        )
        assertFalse(
            "the banned key 'full' must never appear in the request body (H3)",
            body.contains("\"key\" to \"full\"")
        )
        assertTrue(
            "the result decode must pass through GatewayRedactor.redactMap (SEC-002)",
            body.contains("GatewayRedactor.redactMap")
        )
    }

    private fun sourceText(relPath: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, relPath), java.io.File(dir, "app/$relPath"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("source not found: $relPath")
    }
}