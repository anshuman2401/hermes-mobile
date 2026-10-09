package com.hermes.bot.data.subagent

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.*
import com.hermes.bot.feature.chat.SubagentChatController
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private class Adapter(val base: FakeGatewayProtocolAdapter = FakeGatewayProtocolAdapter()) : GatewayProtocolAdapter by base {
        var listReads = 0; var tails = 0; var writes = 0
        var before: suspend () -> Unit = {}
        var list: GatewayResult<SubagentRoster> = GatewayResult.Success(SubagentRoster(listOf(Subagent("child", "Synthetic task", SubagentStatus.RUNNING))))
        var outcome = SubagentControl.QUEUED
        override suspend fun listSubagents(runtimeId: String, origin: CanonicalOrigin, generation: Long): GatewayResult<SubagentRoster> {
            assertEquals("runtime-sid-created-1",runtimeId); assertEquals("http://example.test:8000",origin.originUrl)
            listReads++; before(); return list
        }
        override suspend fun subagentTail(runtimeId: String, childId: String, origin: CanonicalOrigin, generation: Long): GatewayResult<SubagentTail> {
            tails++; return GatewayResult.Success(SubagentTail(true,"Synthetic transcript",false))
        }
        override suspend fun controlSubagent(runtimeId: String, childId: String, text: String?, origin: CanonicalOrigin, generation: Long): SubagentControl {
            writes++; before(); return outcome
        }
    }
    private class Fixture(val repository: DefaultSubagentRepository, val vault: EncryptedVault, val adapter: Adapter)
    private suspend fun fixture(): Fixture {
        val vault=EncryptedVault(folder.newFolder(),AesGcmCipherEngine(InMemoryMasterKeyProvider()),ioDispatcher=Dispatchers.Unconfined)
        vault.saveConnectionProfile(ConnectionProfile("synthetic-connection","http://example.test:8000","Synthetic"))
        vault.setActiveProfileId("synthetic-connection")
        vault.saveAuthCredential(AuthCredential(driverKind=AuthCredential.DRIVER_COOKIE_SESSION,providerId="password",
            cookies=mapOf("session_id" to "synthetic"),canonicalOrigin="http://example.test:8000"),"synthetic-connection")
        val adapter=Adapter(); val sessions=DefaultSessionRepository(adapter,vault)
        sessions.createDraftSession().getOrThrow()
        return Fixture(DefaultSubagentRepository(adapter,sessions),vault,adapter)
    }
    private suspend fun Fixture.owner() = repository.capture("durable-key-created-1","runtime-sid-created-1")!!
    @Test fun snapshotCannotOverwriteANewerCompletionAndForeignEventsAreIgnored() = runTest {
        val f=fixture(); val owner=f.owner(); f.repository.attach(owner)
        f.adapter.before={ f.repository.admit(owner,GatewayEvent.SubagentUpdate(owner.runtimeId,null,"subagent.complete",Subagent("child","Synthetic task",SubagentStatus.FAILED))) }
        f.repository.refresh(owner)
        assertEquals(SubagentStatus.FAILED,f.repository.state.value.children.single().status)
        f.repository.admit(owner,GatewayEvent.SubagentUpdate("other",null,"subagent.start",Subagent("other","Synthetic",SubagentStatus.RUNNING)))
        assertEquals(1,f.repository.state.value.children.size)
    }
    @Test fun unsupportedRosterStopsReadsAndWorkspaceChangeRejectsReadsAndWrites() = runTest {
        val f=fixture(); val owner=f.owner(); f.repository.attach(owner)
        f.adapter.list=GatewayResult.Failure(GatewayFailure.Rpc(-32601,"Synthetic unsupported"))
        f.repository.refresh(owner); f.repository.refresh(owner)
        assertEquals(1,f.adapter.listReads)
        f.vault.saveSelectedContextProfile("synthetic-other")
        assertFalse(f.repository.isCurrent(owner))
        assertEquals(SubagentControl.UNAVAILABLE,f.repository.control(owner,"child","Synthetic steer"))
        assertNull(f.repository.tail(owner,"child"))
        assertEquals(0,f.adapter.writes)
    }
    @Test fun controllerStopsPollingInBackgroundAndNeverRetriesAnAmbiguousWrite() = runTest {
        val f=fixture(); val controller=SubagentChatController(f.repository,backgroundScope)
        controller.environment("durable-key-created-1","runtime-sid-created-1",true); controller.visibility(true); runCurrent()
        assertTrue(controller.state.value.roster.children.isNotEmpty())
        controller.open(controller.state.value.roster.children.single());controller.draft("Synthetic steer")
        val gate=CompletableDeferred<Unit>();f.adapter.before={gate.await()};f.adapter.outcome=SubagentControl.UNKNOWN
        controller.control(true);controller.control(true);runCurrent();assertEquals(1,f.adapter.writes)
        gate.complete(Unit);runCurrent();assertTrue(controller.state.value.feedback!!.startsWith("Outcome unknown"))
        assertEquals("Synthetic steer",controller.state.value.draft)
        controller.transcript();runCurrent();assertEquals(1,f.adapter.tails)
        controller.visibility(false);runCurrent()
        val reads=f.adapter.listReads;val tails=f.adapter.tails
        advanceTimeBy(20000);runCurrent()
        assertEquals(reads,f.adapter.listReads);assertEquals(tails,f.adapter.tails);assertEquals(1,f.adapter.writes)
        assertNull(controller.state.value.transcript);assertEquals("",controller.state.value.draft)
        assertEquals(0,f.adapter.base.eventFlow.subscriptionCount.value)
    }
    @Test fun rejectedWriteRetainsDraftAndQueuedWriteClearsIt() = runTest {
        val f=fixture();val controller=SubagentChatController(f.repository,backgroundScope)
        controller.environment("durable-key-created-1","runtime-sid-created-1",true);controller.visibility(true);runCurrent()
        controller.open(controller.state.value.roster.children.single());controller.draft("Synthetic instructions")
        f.adapter.outcome=SubagentControl.REJECTED;controller.control(true);runCurrent()
        assertEquals("Synthetic instructions",controller.state.value.draft)
        f.adapter.outcome=SubagentControl.QUEUED;controller.control(true);runCurrent()
        assertEquals("",controller.state.value.draft)
        assertEquals("Instruction queued.",controller.state.value.feedback)
        controller.transcript();runCurrent();controller.back();assertFalse(controller.state.value.transcriptVisible)
        assertNotNull(controller.state.value.selectedId);controller.back();assertNull(controller.state.value.selectedId)
    }
    @Test fun unsupportedControlsAreDisabledAndRouteChangeClearsChildren() = runTest {
        val f=fixture();val controller=SubagentChatController(f.repository,backgroundScope)
        controller.environment("durable-key-created-1","runtime-sid-created-1",true);controller.visibility(true);runCurrent()
        controller.open(controller.state.value.roster.children.single());controller.draft("Synthetic steer")
        f.adapter.outcome=SubagentControl.UNAVAILABLE;controller.control(true);runCurrent();controller.control(true);runCurrent()
        assertEquals(1,f.adapter.writes)
        assertTrue("steer" in controller.state.value.roster.disabledControls)
        controller.environment(null,null,false);runCurrent()
        assertTrue(controller.state.value.roster.children.isEmpty())
        assertNull(controller.state.value.selectedId)
    }
    @Test fun fallbackDescriptionNeverAuthorizesControlsOrTailEvenWithARealIdCollision() = runTest {
        val f=fixture();val controller=SubagentChatController(f.repository,backgroundScope)
        controller.environment("durable-key-created-1","runtime-sid-created-1",true);controller.visibility(true);runCurrent()
        controller.open(controller.state.value.roster.children.single().copy(observed=false))
        controller.draft("Synthetic steer");controller.control(true);controller.control(false);controller.transcript();runCurrent()
        assertEquals(0,f.adapter.writes)
        assertEquals(0,f.adapter.tails)
        assertFalse(controller.state.value.transcriptVisible)
    }
    @Test fun staleOwnerRefreshClearsItsTransientRoster() = runTest {
        val f=fixture();val owner=f.owner();f.repository.attach(owner);f.repository.refresh(owner)
        f.vault.saveSelectedContextProfile("synthetic-other")
        f.repository.refresh(owner)
        assertTrue(f.repository.state.value.children.isEmpty())
        assertFalse(f.repository.state.value.available)
    }
    @Test fun ownershipChangedDuringAWriteYieldsUnknownWithoutResend() = runTest {
        val f=fixture();val owner=f.owner();f.repository.attach(owner);f.repository.refresh(owner)
        f.adapter.before={f.vault.saveSelectedContextProfile("synthetic-other")}
        assertEquals(SubagentControl.UNKNOWN,f.repository.control(owner,"child","Synthetic steer"))
        assertEquals(1,f.adapter.writes)
        assertEquals(SubagentControl.UNAVAILABLE,f.repository.control(owner,"child","Synthetic steer"))
        assertEquals(1,f.adapter.writes)
    }
}
