package io.github.anshuman2401.hermesmobile.bootstrap

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.cache.VaultConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel
import io.github.anshuman2401.hermesmobile.feature.chat.SubmitBlockedReason
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import io.github.anshuman2401.hermesmobile.feature.chat.ChatStreamingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Plan §9 row (added): composition-root wiring for the offline conversation
 * snapshot cache — non-null repository, unchanged `createChatViewModel`
 * signature, and the default-null construction path behaving exactly as today.
 */
class ConversationCacheWiringTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun container(): DefaultAppContainer {
        val filesDir = tempFolder.newFolder("wiring-files")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = filesDir
            override fun getNoBackupFilesDir() = tempFolder.newFolder("wiring-no-backup")
        }
        return DefaultAppContainer(context)
    }

    @Test
    fun compositionRoot_handsTheContainerCacheRepository() {
        val c = container()
        val repository: ConversationCacheRepository = c.conversationCacheRepository
        assertNotNull("Container exposes a non-null cache repository", repository)
        assertTrue(repository is VaultConversationCacheRepository)
        // `by lazy`: stable identity across accesses.
        assertSame(repository, c.conversationCacheRepository)
    }

    @Test
    fun createChatViewModel_receivesTheContainerCache_publicSignatureUnchanged() {
        val c = container()
        // Same single-argument call the pre-PR-3 suite already uses — the
        // public signature must stay source-compatible.
        val viewModel = c.createChatViewModel("sess-100")

        val field = ChatViewModel::class.java.getDeclaredField("conversationCacheRepository")
        field.isAccessible = true
        assertSame(
            "Chat VM must receive the container's repository",
            c.conversationCacheRepository,
            field.get(viewModel)
        )
    }

    @Test
    fun defaultNullCachePath_behavesExactlyAsToday() = runTest {
        // No cache repository injected (default null): the offline gate can't
        // serve, so the pre-PR-3 failure behavior is untouched — NO_RUNTIME is
        // armed on a failed resume and no strip flag ever appears.
        val fakeRepo = object : SessionRepository {
            val activeSessionFlow = MutableStateFlow<SessionHandle?>(null)
            override val activeSession: StateFlow<SessionHandle?> = activeSessionFlow
            override val activeMessages: StateFlow<List<SessionMessage>> = MutableStateFlow(emptyList())
            override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())
            override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
                Result.success(SessionListOutcome(emptyList(), false))
            override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
                Result.success(SessionHandle("draft-1", "runtime-draft-1", isDraft = true))
            override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> =
                Result.failure(IllegalStateException("offline"))
            override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> =
                Result.failure(IllegalStateException("offline"))
            override suspend fun restoreSelectedSession(): SessionHandle? = null
            override suspend fun clearActiveSession() {}
            override suspend fun getActiveSession(): SessionHandle? = null
        }
        val adapter = io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter()
        adapter.connectionStatus.value = ConnectionStatus.OFFLINE

        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            savedStateHandle = SavedStateHandle()
        )
        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("No strip flag without a cache repository", state.savedSnapshotCapturedAtMillis)
        assertEquals(
            "Today's behavior: failed resume arms NO_RUNTIME",
            SubmitBlockedReason.NO_RUNTIME,
            state.submitBlockedReason
        )
    }
}
