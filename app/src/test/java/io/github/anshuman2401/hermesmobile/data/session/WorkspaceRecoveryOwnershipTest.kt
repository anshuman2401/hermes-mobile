package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.auth.*
import io.github.anshuman2401.hermesmobile.data.context.*
import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceRecoveryOwnershipTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()
    private val origin = "http://example.test:9711"
    private fun credential(value: String) = AuthCredential("cookie_session", "basic", mapOf("session" to value), origin)
    private suspend fun vault(): EncryptedVault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher).also {
        it.saveConnectionProfile(ConnectionProfile("synthetic-local", origin, "Synthetic"))
        it.saveAuthCredential(credential("synthetic-original")); it.saveSelectedContextProfile("A")
    }
    private class Transport : AuthHttpTransport {
        var calls = 0
        var header: String? = null
        var hook: suspend () -> Unit = {}
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            calls++; header = headers["Cookie"]; hook()
            return HttpResponse(404, body = "{\"detail\":\"Profile 'synthetic' does not exist.\"}")
        }
    }

    @Test fun noShellRequirementDoesNotClearPublishRowsOrNoticeAndConsumesOriginalOwnerOnce() = runBlocking(main.testDispatcher) {
        val vault = vault(); val transport = Transport()
        val repo = DefaultSessionRepository(FakeGatewayProtocolAdapter(), vault, contextProfileRepository = DefaultContextProfileRepository(vault, transport))
        val result = repo.listSessions().getOrThrow()
        assertTrue(result.sessions.isEmpty()); assertNull(result.staleProfileNotice)
        assertEquals("A", vault.getSelectedContextProfile())
        val request = result.recoveryRequirement!!
        assertFalse(request.toString().contains("synthetic-original"))
        assertEquals("session=synthetic-original", transport.header)
        assertEquals(WorkspaceRecoveryApplyResult.Applied, repo.applyWorkspaceRecovery(request))
        assertSame(SelectedContextReadResult.Absent, vault.readSelectedContextProfileAuthoritative())
        assertEquals(WorkspaceRecoveryApplyResult.Stale, repo.applyWorkspaceRecovery(request))
        assertEquals(1, transport.calls)
    }

    @Test fun late404CannotClearBOrUseNewSameOriginLogin() = runBlocking(main.testDispatcher) {
        for (login in listOf(false, true)) {
            val vault = vault(); val transport = Transport()
            transport.hook = { if (login) vault.saveAuthCredential(credential("synthetic-new")) else vault.saveSelectedContextProfile("B") }
            val repo = DefaultSessionRepository(FakeGatewayProtocolAdapter(), vault, contextProfileRepository = DefaultContextProfileRepository(vault, transport))
            val requirement = repo.listSessions().getOrThrow().recoveryRequirement!!
            assertEquals("session=synthetic-original", transport.header)
            assertEquals(WorkspaceRecoveryApplyResult.Stale, repo.applyWorkspaceRecovery(requirement))
            assertEquals(if (login) "A" else "B", vault.getSelectedContextProfile())
        }
    }

    @Test fun capturedFetchRejectsChangedLeaseBeforeDispatchAndPropagatesCancellation() = runBlocking(main.testDispatcher) {
        val vault = vault(); val transport = Transport(); val context = DefaultContextProfileRepository(vault, transport)
        val lease = vault.readAuthCredentialLease(origin)!!
        vault.saveAuthCredential(credential("synthetic-new"))
        assertTrue(context.getScopedSessionsCaptured("A", lease) is ScopedSessionsResult.Error)
        assertEquals(0, transport.calls)
        val current = vault.readAuthCredentialLease(origin)!!
        transport.hook = { throw CancellationException("synthetic cancel") }
        try { context.getScopedSessionsCaptured("A", current); fail("cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals("A", vault.getSelectedContextProfile())
    }

    @Test fun selectionUnavailableNeverGuessesUnscopedList() = runBlocking(main.testDispatcher) {
        val vault = vault()
        val storage = object : VaultStorage by vault {
            override suspend fun captureWorkspaceRecoveryOwner() = WorkspaceRecoveryOwnerResult.Unavailable
        }
        val gateway = FakeGatewayProtocolAdapter()
        val repo = DefaultSessionRepository(gateway, storage)
        assertTrue(repo.listSessions().isFailure)
        assertEquals("A", vault.getSelectedContextProfile())
    }
    @Test fun sharedRecoveryResetsRealOwnerRestampsOnceAndReloadsOnlyCertifiedAbsence() = runBlocking(main.testDispatcher) {
        val vault = vault(); val transport = Transport()
        val gateway = FakeGatewayProtocolAdapter().apply {
            listSessionsResult = GatewayResult.Success(SessionListResult(listOf(SessionSummary("synthetic-default", "Default", null, 1.0, 1, null))))
        }
        val context = DefaultContextProfileRepository(vault, transport)
        val repo = DefaultSessionRepository(gateway, vault, contextProfileRepository = context)
        val sessions = io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel(repo)
        val received = CompletableDeferred<Pair<WorkspaceRecoveryRequest, Long>>()
        sessions.setWorkspaceRecoveryHandler { request, stamp -> received.complete(request to stamp) }
        val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel { io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel(it) }
        val child = owner.get("synthetic-old")!!
        val childJob = child.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!
        var resets = 0; var transition = false
        try {
            val (pending, old) = withContext(Dispatchers.Default) { withTimeout(5000) { received.await() } }
            assertNotNull(pending)
            val applied = io.github.anshuman2401.hermesmobile.navigation.performWorkspaceRecovery(pending, old, context, owner, sessions,
                resetShell = { resets++; sessions.resetWorkspace() }, onTransition = { transition = it })
            assertEquals(WorkspaceRecoveryApplyResult.Applied, applied)
            assertFalse(transition); assertEquals(1, resets)
            assertTrue(childJob.isCancelled); childJob.join(); assertTrue(owner.activeKeys().isEmpty())
            assertFalse(sessions.isWorkspaceIntentCurrent(old))
            val state = withContext(Dispatchers.Default) { withTimeout(5000) { sessions.uiState.first { !it.isLoading } } }
            assertFalse(state.isLoading); assertNull(state.error); assertNull(state.selectedProfileName)
            assertEquals(listOf("synthetic-default"), state.sessions.map { it.id })
            assertEquals(ScopedSessionsResult.PROFILE_GONE_NOTICE, state.staleProfileNotice)
            assertEquals(WorkspaceRecoveryApplyResult.Stale,
                io.github.anshuman2401.hermesmobile.navigation.performWorkspaceRecovery(pending, old, context, owner, sessions, { resets++; sessions.resetWorkspace() }))
            assertEquals(1, resets)
        } finally {
            sessions.setWorkspaceRecoveryHandler(null)
            val job = sessions.viewModelScope.coroutineContext[Job]!!
            androidx.lifecycle.ViewModelStore().apply { put("sessions", sessions); clear() }; job.join()
            owner.activeKeys().forEach(owner::clear)
        }
    }

    @Test fun lateRecoveryGenerationCannotClearBOrReturnedAEvenWhenRequestOwnerEqualsAgain() = runBlocking(main.testDispatcher) {
        val vault = vault(); val transport = Transport(); val context = DefaultContextProfileRepository(vault, transport)
        val repo = DefaultSessionRepository(FakeGatewayProtocolAdapter(), vault, contextProfileRepository = context)
        val sessions = io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel(repo)
        val received = CompletableDeferred<Pair<WorkspaceRecoveryRequest, Long>>()
        sessions.setWorkspaceRecoveryHandler { request, generation -> received.complete(request to generation) }
        val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel()
        var resets = 0
        try {
            val (pending, stamp) = withContext(Dispatchers.Default) { withTimeout(5000) { received.await() } }
            assertNotNull(pending)
            io.github.anshuman2401.hermesmobile.navigation.performWorkspaceSelection("B", context, owner) { sessions.resetWorkspace() }
            assertEquals(WorkspaceRecoveryApplyResult.Stale,
                io.github.anshuman2401.hermesmobile.navigation.performWorkspaceRecovery(pending, stamp, context, owner, sessions, { resets++ }))
            assertEquals("B", vault.getSelectedContextProfile())
            io.github.anshuman2401.hermesmobile.navigation.performWorkspaceSelection("A", context, owner) { sessions.resetWorkspace() }
            assertEquals(WorkspaceRecoveryApplyResult.Stale,
                io.github.anshuman2401.hermesmobile.navigation.performWorkspaceRecovery(pending, stamp, context, owner, sessions, { resets++ }))
            assertEquals("A", vault.getSelectedContextProfile()); assertEquals(0, resets)
        } finally {
            sessions.setWorkspaceRecoveryHandler(null)
            val job = sessions.viewModelScope.coroutineContext[Job]!!
            androidx.lifecycle.ViewModelStore().apply { put("sessions", sessions); clear() }; job.join()
        }
    }

    @Test fun realLate404AfterBOrReturnedACannotForwardRecoveryOrClearSelection() = runBlocking(main.testDispatcher) {
        for (returnedA in listOf(false, true)) {
            val vault = vault(); val transport = Transport()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val terminal = CompletableDeferred<Unit>()
            transport.hook = {
                entered.complete(Unit)
                try { withContext(NonCancellable) { release.await() } }
                finally { terminal.complete(Unit) }
            }
            val context = DefaultContextProfileRepository(vault, transport)
            val repo = DefaultSessionRepository(FakeGatewayProtocolAdapter(), vault, contextProfileRepository = context)
            val listJob = CompletableDeferred<Job>()
            val composed = object : SessionRepository by repo {
                override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> {
                    listJob.complete(currentCoroutineContext()[Job]!!)
                    return repo.listSessions(forceRefresh)
                }
            }
            val sessions = io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel(composed)
            var forwarded = 0
            sessions.setWorkspaceRecoveryHandler { _, _ -> forwarded++ }
            val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel()
            try {
                withContext(Dispatchers.Default) { withTimeout(5000) { entered.await() } }
                io.github.anshuman2401.hermesmobile.navigation.performWorkspaceSelection("B", context, owner, sessions = sessions) { sessions.resetWorkspace() }
                if (returnedA) io.github.anshuman2401.hermesmobile.navigation.performWorkspaceSelection("A", context, owner, sessions = sessions) { sessions.resetWorkspace() }
                release.complete(Unit)
                withContext(Dispatchers.Default) { withTimeout(5000) { terminal.await() } }
                // Keep the VM/handler alive; only the production reset may cancel this list.
                withContext(Dispatchers.Default) { withTimeout(5000) { listJob.await().join() } }
                assertTrue(sessions.viewModelScope.coroutineContext[Job]!!.isActive)
                assertEquals(0, forwarded); assertEquals(1, transport.calls)
                assertEquals(if (returnedA) "A" else "B", vault.getSelectedContextProfile())
                assertTrue(sessions.uiState.value.sessions.isEmpty())
                assertNull(sessions.uiState.value.staleProfileNotice); assertNull(sessions.uiState.value.selectedSession)
                assertTrue(owner.activeKeys().isEmpty())
            } finally {
                release.complete(Unit); sessions.setWorkspaceRecoveryHandler(null)
                val job = sessions.viewModelScope.coroutineContext[Job]!!
                androidx.lifecycle.ViewModelStore().apply { put("sessions", sessions); clear() }
                withContext(Dispatchers.Default) { withTimeout(5000) { job.join() } }
            }
        }
    }

    @Test fun recoveryAdmissionVetoReleasesReservedTransitionWithoutResetOrConsumption() = runBlocking(main.testDispatcher) {
        val vault = vault(); val context = DefaultContextProfileRepository(vault, Transport())
        val repo = DefaultSessionRepository(FakeGatewayProtocolAdapter(), vault, contextProfileRepository = context)
        val request = repo.listSessions().getOrThrow().recoveryRequirement!!
        val sessions = io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel()
        val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel { io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel(it) }
        owner.get("synthetic-old")
        var resets = 0
        try {
            val original = sessions.captureWorkspaceIntent()
            sessions.resetWorkspace()
            for (missingRepository in listOf(false, true)) {
                var transition = true // The shell reserves this synchronously before launch.
                val result = io.github.anshuman2401.hermesmobile.navigation.performWorkspaceRecovery(request,
                    if (missingRepository) sessions.captureWorkspaceIntent() else original,
                    if (missingRepository) null else context, owner, sessions,
                    resetShell = { resets++; sessions.resetWorkspace() }, onTransition = { transition = it })
                assertEquals(WorkspaceRecoveryApplyResult.Stale, result)
                assertFalse("admission veto must release the reserved transition", transition)
                assertEquals(0, resets); assertEquals(listOf("synthetic-old"), owner.activeKeys())
                assertEquals("A", vault.getSelectedContextProfile())
            }
            // Neither refused admission consumed the original one-use request.
            assertEquals(WorkspaceRecoveryApplyResult.Applied, repo.applyWorkspaceRecovery(request))
            assertSame(SelectedContextReadResult.Absent, vault.readSelectedContextProfileAuthoritative())
        } finally { owner.activeKeys().forEach(owner::clear) }
    }

    @Test fun failedGuardedRecoveryKeepsNoChatAndCannotReloadOrAdvertiseSuccess() = runBlocking(main.testDispatcher) {
        val vault = vault(); val transport = Transport()
        val storage = object : VaultStorage by vault {
            override suspend fun clearSelectedContextProfileIfCurrent(expectedLease: AuthCredentialLease, expectedContext: String) = WorkspaceClearResult.Failed
        }
        val context = DefaultContextProfileRepository(storage, transport)
        val gateway = FakeGatewayProtocolAdapter()
        val repo = DefaultSessionRepository(gateway, storage, contextProfileRepository = context)
        val sessions = io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel(repo)
        val received = CompletableDeferred<Pair<WorkspaceRecoveryRequest, Long>>()
        sessions.setWorkspaceRecoveryHandler { request, generation -> received.complete(request to generation) }
        val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel { io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel(it) }
        val child = owner.get("synthetic-old")!!
        val childJob = child.viewModelScope.coroutineContext[Job]!!
        var transition = true; var resets = 0
        try {
            val (request, generation) = withContext(Dispatchers.Default) { withTimeout(5000) { received.await() } }
            val result = io.github.anshuman2401.hermesmobile.navigation.performWorkspaceRecovery(request, generation, context, owner, sessions,
                resetShell = { resets++; sessions.resetWorkspace() }, onTransition = { transition = it })
            assertEquals(WorkspaceRecoveryApplyResult.Failed, result)
            assertFalse(transition); assertEquals(1, resets); assertTrue(owner.activeKeys().isEmpty())
            assertTrue(childJob.isCancelled); childJob.join()
            assertEquals("A", vault.getSelectedContextProfile()); assertEquals(1, transport.calls)
            val state = sessions.uiState.value
            assertFalse(state.isLoading); assertNotNull(state.error)
            assertTrue(state.sessions.isEmpty()); assertNull(state.selectedSession); assertNull(state.staleProfileNotice)
            assertEquals(WorkspaceRecoveryApplyResult.Stale, repo.applyWorkspaceRecovery(request))
        } finally {
            sessions.setWorkspaceRecoveryHandler(null)
            val job = sessions.viewModelScope.coroutineContext[Job]!!
            androidx.lifecycle.ViewModelStore().apply { put("sessions", sessions); clear() }; job.join()
            owner.activeKeys().forEach(owner::clear)
        }
    }

    @Test fun implicitRecoveryCancelsActualStoredGetAndCannotPublishAfterLateBody() = runBlocking(main.testDispatcher) {
        io.github.anshuman2401.hermesmobile.feature.chat.StoredChatFixture(temp.newFolder(), main.testDispatcher).use { fixture ->
            fixture.initialize()
            fixture.bodyGate = CompletableDeferred(); fixture.bodyEntered = CompletableDeferred(); fixture.nonCooperativeBody = true
            val transport = Transport()
            val context = DefaultContextProfileRepository(fixture.vault, transport)
            val listRepo = DefaultSessionRepository(fixture.gateway, fixture.vault, contextProfileRepository = context)
            val composed = object : SessionRepository by fixture.repository {
                override suspend fun listSessions(forceRefresh: Boolean) = listRepo.listSessions(forceRefresh)
                override suspend fun applyWorkspaceRecovery(request: WorkspaceRecoveryRequest) = listRepo.applyWorkspaceRecovery(request)
            }
            val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel { fixture.vm(repo = composed) }
            val child = owner.get("synthetic-session")!!
            val childJob = child.viewModelScope.coroutineContext[Job]!!
            val sessions = io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel(composed)
            val received = CompletableDeferred<Pair<WorkspaceRecoveryRequest, Long>>()
            sessions.setWorkspaceRecoveryHandler { pending, stamp -> received.complete(pending to stamp) }
            try {
                withContext(Dispatchers.Default) { withTimeout(5000) { fixture.bodyEntered!!.await() } }
                val (request, generation) = withContext(Dispatchers.Default) { withTimeout(5000) { received.await() } }
                assertNotNull(request)
                io.github.anshuman2401.hermesmobile.navigation.performWorkspaceRecovery(request, generation, context, owner, sessions, { sessions.resetWorkspace() })
                assertTrue(childJob.isCancelled); assertTrue(owner.activeKeys().isEmpty())
                fixture.bodyGate!!.complete(Unit)
                withContext(Dispatchers.Default) { withTimeout(5000) { childJob.join() } }
                assertTrue(child.uiState.value.timeline.none { it is io.github.anshuman2401.hermesmobile.feature.chat.ToolActivity && it.storedSource != null })
                assertFalse(fixture.cache.load("synthetic-session") is io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheReadResult.Snapshot)
                assertSame(SelectedContextReadResult.Absent, fixture.vault.readSelectedContextProfileAuthoritative())
            } finally {
                fixture.bodyGate!!.complete(Unit); owner.activeKeys().forEach(owner::clear)
                sessions.setWorkspaceRecoveryHandler(null)
                val job = sessions.viewModelScope.coroutineContext[Job]!!
                androidx.lifecycle.ViewModelStore().apply { put("sessions", sessions); clear() }
                withContext(Dispatchers.Default) { withTimeout(5000) { job.join(); childJob.join() } }
            }
        }
    }

}
