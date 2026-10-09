package io.github.anshuman2401.hermesmobile.data.context

import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ContextUsageRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private class Adapter(val base: FakeGatewayProtocolAdapter = FakeGatewayProtocolAdapter()) : GatewayProtocolAdapter by base {
        val scopes = mutableListOf<Triple<String,String,Long>>()
        var before: suspend () -> Unit = {}
        override suspend fun sessionContextBreakdown(runtimeId: String, profile: String, origin: CanonicalOrigin, generation: Long): GatewayResult<ContextBreakdown> {
            assertEquals("http://example.test:8000", origin.originUrl)
            scopes.add(Triple(runtimeId,profile,generation)); before()
            return GatewayResult.Success(ContextBreakdown(100,1000,10.0,true,"Synthetic",emptyList()))
        }
        override suspend fun sessionUsage(runtimeId: String, profile: String, origin: CanonicalOrigin, generation: Long): GatewayResult<SessionUsage> {
            assertEquals("http://example.test:8000", origin.originUrl)
            scopes.add(Triple(runtimeId,profile,generation))
            return GatewayResult.Success(SessionUsage(500, AccountAllowanceParser.parse(emptyList())))
        }
    }
    private class Fixture(val repo: DefaultContextUsageRepository, val vault: EncryptedVault, val adapter: Adapter, val sessions: DefaultSessionRepository)
    private suspend fun fixture(profile: String? = null): Fixture {
        val vault = EncryptedVault(folder.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = Dispatchers.Unconfined)
        vault.saveConnectionProfile(ConnectionProfile("synthetic-connection", "http://example.test:8000", "Synthetic"))
        vault.setActiveProfileId("synthetic-connection")
        vault.saveAuthCredential(AuthCredential(driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "password", cookies = mapOf("session_id" to "synthetic"), canonicalOrigin = "http://example.test:8000"), "synthetic-connection")
        if (profile != null) vault.saveSelectedContextProfile(profile)
        val adapter = Adapter()
        val sessions = DefaultSessionRepository(adapter, vault)
        sessions.createDraftSession().getOrThrow()
        return Fixture(DefaultContextUsageRepository(adapter,sessions),vault,adapter,sessions)
    }
    @Test fun usageAdmissionChecksConnectionAndActiveRuntime() = runTest {
        val f=fixture();val event=GatewayEvent.SessionUsageUpdated("runtime-sid-created-1",1,LiveUsageSummary(total=20,model="Synthetic"))
        assertEquals(ContextUsageSignalKind.USAGE_UPDATED,f.repo.admitEvent("durable-key-created-1",event)!!.kind)
        assertNull(f.repo.admitEvent("durable-key-created-1",GatewayEvent.SessionUsageUpdated("other",1,event.usage)))
        f.adapter.base.connectionStatus.value = io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.OFFLINE
        assertNull(f.repo.admitEvent("durable-key-created-1",event))
    }
    @Test fun bothReadsUseCapturedWorkspaceIncludingExplicitDefault() = runTest {
        for (profile in listOf(null,"default","synthetic-workspace")) {
            val f = fixture(profile)
            val owner = f.repo.capture("durable-key-created-1","runtime-sid-created-1")!!
            assertTrue(f.repo.read(owner) is GatewayResult.Success)
            assertEquals(2,f.adapter.scopes.size)
            assertTrue(f.adapter.scopes.all { it == Triple("runtime-sid-created-1",profile ?: "default",0L) })
        }
    }
    @Test fun workspaceAndGenerationChangesRejectReadResultsAndFurtherRequests() = runTest {
        for (change in listOf<suspend (Fixture) -> Unit>(
            { it.vault.saveSelectedContextProfile("synthetic-other") },
            { it.adapter.base.connectionGeneration++ },
            { it.adapter.base.connectionStatus.value = io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.OFFLINE }
        )) {
            val f = fixture(); val owner = f.repo.capture("durable-key-created-1","runtime-sid-created-1")!!
            f.adapter.before = { change(f) }
            assertTrue(f.repo.read(owner) is GatewayResult.Failure)
            val reads = f.adapter.scopes.size
            assertTrue(f.repo.read(owner) is GatewayResult.Failure)
            assertEquals(reads,f.adapter.scopes.size)
        }
    }
    @Test fun signalAdmissionRequiresCurrentActiveRuntimeAndScopeWithoutAnotherSubscriber() = runTest {
        val f = fixture()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.repo.signals.collect {} }
        runCurrent()
        assertEquals(0, f.adapter.base.eventFlow.subscriptionCount.value)
        assertNull(f.repo.admitEvent("durable-key-created-1", GatewayEvent.StatusUpdate("other-runtime", null, "", kind="compacted")))
        val signals = listOf(
            GatewayEvent.StatusUpdate("runtime-sid-created-1",null,"",kind="compacted"),
            GatewayEvent.StatusUpdate("runtime-sid-created-1",null,"",kind="ready"),
            GatewayEvent.SessionInfo("runtime-sid-created-1",null,mapOf("running" to false))
        ).map { f.repo.admitEvent("durable-key-created-1",it)!!.kind }
        assertEquals(listOf(ContextUsageSignalKind.COMPACTED, ContextUsageSignalKind.READY, ContextUsageSignalKind.STOPPED), signals)
        f.vault.saveSelectedContextProfile("synthetic-other")
        assertNull(f.repo.admitEvent("wrong-stored", GatewayEvent.StatusUpdate("runtime-sid-created-1",null,"",kind="ready")))
    }
}
