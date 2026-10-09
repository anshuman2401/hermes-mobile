package com.hermes.bot.data.commands

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SlashCommandRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private class Adapter(val base: FakeGatewayProtocolAdapter = FakeGatewayProtocolAdapter()) : GatewayProtocolAdapter by base {
        var catalogs = 0
        var writes = 0
        var failure: GatewayFailure? = null
        var directive: SlashDirective = SlashDirective.Output("Synthetic output")
        var onWrite: suspend () -> Unit = {}
        override suspend fun slashCatalog(runtimeId: String, origin: CanonicalOrigin, generation: Long): GatewayResult<SlashCatalog> {
            catalogs++
            return GatewayResult.Success(SlashCatalog(listOf(SlashSuggestion("/review", "Review", "Skills"),
                SlashSuggestion("/config", "Config")), unavailable = setOf("/config")))
        }
        override suspend fun slashComplete(runtimeId: String, text: String, origin: CanonicalOrigin, generation: Long) =
            GatewayResult.Success(SlashCompletion(listOf(SlashSuggestion("high", "More effort")), 11))
        override suspend fun slashExecute(runtimeId: String, command: SlashInvocation, origin: CanonicalOrigin, generation: Long): GatewayResult<SlashDirective> {
            writes++; onWrite(); return failure?.let { GatewayResult.Failure(it) } ?: GatewayResult.Success(directive)
        }
    }
    private suspend fun fixture(): Triple<DefaultSlashCommandRepository, EncryptedVault, Adapter> {
        val vault = EncryptedVault(folder.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        vault.saveConnectionProfile(ConnectionProfile("synthetic-connection", "http://example.test:8000", "Synthetic"))
        vault.setActiveProfileId("synthetic-connection")
        vault.saveAuthCredential(AuthCredential(driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "password", cookies = mapOf("session_id" to "synthetic"), canonicalOrigin = "http://example.test:8000"),
            profileLocalId = "synthetic-connection")
        val adapter = Adapter()
        val sessions = DefaultSessionRepository(adapter, vault)
        sessions.createDraftSession().getOrThrow()
        return Triple(DefaultSlashCommandRepository(adapter, sessions), vault, adapter)
    }
    @Test fun catalogIsCachedAndUnavailableCommandsAreFiltered() = runTest {
        val (repo, _, adapter) = fixture()
        val session = repo.capture("durable-key-created-1", "runtime-sid-created-1")!!
        val rows = repo.suggestions(session, "/")
        assertTrue(rows.any { it.text == "/review" })
        assertFalse(rows.any { it.text == "/config" })
        assertTrue(rows.any { it.text == "/yolo" })
        repo.suggestions(session, "/")
        assertEquals(1, adapter.catalogs)
        val result = repo.execute(session, SlashInvocation("config",""))
        assertTrue(result is CommandOutcome.Failure)
        assertEquals(0, adapter.writes)
    }
    @Test fun nativeAliasesDoNotNeedCatalogAndCyclesAreBounded() = runTest {
        val (repo, _, adapter) = fixture()
        val session = repo.capture("durable-key-created-1", "runtime-sid-created-1")!!
        assertEquals(SlashNavigation.NEW_CHAT, (repo.execute(session, SlashInvocation("reset","")) as CommandOutcome.Navigate).destination)
        assertEquals(0, adapter.catalogs)
        adapter.directive = SlashDirective.Alias("review")
        assertTrue(repo.execute(session, SlashInvocation("review","")) is CommandOutcome.Failure)
        assertEquals(1, adapter.writes)
    }
    @Test fun workspaceChangeRejectsCapturedOwnerAndLateResult() = runTest {
        val (repo, vault, adapter) = fixture()
        val session = repo.capture("durable-key-created-1", "runtime-sid-created-1")!!
        adapter.onWrite = { vault.saveSelectedContextProfile("synthetic-other") }
        val result = repo.execute(session, SlashInvocation("review",""))
        assertTrue(result is CommandOutcome.Failure)
        assertFalse(repo.isCurrent(session))
        repo.execute(session, SlashInvocation("review",""))
        assertEquals(1, adapter.writes)
    }
    @Test fun yoloUsesNativeScopedCommandAndRejectsGlobalArguments() = runTest {
        val (repo, _, adapter) = fixture()
        val session = repo.capture("durable-key-created-1", "runtime-sid-created-1")!!
        for (arg in listOf("", "global", "toggle", "on global"))
            assertTrue(repo.execute(session, SlashInvocation("yolo", arg)) is CommandOutcome.Failure)
        assertEquals(0, adapter.writes)
        adapter.directive = SlashDirective.YoloChanged()
        assertTrue((repo.execute(session, SlashInvocation("yolo", "on")) as CommandOutcome.Result).directive is SlashDirective.YoloChanged)
        assertEquals(0, adapter.catalogs)
        assertEquals(1, adapter.writes)
    }
    @Test fun yoloLateResultCannotCrossWorkspaceOwnership() = runTest {
        val (repo, vault, adapter) = fixture()
        val session = repo.capture("durable-key-created-1", "runtime-sid-created-1")!!
        adapter.directive = SlashDirective.YoloChanged()
        adapter.onWrite = { vault.saveSelectedContextProfile("synthetic-other") }
        assertTrue(repo.execute(session, SlashInvocation("yolo", "off")) is CommandOutcome.Failure)
        assertTrue(repo.execute(session, SlashInvocation("yolo", "on")) is CommandOutcome.Failure)
        assertEquals(1, adapter.writes)
    }
    @Test fun compressionConnectionLossIsAmbiguousEvenBeforeStatusCollectorRuns() = runTest {
        val (repo, _, adapter) = fixture()
        val owner = repo.capture("durable-key-created-1", "runtime-sid-created-1")!!
        adapter.failure = GatewayFailure.NotConnected("Synthetic connection loss")
        val outcome = repo.execute(owner, SlashInvocation("compress", "")) as CommandOutcome.Failure
        assertTrue(outcome.outcomeUnknown)
        assertEquals(1, adapter.writes)
        adapter.failure = GatewayFailure.Rpc(-32601, "Synthetic missing method")
        val definite = repo.execute(owner, SlashInvocation("compress", "")) as CommandOutcome.Failure
        assertFalse(definite.outcomeUnknown)
        assertEquals(2, adapter.writes)
    }

    @Test fun yoloConnectionLossIsUnconfirmedAndNeverAutomaticallyResent() = runTest {
        val (repo, _, adapter) = fixture()
        val owner = repo.capture("durable-key-created-1", "runtime-sid-created-1")!!
        adapter.failure = GatewayFailure.NotConnected("Synthetic connection loss")
        val outcome = repo.execute(owner, SlashInvocation("yolo", "off")) as CommandOutcome.Failure
        assertTrue(outcome.outcomeUnknown)
        assertEquals(1, adapter.writes)
    }

}
