package io.github.anshuman2401.hermesmobile.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level complement to the pure gate matrix; physical Compose behavior remains unverified. */
class SessionsRunningRowSourceContractTest {
    private val source by lazy {
        val local = File("src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/SessionsScreen.kt")
        (if (local.exists()) local else File("app/src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/SessionsScreen.kt")).readText()
    }

    @Test fun keyedFilteredRowsOwnSingleGateAndConditionalPulse() {
        assertEquals(1, Regex("\\bitems\\(group\\.sessions, key = \\{ it.id \\}\\)").findAll(source).count())
        assertEquals(1, Regex("SessionsRunningRowPolicy\\.showRunning\\(").findAll(source).count())
        assertTrue(source.contains("hasNetworkFailure = currentError is SessionError.NetworkFailure"))
        assertTrue(source.contains("isOfflineCache = isOfflineCache"))
        assertTrue(source.contains("hasViewModel = viewModel != null"))
        assertTrue(source.contains("if (showRunningMark) {"))
        val conditional = source.substringAfter("if (showRunningMark) {").substringBefore("val messageCountLabel")
        assertFalse(conditional.contains("rememberInfiniteTransition"))
        assertTrue(conditional.contains(".background(runningGreen))"))
        assertTrue(conditional.contains("color = runningGreen"))
        assertTrue(source.contains("AtelierGroupRow(first, last,"))
        assertTrue(conditional.contains("text = \"Running\""))
        assertTrue(conditional.contains("contentDescription = \"Running: this conversation is streaming a response\""))
        assertTrue(source.contains("if (showRunningMark) \"Session: ${'$'}displayedTitle. Running: this conversation is streaming a response\""))
        assertTrue(source.contains("SessionsRowPresentationPolicy.displayedTitle("))
        assertFalse(source.contains("uiState?.activeTurnStreaming?.storedSessionId == session.id"))
    }

    @Test fun searchAndRowTapRemainScopedToFilteredList() {
        assertTrue(source.contains("SessionsSearchPolicy.filterSessions(sessions, searchQuery)"))
        assertTrue(source.contains("SessionsPinnedPolicy.groups(filteredSessions, uiState?.pinnedSessionIds.orEmpty(), pinnedOnly, now, zone)"))
        assertTrue(source.contains("if (sessionOpenAllowed()) onOpenChat(session.id)"))
        assertEquals(1, Regex("onOpenChat\\(session\\.id\\)").findAll(source).count())
        assertFalse(source.contains("selectAndResumeSession("))
        assertFalse(source.contains("resumeCandidate"))
    }
}
