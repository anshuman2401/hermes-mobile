package io.github.anshuman2401.hermesmobile.feature.sessions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-contract test for the consumer row's count metadata and state boundary. */
class SessionsRowMetaSourceContractTest {

    private fun locate(fileName: String): File =
        File("src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/$fileName").let {
            if (it.exists()) it
            else File("app/src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/$fileName")
        }

    private val screenSource: String by lazy {
        val f = locate("SessionsScreen.kt")
        assertTrue("SessionsScreen.kt must exist", f.exists())
        f.readText()
    }

    private val uiStateSource: String by lazy {
        val f = locate("SessionsUiState.kt")
        assertTrue("SessionsUiState.kt must exist", f.exists())
        f.readText()
    }

    @Test
    fun sessionsScreen_derivesMessageCountFromPolicy_withoutModelDuplication() {
        assertTrue(
            "SessionsScreen.kt must derive message count label via SessionsRowMetaPolicy.messageLabel(",
            screenSource.contains("SessionsRowMetaPolicy.messageLabel(")
        )
        assertTrue(screenSource.contains("OutlineChatBubbleGraphic("))
        assertFalse(
            "model/workspace metadata must not be repeated in consumer rows",
            screenSource.contains("SessionsRowMetaPolicy.modelLabel(") ||
                screenSource.contains("val activeModels")
        )
    }

    @Test
    fun sessionsUiState_declaresActiveModels() {
        assertTrue(
            "SessionsUiState.kt must declare activeModels",
            uiStateSource.contains("val activeModels: Map<String, String>")
        )
    }

    @Test
    fun sessionsScreen_rowMetaIntroducesNoNewGatewayCalls() {
        assertFalse(
            "SessionsScreen.kt must not call listSessions( directly",
            screenSource.contains("listSessions(")
        )
        assertFalse(
            "SessionsScreen.kt must not call resumeSession( directly",
            screenSource.contains("resumeSession(")
        )
        assertFalse(
            "SessionsScreen.kt must not call createDraftSession( directly",
            screenSource.contains("createDraftSession(")
        )
    }

    @Test
    fun sessionsScreen_rowMetaUsesCountSemantics_withoutModelSemantics() {
        assertTrue(
            "SessionsScreen.kt must set contentDescription for message count label",
            screenSource.contains("contentDescription = messageCountLabel")
        )
        assertFalse(screenSource.contains("contentDescription = modelLabel"))
    }

    @Test
    fun sessionsScreen_preservesExistingRowElements() {
        assertTrue(
            "SessionsScreen.kt must preserve the guarded relative activity label",
            screenSource.contains("safeRelativeActivityLabel(")
        )
        assertTrue(
            "SessionsScreen.kt must guard rendering of an empty relative activity label",
            screenSource.contains("if (label.isNotEmpty() && !largeText)") &&
                screenSource.contains("if (label.isNotEmpty() && largeText)")
        )
        assertTrue(
            "SessionsScreen.kt must preserve Running mark check",
            screenSource.contains("activeTurnStreaming")
        )
        assertTrue(
            "Minimal rows omit message previews",
            !screenSource.contains("session.preview.orEmpty()")
        )
    }
}
