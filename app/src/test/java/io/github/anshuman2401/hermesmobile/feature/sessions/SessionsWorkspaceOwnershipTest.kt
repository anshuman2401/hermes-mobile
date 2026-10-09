package io.github.anshuman2401.hermesmobile.feature.sessions

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.feature.chat.ChatStreamingStatus
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsWorkspaceOwnershipTest {
    @get:Rule val main = MainDispatcherRule()

    private class Repository : SessionRepository {
        override val activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeTurnStreaming = MutableStateFlow(ChatStreamingStatus())
        var list: suspend () -> Result<SessionListOutcome> = { Result.success(outcome("A")) }
        var models: suspend () -> Result<Map<String, String>> = { Result.success(emptyMap()) }
        var restore: suspend () -> SessionHandle? = { null }
        var create: suspend () -> Result<SessionHandle> = { Result.success(SessionHandle("new", "runtime", isDraft = true)) }
        override suspend fun listSessions(forceRefresh: Boolean) = list()
        override suspend fun activeSessionModels() = models()
        override suspend fun restoreSelectedSession() = restore()
        override suspend fun createDraftSession(title: String?) = create()
        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> = error("explicit chat owner resumes")
        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> = error("unused")
        override suspend fun clearActiveSession() = Unit
        override suspend fun getActiveSession(): SessionHandle? = null
        companion object {
            fun outcome(name: String) = SessionListOutcome(listOf(SessionSummary("synthetic-$name", name, null, 1.0, 1, null)), false, name)
        }
    }

    private suspend fun close(vm: SessionsViewModel) {
        val job = vm.viewModelScope.coroutineContext[Job]!!
        ViewModelStore().apply { put("sessions", vm); clear() }
        job.join()
    }

    @Test fun resetRejectsLateListAndRestoreEvenAfterReturnedWorkspace() = runTest(main.testDispatcher) {
        val listEntered = CompletableDeferred<Unit>(); val listRelease = CompletableDeferred<Unit>()
        val restoreEntered = CompletableDeferred<Unit>(); val restoreRelease = CompletableDeferred<Unit>()
        val repo = Repository().apply {
            list = { listEntered.complete(Unit); withContext(NonCancellable) { listRelease.await() }; Result.success(Repository.outcome("A")) }
            restore = { restoreEntered.complete(Unit); withContext(NonCancellable) { restoreRelease.await() }; SessionHandle("old", null, isDraft = false) }
        }
        val vm = SessionsViewModel(repo)
        try {
            listEntered.await(); restoreEntered.await()
            vm.resetWorkspace(); vm.resetWorkspace()
            repo.list = { Result.success(Repository.outcome("returned-A")) }
            vm.loadSessions(true); runCurrent()
            listRelease.complete(Unit); restoreRelease.complete(Unit); runCurrent()
            assertEquals("returned-A", vm.uiState.value.selectedProfileName)
            assertEquals(listOf("synthetic-returned-A"), vm.uiState.value.sessions.map { it.id })
            assertNull(vm.uiState.value.selectedSession)
            assertFalse(vm.uiState.value.isLoading); assertNull(vm.uiState.value.error)
        } finally { listRelease.complete(Unit); restoreRelease.complete(Unit); close(vm) }
    }

    @Test fun resetRejectsBothInlineModelAndPollCompletion() = runTest(main.testDispatcher) {
        for (poll in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val repo = Repository()
            if (!poll) repo.models = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; Result.success(mapOf("synthetic-A" to "old-model")) }
            val vm = SessionsViewModel(repo)
            try {
                if (poll) {
                    vm.resumePolling()
                    repo.models = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; Result.success(mapOf("synthetic-A" to "old-model")) }
                    advanceTimeBy(10_000); runCurrent()
                }
                entered.await()
                vm.resetWorkspace()
                repo.list = { Result.success(Repository.outcome("B")) }
                repo.models = { Result.success(mapOf("synthetic-B" to "new-model")) }
                vm.loadSessions(true); runCurrent()
                release.complete(Unit); runCurrent()
                assertEquals("B", vm.uiState.value.selectedProfileName)
                assertEquals(mapOf("synthetic-B" to "new-model"), vm.uiState.value.activeModels)
                vm.pausePolling(); assertFalse(vm.isPollingActive)
            } finally { release.complete(Unit); close(vm) }
        }
    }

    @Test fun createResultCancellationRethrowsAndFinallyClearsWithoutFailureNotice() = runTest(main.testDispatcher) {
        val repo = Repository().apply { create = { Result.failure(CancellationException("synthetic cancellation")) } }
        val vm = SessionsViewModel(repo)
        var callbacks = 0
        try {
            vm.createNewChat { callbacks++ }; runCurrent()
            assertEquals(0, callbacks)
            assertFalse(vm.uiState.value.isCreatingSession)
            assertNull("cancellation is not a create error", vm.uiState.value.newChatFailure)
        } finally { close(vm) }
    }

    @Test fun createHasCurrentIntentAndRejectsOldDeferredSuccessAfterResetFlagsClear() = runTest(main.testDispatcher) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = Repository().apply { create = { entered.complete(Unit); release.await(); Result.success(SessionHandle("created-A", "runtime", isDraft = true)) } }
        val vm = SessionsViewModel(repo)
        var callbacks = 0
        try {
            val intent = vm.captureWorkspaceIntent()
            vm.createNewChat { if (vm.isWorkspaceIntentCurrent(intent)) callbacks++ }
            entered.await(); assertTrue(vm.uiState.value.isCreatingSession)
            vm.resetWorkspace(); release.complete(Unit); runCurrent()
            assertFalse(vm.isWorkspaceIntentCurrent(intent)); assertFalse(vm.uiState.value.isCreatingSession)
            assertEquals(0, callbacks); assertNull(vm.uiState.value.selectedSession)
        } finally { release.complete(Unit); close(vm) }
    }
    @Test fun inFlightCreateVetoPrecedesRealOwnerResetAndSameCurrentCreateNavigatesOnce() = runTest(main.testDispatcher) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var creates = 0
        val repo = Repository().apply { create = { creates++; entered.complete(Unit); release.await(); Result.success(SessionHandle("created-A", "runtime", isDraft = true)) } }
        val vm = SessionsViewModel(repo)
        val folder = java.nio.file.Files.createTempDirectory("synthetic-workspace").toFile()
        val vault = io.github.anshuman2401.hermesmobile.data.local.EncryptedVault(folder, io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine(io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        vault.saveSelectedContextProfile("A")
        val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel { io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel(it) }
        owner.get("synthetic-old")
        var resets = 0; var callbacks = 0
        try {
            vm.createNewChat { callbacks++ }; entered.await()
            vm.createNewChat { callbacks++ }
            val result = io.github.anshuman2401.hermesmobile.navigation.performWorkspaceSelection("B", io.github.anshuman2401.hermesmobile.data.context.DefaultContextProfileRepository(vault), owner,
                sessions = vm) { resets++; vm.resetWorkspace() }
            assertTrue(result is io.github.anshuman2401.hermesmobile.data.context.ContextProfileSelectionResult.Error)
            assertEquals(0, resets); assertEquals("A", vault.getSelectedContextProfile())
            assertEquals(listOf("synthetic-old"), owner.activeKeys())
            release.complete(Unit); runCurrent()
            assertEquals(1, creates); assertEquals(1, callbacks); assertFalse(vm.uiState.value.isCreatingSession)
        } finally { release.complete(Unit); close(vm); owner.activeKeys().forEach(owner::clear); folder.deleteRecursively() }
    }

    @Test fun productionShellOpenCallbackRejectsStaleVmAndPreviewActionsAfterFlagsClear() = runTest(main.testDispatcher) {
        for (withVm in listOf(false, true)) {
            val repo = Repository(); val vm = if (withVm) SessionsViewModel(repo) else null
            var shellGeneration = 0L; val captured = shellGeneration
            var transition = false; val opens = mutableListOf<String>()
            val allowed = { io.github.anshuman2401.hermesmobile.navigation.workspaceActionIsCurrent(captured, shellGeneration, !transition && vm?.uiState?.value?.isCreatingSession != true) }
            val callback = io.github.anshuman2401.hermesmobile.navigation.workspaceOpenCallback(allowed) { opens.add(it) }
            try {
                if (vm == null) callback("new-session") else vm.createNewChat(callback)
                assertEquals(listOf(if (vm == null) "new-session" else "new"), opens)
                transition = true; shellGeneration++; vm?.resetWorkspace()
                transition = false
                callback("stale-row")
                if (vm == null) callback("new-session") else vm.createNewChat(callback)
                assertEquals(1, opens.size)
            } finally { if (vm != null) close(vm) }
        }
    }

    @Test fun actualStreamingVetoIsRecheckedAfterAuthorityAwaitBeforeOwnerReset() = runTest(main.testDispatcher) {
        val repo = Repository(); val vm = SessionsViewModel(repo)
        val folder = java.nio.file.Files.createTempDirectory("synthetic-streaming").toFile()
        val vault = io.github.anshuman2401.hermesmobile.data.local.EncryptedVault(folder, io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine(io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        vault.saveSelectedContextProfile("A")
        val context = io.github.anshuman2401.hermesmobile.data.context.DefaultContextProfileRepository(vault)
        val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel { io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel(it) }
        owner.get("synthetic-old")
        var resets = 0
        val gated = object : io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository by context {
            override suspend fun readSelectedProfileAuthoritative(): io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult {
                val actual = context.readSelectedProfileAuthoritative()
                repo.activeTurnStreaming.value = ChatStreamingStatus("synthetic-old", true)
                runCurrent()
                return actual
            }
        }
        try {
            val result = io.github.anshuman2401.hermesmobile.navigation.performWorkspaceSelection("B", gated, owner, sessions = vm) { resets++; vm.resetWorkspace() }
            assertTrue(vm.uiState.value.activeTurnStreaming.isStreaming)
            assertTrue(result is io.github.anshuman2401.hermesmobile.data.context.ContextProfileSelectionResult.Error)
            assertEquals(0, resets); assertEquals(listOf("synthetic-old"), owner.activeKeys())
            assertEquals("A", vault.getSelectedContextProfile())
        } finally { close(vm); owner.activeKeys().forEach(owner::clear); folder.deleteRecursively() }
    }

    @Test fun allThreeProductionCreateActionsHaveCurrentSuccessFailureAndGuardedPreviewFallback() = runTest(main.testDispatcher) {
        for (route in listOf("sessions-fab", "expanded-detail", "chat-route")) for (outcome in listOf("success", "failure", "preview")) {
            var creates = 0
            val repo = Repository().apply { create = {
                creates++
                if (outcome == "failure") Result.failure(IllegalStateException("synthetic create failure"))
                else Result.success(SessionHandle("synthetic-created", "synthetic-runtime", isDraft = true))
            } }
            val vm = if (outcome == "preview") null else SessionsViewModel(repo)
            var generation = 0L; val captured = generation
            var transition = false; var enabled = false
            val opens = mutableListOf<String>()
            val allowed = { io.github.anshuman2401.hermesmobile.navigation.workspaceActionIsCurrent(captured, generation,
                enabled && !transition && vm?.uiState?.value?.isCreatingSession != true) }
            val open = io.github.anshuman2401.hermesmobile.navigation.workspaceOpenCallback(allowed) { opens.add(it) }
            val action = if (route == "sessions-fab") sessionsFabCreateAction(vm, allowed, false, null, open)
                else io.github.anshuman2401.hermesmobile.navigation.shellCreateAction(vm, allowed, open)
            try {
                if (route == "sessions-fab") {
                    sessionsFabCreateAction(vm, { true }, true, null) { opens.add(it) }()
                    sessionsFabCreateAction(vm, { true }, false, io.github.anshuman2401.hermesmobile.domain.SessionError.NetworkFailure("synthetic unavailable")) { opens.add(it) }()
                    runCurrent()
                    assertTrue(opens.isEmpty()); assertEquals(0, creates)
                }
                action(); assertTrue(opens.isEmpty()); assertEquals(0, creates)
                enabled = true; action(); runCurrent()
                assertFalse(vm?.uiState?.value?.isCreatingSession == true)
                if (outcome == "failure") {
                    assertTrue(opens.isEmpty()); assertNotNull(vm!!.uiState.value.newChatFailure)
                } else assertEquals(listOf(if (outcome == "preview") "new-session" else "synthetic-created"), opens)
                assertEquals(if (outcome == "preview") 0 else 1, creates)
                val before = opens.toList()
                transition = true; generation++; vm?.resetWorkspace(); transition = false
                action(); runCurrent()
                assertEquals(before, opens); assertEquals(if (outcome == "preview") 0 else 1, creates)
            } finally { if (vm != null) close(vm) }
        }
        // Sessions retains its create entry point; chat header entry points are removed.
        val root = if (java.io.File("app/src/main").exists()) "app/src/main" else "src/main"
        val shell = java.io.File("$root/java/io/github/anshuman2401/hermesmobile/navigation/HermesNavShell.kt").readText()
        assertFalse(shell.contains("onNavigateToNewChat"))
        assertFalse(shell.contains("val createSelectedChat"))
        val screen = java.io.File("$root/java/io/github/anshuman2401/hermesmobile/feature/sessions/SessionsScreen.kt").readText()
        assertEquals(1, Regex("onClick = sessionsFabCreateAction").findAll(screen).count())
    }

    @Test fun allThreeProductionCreateActionsRejectDeferredOldSuccessAfterFlagsClear() = runTest(main.testDispatcher) {
        for (route in listOf("sessions-fab", "expanded-detail", "chat-route")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var creates = 0
            val repo = Repository().apply { create = {
                creates++; entered.complete(Unit); release.await()
                Result.success(SessionHandle("synthetic-old-created", "synthetic-runtime", isDraft = true))
            } }
            val vm = SessionsViewModel(repo)
            var generation = 0L; val captured = generation; var transition = false
            val opens = mutableListOf<String>()
            val allowed = { io.github.anshuman2401.hermesmobile.navigation.workspaceActionIsCurrent(captured, generation,
                !transition && !vm.uiState.value.isCreatingSession) }
            val open = io.github.anshuman2401.hermesmobile.navigation.workspaceOpenCallback(allowed) { opens.add(it) }
            val action = if (route == "sessions-fab") sessionsFabCreateAction(vm, allowed, false, null, open)
                else io.github.anshuman2401.hermesmobile.navigation.shellCreateAction(vm, allowed, open)
            try {
                action(); entered.await(); assertTrue(vm.uiState.value.isCreatingSession)
                action(); assertEquals(1, creates)
                transition = true; generation++; vm.resetWorkspace(); transition = false
                release.complete(Unit); runCurrent()
                assertFalse(vm.uiState.value.isCreatingSession); assertFalse(transition)
                assertTrue(opens.isEmpty()); assertNull(vm.uiState.value.selectedSession)
                assertNull(vm.uiState.value.newChatFailure)
            } finally { release.complete(Unit); close(vm) }
        }
    }

}
