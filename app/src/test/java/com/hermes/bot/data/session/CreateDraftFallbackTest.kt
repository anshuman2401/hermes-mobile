package com.hermes.bot.data.session

import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.local.ProfileModelDefault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CHAT-015 B1: ordinary cached-default handling in
 * `DefaultSessionRepository.createDraftSession`.
 *
 * Ordinary create never forwards cached model/reasoning overrides, so a
 * `GatewayFailure.Rpc(code == -32602)` is returned after exactly one call;
 * there is no cached-default recovery retry. Every other failure is also
 * exactly one call, returned as today. The cached record remains unchanged.
 * At most one minted session is attempted per tap under every residual cause.
 *
 * RED first: the former cached-retry expectation must fail before the
 * production change is made.
 * Repository wiring follows the CreateDefaultOverrideTest Part B conventions
 * (real EncryptedVault over an in-memory key provider).
 */
class CreateDraftFallbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newVault(): EncryptedVault {
        val storageDir = tempFolder.newFolder("chat015_fallback")
        return EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
    }

    private fun record(workspaceName: String, model: String, effort: String): ProfileModelDefault =
        ProfileModelDefault(workspaceName = workspaceName, modelName = model, reasoningEffort = effort)

    private fun rpcInvalidParams() = GatewayResult.Failure(
        GatewayFailure.Rpc(code = -32602, message = "invalid params")
    )

    @Test
    fun createDraftSession_rpcInvalidParamsWithCachedDefault_callsOnceWithoutOverrides_preservesFailureAndCache() =
        runBlocking {
            val vault = newVault()
            vault.saveSelectedContextProfile("coding")
            vault.saveProfileModelDefault(record("coding", "gpt-5.6-luna", "high"))
            val fake = FakeGatewayProtocolAdapter()
            fake.createSessionResults.add(rpcInvalidParams())
            val repository = DefaultSessionRepository(fake, vault)

            val result = repository.createDraftSession("New Chat")

            assertTrue("cached-default refusal must remain a failure", result.isFailure)
            assertEquals("ordinary create must make exactly one attempt", 1, fake.createCalls.size)
            val call = fake.createCalls.single()
            assertNull("ordinary create must omit the cached model override", call.model)
            assertNull("ordinary create must omit the cached effort override", call.reasoningEffort)
            assertEquals("New Chat", call.title)
            assertEquals("android", call.source)
            assertEquals("coding", call.profile)
            val failure = result.exceptionOrNull() as? SessionCreateFailedException
            assertNotNull("create failure must retain its typed wrapper", failure)
            val rpcFailure = failure?.failure as? GatewayFailure.Rpc
            assertNotNull("create failure must retain its RPC category", rpcFailure)
            assertEquals(-32602, rpcFailure?.code)
            assertEquals("invalid params", rpcFailure?.message)
            val cached = vault.getProfileModelDefault(ProfileModelDefault.workspaceKey("coding"))
            assertEquals("cached model must remain unchanged", "gpt-5.6-luna", cached?.modelName)
            assertEquals("cached effort must remain unchanged", "high", cached?.reasoningEffort)
        }

    @Test
    fun createDraftSession_rpcInvalidParamsWithoutCachedOverride_callsOnce() = runBlocking {
        val vault = newVault()
        vault.saveSelectedContextProfile("coding")
        val fake = FakeGatewayProtocolAdapter()
        fake.createSessionResults.add(rpcInvalidParams())
        val repository = DefaultSessionRepository(fake, vault)

        val result = repository.createDraftSession(null)

        assertTrue("failure returned as today", result.isFailure)
        assertEquals("invalid params must never trigger a retry", 1, fake.createCalls.size)
        assertNull(fake.createCalls.single().model)
        assertNull(fake.createCalls.single().reasoningEffort)
    }

    @Test
    fun createDraftSession_transportFailureWithCachedDefault_callsOnceWithoutOverrides() = runBlocking {
        val vault = newVault()
        vault.saveSelectedContextProfile("coding")
        vault.saveProfileModelDefault(record("coding", "gpt-5.6-luna", "high"))
        val fake = FakeGatewayProtocolAdapter()
        fake.createSessionResults.add(
            GatewayResult.Failure(GatewayFailure.Transport(message = "socket closed"))
        )
        val repository = DefaultSessionRepository(fake, vault)

        val result = repository.createDraftSession("New Chat")

        assertTrue("failure returned as today", result.isFailure)
        assertEquals("transport failures must never retry (no duplicate create)", 1, fake.createCalls.size)
        assertNull(fake.createCalls.single().model)
        assertNull(fake.createCalls.single().reasoningEffort)
    }

    @Test
    fun createDraftSession_nonInvalidParamsRpcFailureWithCachedDefault_callsOnceWithoutOverrides() = runBlocking {
        val vault = newVault()
        vault.saveSelectedContextProfile("coding")
        vault.saveProfileModelDefault(record("coding", "gpt-5.6-luna", "high"))
        val fake = FakeGatewayProtocolAdapter()
        fake.createSessionResults.add(
            GatewayResult.Failure(GatewayFailure.Rpc(code = -32000, message = "server error"))
        )
        val repository = DefaultSessionRepository(fake, vault)

        val result = repository.createDraftSession("New Chat")

        assertTrue("failure returned as today", result.isFailure)
        assertEquals("RPC failures must never trigger a cached-default retry", 1, fake.createCalls.size)
        assertNull(fake.createCalls.single().model)
        assertNull(fake.createCalls.single().reasoningEffort)
    }

    @Test
    fun createDraftSession_secondQueuedRefusalIsNeverConsumed() = runBlocking {
        val vault = newVault()
        vault.saveSelectedContextProfile("coding")
        vault.saveProfileModelDefault(record("coding", "gpt-5.6-luna", "high"))
        val fake = FakeGatewayProtocolAdapter()
        fake.createSessionResults.add(rpcInvalidParams())
        fake.createSessionResults.add(rpcInvalidParams())
        val repository = DefaultSessionRepository(fake, vault)

        val result = repository.createDraftSession("New Chat")

        assertTrue("first refusal is returned", result.isFailure)
        assertEquals("cached-default recovery must not make a second attempt", 1, fake.createCalls.size)
        assertNull(fake.createCalls.single().model)
        assertNull(fake.createCalls.single().reasoningEffort)
    }

    @Test
    fun createDraftSession_plainSuccess_reportsSavedDefaultApplied() = runBlocking {
        val vault = newVault()
        vault.saveSelectedContextProfile("coding")
        vault.saveProfileModelDefault(record("coding", "gpt-5.6-luna", "high"))
        val fake = FakeGatewayProtocolAdapter()
        val repository = DefaultSessionRepository(fake, vault)

        val result = repository.createDraftSession("New Chat")

        assertTrue(result.isSuccess)
        assertEquals("one call on first-try success", 1, fake.createCalls.size)
        assertNull("success must inherit the host model default", fake.createCalls.single().model)
        assertNull("success must inherit the host effort default", fake.createCalls.single().reasoningEffort)
        assertEquals(
            "normal success reports no degraded-default notice",
            true,
            result.getOrThrow().savedDefaultModelApplied
        )
    }

    @Test
    fun createDraftSession_failureDoesNotMutateVaultDefault() = runBlocking {
        // CHAT-015 non-goal: the vault cached default is NOT mutated or cleared
        // by ordinary create failure (the picker/vault seam is CHAT-014
        // territory).
        val vault = newVault()
        vault.saveSelectedContextProfile("coding")
        vault.saveProfileModelDefault(record("coding", "gpt-5.6-luna", "high"))
        val fake = FakeGatewayProtocolAdapter()
        fake.createSessionResults.add(rpcInvalidParams())
        val repository = DefaultSessionRepository(fake, vault)

        repository.createDraftSession("New Chat")

        val cached = vault.getProfileModelDefault(ProfileModelDefault.workspaceKey("coding"))
        assertEquals("cached model must survive ordinary create failure", "gpt-5.6-luna", cached?.modelName)
        assertEquals("cached effort must survive ordinary create failure", "high", cached?.reasoningEffort)
    }
}
