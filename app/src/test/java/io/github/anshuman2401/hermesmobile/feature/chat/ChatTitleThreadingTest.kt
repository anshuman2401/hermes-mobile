package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * CHG-005-S4 (TASK-007/008/009): chat header title precedence.
 *
 * Binding owner decision (ALT-003 rejected — no session.list re-fetch on
 * reopen): the title is threaded from the Sessions list summary the user
 * tapped. Precedence: server summary title > vault known title (CHG-003-S1
 * `lastKnownTitle` propagation via the repository's active handle) > a short
 * friendly label. A raw durable session ID is never displayed when any title
 * source exists, and no title is ever fabricated client-side.
 */
class ChatTitleThreadingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Fake repository that mirrors DefaultSessionRepository's CHG-003-S1
     * behavior: a successful resume installs the active handle carrying the
     * vault-known title (may be null), so loadSession() can read it via
     * getActiveSession().
     */
    private class TitleThreadingSessionRepository(
        private val resumeOutcome: Result<SessionResumeResult>,
        private val vaultTitle: String?
    ) : SessionRepository {
        private val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession
        override val activeMessages: StateFlow<List<SessionMessage>> =
            MutableStateFlow(emptyList())
        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> =
            MutableStateFlow(ChatStreamingStatus())

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft", "runtime-draft", title = title, isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> {
            if (resumeOutcome.isSuccess) {
                _activeSession.value = SessionHandle(
                    storedSessionId = storedSessionId,
                    runtimeSessionId = "runtime-100",
                    title = vaultTitle,
                    isDraft = false
                )
            }
            return resumeOutcome
        }

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> =
            Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-snap",
                    durableSessionId = durableSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )

        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = _activeSession.value
    }

    private fun resumeResult(durable: String = "sess-100") = SessionResumeResult(
        runtimeSessionId = "runtime-100",
        durableSessionId = durable,
        messageCount = 0,
        messages = emptyList()
    )

    // ------------------------------------------------------------------
    // Construction-time title (initialTitle over the raw-ID fallback)
    // ------------------------------------------------------------------

    @Test
    fun init_withInitialTitle_showsServerSummaryTitle_neverRawId() {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = null,
            initialTitle = "Design review"
        )

        val title = viewModel.uiState.value.sessionTitle
        assertEquals("Design review", title)
        assertFalse(title!!.contains("sess-100"))
    }

    @Test
    fun init_untitledDurableSession_showsFriendlyLabel_neverRawId() {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = null
        )

        val title = viewModel.uiState.value.sessionTitle
        assertEquals(UNTITLED_CONVERSATION_HEADER_LABEL, title)
        assertFalse(title!!.contains("sess-100"))
        assertFalse(title.contains("Session "))
    }

    @Test
    fun init_newSession_showsNewConversationLabel() {
        val viewModel = ChatViewModel(
            sessionId = "new-session",
            sessionRepository = null
        )

        assertEquals(NEW_CONVERSATION_HEADER_LABEL, viewModel.uiState.value.sessionTitle)
    }

    // ------------------------------------------------------------------
    // loadSession() precedence: server summary > vault known title
    // ------------------------------------------------------------------

    @Test
    fun loadSession_serverSummaryTitleWinsOverVaultKnownTitle() = runTest {
        val repo = TitleThreadingSessionRepository(
            resumeOutcome = Result.success(resumeResult()),
            vaultTitle = "Older vault title"
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = repo,
            initialTitle = "Tapped summary title"
        )

        assertEquals("Tapped summary title", viewModel.uiState.value.sessionTitle)
        assertFalse(viewModel.uiState.value.sessionTitle!!.contains("sess-100"))
    }

    @Test
    fun loadSession_fallsBackToVaultKnownTitle_whenNoServerSummaryTitle() = runTest {
        val repo = TitleThreadingSessionRepository(
            resumeOutcome = Result.success(resumeResult()),
            vaultTitle = "Vault known title"
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = repo,
            initialTitle = null
        )

        assertEquals("Vault known title", viewModel.uiState.value.sessionTitle)
        assertFalse(viewModel.uiState.value.sessionTitle!!.contains("sess-100"))
    }

    @Test
    fun loadSession_failure_keepsFriendlyFallback_neverRawId() = runTest {
        val repo = TitleThreadingSessionRepository(
            resumeOutcome = Result.failure(IllegalStateException("resume failed")),
            vaultTitle = null
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = repo,
            initialTitle = null
        )

        val title = viewModel.uiState.value.sessionTitle
        assertEquals(UNTITLED_CONVERSATION_HEADER_LABEL, title)
        assertFalse(title!!.contains("sess-100"))
    }

    @Test
    fun loadSession_noTitleSourceAnywhere_friendlyLabel_neverRawId() = runTest {
        val repo = TitleThreadingSessionRepository(
            resumeOutcome = Result.success(resumeResult()),
            vaultTitle = null
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = repo,
            initialTitle = null
        )

        val title = viewModel.uiState.value.sessionTitle
        assertEquals(UNTITLED_CONVERSATION_HEADER_LABEL, title)
        assertFalse(title!!.contains("sess-100"))
    }

    // ------------------------------------------------------------------
    // Shell threading onto an existing keyed ViewModel (reopen path)
    // ------------------------------------------------------------------

    @Test
    fun applyInitialTitle_threadsSummaryTitleIntoExistingViewModel() {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = null
        )
        assertEquals(UNTITLED_CONVERSATION_HEADER_LABEL, viewModel.uiState.value.sessionTitle)

        viewModel.applyInitialTitle("Reopened title")

        assertEquals("Reopened title", viewModel.uiState.value.sessionTitle)
    }

    @Test
    fun applyInitialTitle_blankTitleIsIgnored_neverRawId() {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = null,
            initialTitle = "Real title"
        )

        viewModel.applyInitialTitle("   ")

        assertEquals("Real title", viewModel.uiState.value.sessionTitle)
    }

    @Test
    fun applyInitialTitle_blankSummaryNeverFabricatesRawIdFallback() {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = null
        )

        viewModel.applyInitialTitle("")

        val title = viewModel.uiState.value.sessionTitle!!
        assertEquals(UNTITLED_CONVERSATION_HEADER_LABEL, title)
        assertFalse(title.contains("sess-100"))
    }

    // ------------------------------------------------------------------
    // POLISH (S4 dual-pane re-tap guard): re-tapping the same session in
    // expanded dual-pane re-threads a possibly stale summary title; it must
    // never overwrite a live server title the keyed VM already holds.
    // ------------------------------------------------------------------

    @Test
    fun applyInitialTitle_staleRetapSummaryDoesNotOverwriteLiveServerTitle() {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = null
        )
        // The keyed VM already received a live server title (e.g. a
        // GatewayEvent.SessionTitle after a host rename); any nonblank,
        // non-placeholder title in state counts as live.
        viewModel.applyInitialTitle("Host-renamed live title")
        assertEquals("Host-renamed live title", viewModel.uiState.value.sessionTitle)

        // Re-tap: the shell re-threads the summary title captured earlier,
        // which is now stale. It must not clobber the live title.
        viewModel.applyInitialTitle("Stale summary title")

        assertEquals("Host-renamed live title", viewModel.uiState.value.sessionTitle)
    }

    @Test
    fun applyInitialTitle_fillsPlaceholderAfterLiveTitleIsAbsent() {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = null
        )
        assertEquals(UNTITLED_CONVERSATION_HEADER_LABEL, viewModel.uiState.value.sessionTitle)

        // No live title yet: the threaded summary still fills the placeholder.
        viewModel.applyInitialTitle("Fresh summary title")

        assertEquals("Fresh summary title", viewModel.uiState.value.sessionTitle)
    }

    // ------------------------------------------------------------------
    // ChatScreen second-fallback fix (skeptic fix-up): the screen-level
    // fallback must also never render the raw session ID.
    // ------------------------------------------------------------------

    @Test
    fun chatHeaderFallbackTitle_durableSessionIsFriendly_neverRawId() {
        val title = chatHeaderFallbackTitle("sess-abc123")

        assertEquals(UNTITLED_CONVERSATION_HEADER_LABEL, title)
        assertFalse(title.contains("sess-abc123"))
    }

    @Test
    fun chatHeaderFallbackTitle_newSessionIsNewConversation() {
        assertEquals(NEW_CONVERSATION_HEADER_LABEL, chatHeaderFallbackTitle("new-session"))
    }
}
