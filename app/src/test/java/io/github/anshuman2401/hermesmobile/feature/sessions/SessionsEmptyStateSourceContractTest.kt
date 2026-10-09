package io.github.anshuman2401.hermesmobile.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOME-S10 source-contract guard (frozen plan v1, §6 TEST-005..TEST-007).
 *
 * Freezes the designed empty-state decisions (FZ-2..FZ-5): the bare-text
 * empty state is removed, the screen renders the policy strings, and the CTA
 * reuses the byte-identical `createNewChat` path with zero new gateway calls.
 *
 * Same dual-root source-locating pattern as
 * `SessionsNewChatProminenceSourceContractTest`. Source-text assertions
 * supplement, never replace, behavior tests.
 */
class SessionsEmptyStateSourceContractTest {

    private val sourceFile = File("src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/SessionsScreen.kt").let {
        if (it.exists()) it else File("app/src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/SessionsScreen.kt")
    }

    private val source: String by lazy {
        assertTrue("SessionsScreen.kt must exist", sourceFile.exists())
        sourceFile.readText()
    }

    @Test
    fun sessionsScreen_bareTextEmptyStateRemoved() {
        // TEST-005 / INV-1: the old bare one-line empty state is gone.
        assertFalse(
            "SessionsScreen.kt must no longer contain the bare-text 'No active conversations' empty state",
            source.contains("No active conversations")
        )
        assertFalse(
            "SessionsScreen.kt must no longer contain \"Tap 'New chat' to start\"",
            source.contains("Tap 'New chat' to start")
        )
    }

    @Test
    fun sessionsScreen_emptyStateUsesPolicyStrings() {
        // TEST-006 / INV-2, INV-3: the empty state renders policy strings only.
        assertTrue(
            "SessionsScreen.kt must gate the empty state on SessionsEmptyStatePolicy.isEmptyStateVisible(",
            source.contains("SessionsEmptyStatePolicy.isEmptyStateVisible(")
        )
        assertTrue(
            "SessionsScreen.kt must render SessionsEmptyStatePolicy.EMPTY_TITLE",
            source.contains("SessionsEmptyStatePolicy.EMPTY_TITLE")
        )
        assertTrue(
            "SessionsScreen.kt must render SessionsEmptyStatePolicy.EMPTY_CTA",
            source.contains("SessionsEmptyStatePolicy.EMPTY_CTA")
        )
        assertFalse(
            "SessionsScreen.kt must not hardcode the empty-state title literal",
            source.contains("Text(\"No saved conversations yet.")
        )
    }

    @Test
    fun sessionsScreen_emptyStateCtaReusesCreateNewChat_noNewGatewayCalls() {
        // Both explicit entrypoints retain create and captured-intent navigation.
        val createNewChat = "viewModel.createNewChat { sessionId ->"
        assertEquals(
            "SessionsScreen.kt must contain exactly 2 createNewChat invocations (FAB + empty-state CTA)",
            2,
            Regex(Regex.escape(createNewChat)).findAll(source).count()
        )
        assertEquals(2, Regex(Regex.escape("val intent = viewModel?.captureWorkspaceIntent()")).findAll(source).count())
        assertEquals(2, Regex(Regex.escape("viewModel.isWorkspaceIntentCurrent(intent)) onOpenChat(sessionId)")).findAll(source).count())
        assertEquals(2, Regex(Regex.escape("workspaceActionsAllowed() && !isLoading && currentError == null")).findAll(source).count())
        assertEquals(2, Regex(Regex.escape("else if (workspaceActionsAllowed()) onOpenChat(\"new-session\")")).findAll(source).count())
        assertEquals(
            "SessionsScreen.kt must keep exactly one fixed primary action",
            1,
            Regex(Regex.escape("FilledIconButton(")).findAll(source).count()
        )
        assertFalse(
            "SessionsScreen.kt must not call listSessions(",
            source.contains("listSessions(")
        )
        assertFalse(
            "SessionsScreen.kt must not call resumeSession(",
            source.contains("resumeSession(")
        )
        assertFalse(
            "SessionsScreen.kt must not call createDraftSession(",
            source.contains("createDraftSession(")
        )
    }
}
