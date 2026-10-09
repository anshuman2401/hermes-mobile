package io.github.anshuman2401.hermesmobile.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Presentation guard; creation behavior remains covered by SessionsViewModel tests. */
class SessionsNewChatProminenceSourceContractTest {

    private val sourceFile = File("src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/SessionsScreen.kt").let {
        if (it.exists()) it else File("app/src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/SessionsScreen.kt")
    }

    private val source: String by lazy {
        assertTrue("SessionsScreen.kt must exist", sourceFile.exists())
        sourceFile.readText()
    }

    @Test
    fun sessionsScreen_newChatIsCompactOverlayAction() {
        assertTrue(source.contains("// Fixed entry point above the shell-owned bottom bar."))
        assertTrue(source.contains("FilledIconButton("))
        assertTrue(source.contains("Modifier.align(Alignment.BottomEnd)"))
        assertTrue(source.contains("Modifier.size(56.dp).semantics"))
        assertTrue(source.contains("contentPadding = PaddingValues(bottom = 80.dp)"))
        assertFalse(source.contains("floatingActionButton = {"))
        assertFalse(source.contains("FloatingActionButton("))
        assertTrue(source.contains("if (isCreatingSession) CircularProgressIndicator"))
    }

    @Test
    fun sessionsScreen_newChatUsesCreateNewChatPathExactlyAsBefore() {
        assertTrue("User action still creates through the ViewModel", source.contains("viewModel.createNewChat { sessionId ->"))
        assertTrue("Deferred navigation retains captured intent", source.contains("val intent = viewModel?.captureWorkspaceIntent()"))
        assertTrue(source.contains("viewModel.isWorkspaceIntentCurrent(intent)) onOpenChat(sessionId)"))
        assertTrue("Workspace/loading/error gates precede create", source.contains("workspaceActionsAllowed() && !isLoading && currentError == null"))
        assertTrue("Explicit no-VM fallback remains guarded", source.contains("else if (workspaceActionsAllowed()) onOpenChat(\"new-session\")"))
    }

    @Test
    fun sessionsScreen_exactlyOneNewChatAffordance_inlineButtonRemoved() {
        // The sole FAB has an accessible label and no visible text label.
        val occurrences = Regex.escape("Text(\"New chat\")").toRegex().findAll(source).count()
        assertEquals(
            "SessionsScreen.kt has no visible New chat text",
            0,
            occurrences
        )
        assertTrue("new-chat FAB must retain its accessible label", source.contains("contentDescription = \"New chat\""))
        // The removed full-width inline Button block must be gone.
        assertFalse(
            "SessionsScreen.kt must no longer contain the '// New Chat CTA' inline block",
            source.contains("// New Chat CTA")
        )
    }
}
