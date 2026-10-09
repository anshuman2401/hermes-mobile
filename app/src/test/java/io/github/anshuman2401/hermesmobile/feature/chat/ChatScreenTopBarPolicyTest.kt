package io.github.anshuman2401.hermesmobile.feature.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenTopBarPolicyTest {
    @Test
    fun actionsOrder_containsOnlyRefresh() {
        assertEquals(emptyList<String>(), ChatScreenTopBarPolicy.actionsOrder())
        assertEquals(listOf("Refresh chat"), ChatScreenTopBarPolicy.actionsOrder(hasRefresh = true))
    }

    @Test
    fun chatScreen_hasNoNewChatOrProfileSwitcher_andKeepsRemainingActions() {
        val relative = "src/main/java/io/github/anshuman2401/hermesmobile/feature/chat/ChatScreen.kt"
        val source = File(relative).let { if (it.exists()) it else File("app/$relative") }.readText()
        assertFalse(source.contains("onNavigateToNewChat"))
        assertFalse(source.contains("profileSwitcher"))
        assertFalse(source.contains("Icons.Filled.Add"))
        assertFalse(source.contains("isCreatingSession"))
        assertTrue(source.contains("if (uiState?.slashMenuVisible == true) viewModel?.dismissSlashSuggestions() else onNavigateBack()"))
        assertTrue(source.contains("onClick = { viewModel?.refreshSession() }"))
        assertFalse(source.contains("onClick = onNavigateToSettings"))
        assertFalse(source.contains("Icons.Default.MoreVert"))
        assertTrue(source.contains("onOpenContextUsage = if (sessionId != \"new-session\" && viewModel != null)"))
        assertTrue(source.contains("viewModel.openContextUsage()"))
        assertTrue(source.contains("ContextUsageBottomSheet(uiState"))
    }

    @Test fun headerTitleAndStatusRemainTruthful() {
        assertEquals("Synthetic title", ChatScreenTopBarPolicy.title("Synthetic title", false))
        assertEquals(UNTITLED_CONVERSATION_HEADER_LABEL, ChatScreenTopBarPolicy.title(" ", false))
        assertEquals(NEW_CONVERSATION_HEADER_LABEL, ChatScreenTopBarPolicy.title(null, true))
        assertEquals("Offline", ChatScreenTopBarPolicy.connectionLabel(null))
        val labels = mapOf(
            io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.CONNECTED to "Connected",
            io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.CONNECTING to "Connecting…",
            io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.OFFLINE to "Offline",
            io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.AUTHENTICATION_REQUIRED to "Sign in required",
            io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.FAILED to "Disconnected"
        )
        labels.forEach { (status, label) ->
            val state = ChatUiState(headerConnectionStatus = status)
            assertEquals(label, ChatScreenTopBarPolicy.connectionLabel(state))
            assertEquals(status == io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.CONNECTED, ChatScreenTopBarPolicy.isConnected(state))
        }
        val snapshot = ChatUiState(headerConnectionStatus = io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.CONNECTED,
            savedSnapshotCapturedAtMillis = 1L)
        assertEquals("Saved snapshot · Offline", ChatScreenTopBarPolicy.connectionLabel(snapshot))
        assertFalse(ChatScreenTopBarPolicy.isConnected(snapshot))
    }

    @Test
    fun shell_hasNoChatCreationCallback_andKeepsSessionsWorkspaceTrigger() {
        val relative = "src/main/java/io/github/anshuman2401/hermesmobile/navigation/HermesNavShell.kt"
        val source = File(relative).let { if (it.exists()) it else File("app/$relative") }.readText()
        assertFalse(source.contains("onNavigateToNewChat"))
        assertTrue(source.contains("profileSwitcher = workspaceTrigger"))
    }

}
