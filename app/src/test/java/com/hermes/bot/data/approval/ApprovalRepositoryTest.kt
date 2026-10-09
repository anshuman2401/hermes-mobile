package com.hermes.bot.data.approval

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.*
import com.hermes.bot.feature.chat.ApprovalChatController
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalRepositoryTest {
    @get:Rule val folder=TemporaryFolder()
    private class Adapter(val base: FakeGatewayProtocolAdapter=FakeGatewayProtocolAdapter()) : GatewayProtocolAdapter by base {
        val store=ApprovalInboxStore().apply { available(true) }
        override val approvalInbox=store.state
        var reads=0;var writes=0;var before: suspend ()->Unit={};var outcome=ApprovalOutcome.SENT
        var snapshot: List<ApprovalRequest> = emptyList()
        var failRead = false
        override suspend fun refreshApprovals(runtimeId: String, origin: CanonicalOrigin, generation: Long): GatewayResult<Boolean> {
            reads++;if(failRead) return GatewayResult.Failure(GatewayFailure.Transport("Synthetic read unavailable"))
            return GatewayResult.Success(store.reconcile(runtimeId,snapshot,store.state.value.revision))
        }
        override suspend fun answerApproval(request: ApprovalRequest,choice: ApprovalChoice,origin: CanonicalOrigin,generation: Long): ApprovalOutcome {
            writes++;before();return outcome
        }
    }
    private class Fixture(val repository: DefaultApprovalRepository,val vault: EncryptedVault,val adapter: Adapter,val sessions: DefaultSessionRepository)
    private suspend fun fixture(): Fixture {
        val vault=EncryptedVault(folder.newFolder(),AesGcmCipherEngine(InMemoryMasterKeyProvider()),ioDispatcher=Dispatchers.Unconfined)
        vault.saveConnectionProfile(ConnectionProfile("synthetic-connection","http://example.test:8000","Synthetic"));vault.setActiveProfileId("synthetic-connection")
        vault.saveAuthCredential(AuthCredential(driverKind=AuthCredential.DRIVER_COOKIE_SESSION,providerId="password",cookies=mapOf("session_id" to "synthetic"),canonicalOrigin="http://example.test:8000"),"synthetic-connection")
        val adapter=Adapter();val sessions=DefaultSessionRepository(adapter,vault);sessions.createDraftSession().getOrThrow()
        return Fixture(DefaultApprovalRepository(adapter,sessions),vault,adapter,sessions)
    }
    private fun request(id:String="queue-a",runtime:String="runtime-sid-created-1")=ApprovalRequest(id,runtime,"Synthetic details",ApprovalChoice.entries.toSet(),"server-$id")
    private suspend fun Fixture.attach(): ApprovalOwner {
        val owner=repository.capture("durable-key-created-1","runtime-sid-created-1")!!
        adapter.snapshot=listOf(request());adapter.store.receive(request());repository.attach(owner);repository.refresh(owner);return owner
    }
    @Test fun initialVisibilityAndEmptyDisconnectDoNotInventAnApprovalNotice() = runTest {
        val f = fixture()
        val controller = ApprovalChatController(f.repository, backgroundScope)
        controller.visibility(true)
        assertNull(controller.state.value.notice)
        assertEquals(ApprovalPhase.IDLE, controller.state.value.phase)
        controller.environment("durable-key-created-1", "runtime-sid-created-1", true)
        runCurrent()
        controller.environment("durable-key-created-1", "runtime-sid-created-1", false)
        assertNull(controller.state.value.notice)
        assertTrue(controller.state.value.requests.isEmpty())
    }
    @Test fun disconnectWithRealRequestRetainsWarningAndDisablesDecisions() = runTest {
        val f = fixture(); f.attach()
        f.repository.suspendObservation(clear = false)
        assertEquals("Reconnect to review the current request.", f.repository.state.value.notice)
        assertFalse(f.repository.state.value.canDecide)
        assertTrue(f.repository.state.value.blocksSend)
        assertEquals(1, f.repository.state.value.requests.size)
    }
    @Test fun leavingUnknownDecisionCannotShowReconnectNoticeInNextChat() = runTest {
        val f = fixture(); val owner = f.attach()
        f.adapter.outcome = ApprovalOutcome.UNKNOWN; f.adapter.failRead = true
        f.repository.decide(owner, "queue-a", ApprovalChoice.ONCE)
        assertEquals(ApprovalPhase.UNKNOWN, f.repository.state.value.phase)
        f.repository.suspendObservation(clear = true)
        f.repository.suspendObservation(clear = false)
        assertEquals(ApprovalPhase.IDLE, f.repository.state.value.phase)
        assertNull(f.repository.state.value.notice)
    }
    @Test fun fifoDedupAndRevisionGuardPreserveLiveHandles() {
        val store=ApprovalInboxStore();val first=request();store.receive(first);store.receive(first);store.receive(request("queue-b"))
        assertEquals(listOf("queue-a","queue-b"),store.state.value.requests.map { it.requestId })
        val revision=store.state.value.revision;store.cancel(first.runtimeId,"foreign","timeout")
        assertEquals(revision,store.state.value.revision)
        store.cancel(first.runtimeId,first.serverRequestId!!,"timeout")
        assertFalse(store.reconcile(first.runtimeId,listOf(first),revision));assertEquals("queue-b",store.state.value.requests.single().requestId)
        assertFalse(store.state.value.toString().contains("Synthetic"))
    }
    @Test fun doubleTapsAreSingleFlightAndConfirmedRemovalAdvancesQueue()=runTest {
        val f=fixture();val owner=f.attach();val gate=CompletableDeferred<Unit>();f.adapter.before={gate.await()}
        val write=launch{f.repository.decide(owner,"queue-a",ApprovalChoice.ONCE)};runCurrent()
        f.repository.decide(owner,"queue-a",ApprovalChoice.DENY);assertEquals(1,f.adapter.writes)
        assertFalse(f.repository.state.value.canDecide)
        f.adapter.snapshot=listOf(request("queue-b"));gate.complete(Unit);write.join()
        assertEquals("queue-b",f.repository.state.value.requests.single().requestId);assertTrue(f.repository.state.value.canDecide)
    }
    @Test fun workspaceChangesAndWrongQueueCannotAuthorizeDecisions()=runTest {
        val f=fixture();val owner=f.attach()
        f.repository.decide(owner,"other",ApprovalChoice.ONCE);assertEquals(0,f.adapter.writes)
        f.vault.saveSelectedContextProfile("synthetic-other");assertFalse(f.repository.current(owner))
        f.repository.decide(owner,"queue-a",ApprovalChoice.ALWAYS);assertEquals(0,f.adapter.writes)
        f.repository.observe(owner);assertTrue(f.repository.state.value.requests.isEmpty())
    }
    @Test fun allChoicesUseRepositoryAndUnknownWriteIsNeverAutomaticallyResent()=runTest {
        for(choice in ApprovalChoice.entries){val f=fixture();val owner=f.attach();f.adapter.outcome=ApprovalOutcome.UNKNOWN
            f.repository.decide(owner,"queue-a",choice);assertEquals(1,f.adapter.writes)
            repeat(3){f.repository.refresh(owner)};assertEquals(1,f.adapter.writes)
            assertTrue(f.repository.state.value.canDecide) // host confirmed exact request still pending
        }
    }
    @Test fun unknownAndUnavailableReadsKeepControlsDisabledUntilReconciled()=runTest {
        val f=fixture();val owner=f.attach();f.adapter.outcome=ApprovalOutcome.UNKNOWN;f.adapter.failRead=true
        f.repository.decide(owner,"queue-a",ApprovalChoice.ONCE)
        assertEquals(ApprovalPhase.UNKNOWN,f.repository.state.value.phase);assertFalse(f.repository.state.value.canDecide)
        f.repository.decide(owner,"queue-a",ApprovalChoice.ONCE);assertEquals(1,f.adapter.writes)
        f.adapter.failRead=false;f.adapter.snapshot=emptyList();f.repository.refresh(owner)
        assertTrue(f.repository.state.value.requests.isEmpty());assertFalse(f.repository.state.value.blocksSend)
        assertNull(f.repository.state.value.notice)
    }
    @Test fun confirmedEmptyClearsSentNoticeAndExpiryBelongsOnlyToTheCancelledRequest()=runTest {
        val f=fixture();val owner=f.attach();f.adapter.snapshot=emptyList()
        f.repository.decide(owner,"queue-a",ApprovalChoice.ONCE)
        assertEquals(ApprovalPhase.IDLE,f.repository.state.value.phase);assertNull(f.repository.state.value.notice)
        val expired=request("expired");f.adapter.store.receive(expired)
        f.adapter.store.cancel(expired.runtimeId,expired.serverRequestId!!,"timeout")
        f.repository.refresh(owner)
        assertEquals("This request expired. The command did not run.",f.repository.state.value.notice)
        f.adapter.snapshot=listOf(request("next"));f.adapter.store.receive(request("next"));f.repository.refresh(owner)
        assertNull(f.repository.state.value.notice);assertFalse(f.adapter.store.state.value.cancelled.containsKey(owner.runtimeId))
        f.adapter.snapshot=emptyList();f.repository.refresh(owner);assertNull(f.repository.state.value.notice)
    }
    @Test fun restrictionUpdatesKeepLiveHandleButCannotPreserveAnOldAllowance() {
        val store=ApprovalInboxStore();val request=request();store.receive(request)
        store.reconcile(request.runtimeId,listOf(request.copy(serverRequestId=null,choices=setOf(ApprovalChoice.DENY))),store.state.value.revision)
        assertEquals(request.serverRequestId,store.state.value.requests.single().serverRequestId)
        assertEquals(setOf(ApprovalChoice.DENY),store.state.value.requests.single().choices)
        store.reset();repeat(130){store.receive(request("synthetic-$it"))};assertEquals(128,store.state.value.requests.size)
    }
    @Test fun controllerVisibilityOwnsPollingAndForeignRequestsStayOutOfChat()=runTest {
        val f=fixture();f.adapter.snapshot=listOf(request());f.adapter.store.receive(request("foreign","other-runtime"))
        val controller=ApprovalChatController(f.repository,backgroundScope)
        controller.environment("durable-key-created-1","runtime-sid-created-1",true);controller.visibility(true);runCurrent()
        assertEquals("queue-a",controller.state.value.requests.single().requestId)
        advanceTimeBy(5001);runCurrent();assertTrue(f.adapter.reads>=2)
        controller.visibility(false);runCurrent();val reads=f.adapter.reads
        advanceTimeBy(15000);runCurrent();assertEquals(reads,f.adapter.reads);assertTrue(controller.state.value.requests.isEmpty())
        controller.decide("queue-a",ApprovalChoice.ONCE);runCurrent();assertEquals(0,f.adapter.writes)
    }
}
