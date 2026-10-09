package io.github.anshuman2401.hermesmobile.navigation

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BotsRouteWiringTest {
    private fun source(path: String): String = sequenceOf(File("."), File("app"), File(".."))
        .map { it.resolve("src/main/$path") }.first { it.exists() }.readText()
    @Test fun realBotsProviderIsCreatedOnlyInsideBotsBranchAndUsesSavedTarget() {
        val shell = source("java/io/github/anshuman2401/hermesmobile/navigation/HermesNavShell.kt")
        val branch = shell.substringAfter("BottomNavTab.BOTS -> {").substringBefore("ShellScreen.CHAT ->")
        assertTrue(branch.contains("remember(botsViewModelProvider)"))
        assertTrue(branch.contains("botsViewModelProvider?.invoke()"))
        assertTrue(branch.contains("profilesUiState.activeProfileId"))
        assertTrue(branch.contains("normalizedOrigin"))
        assertFalse(branch.contains("hostDraft"))
        assertFalse(branch.contains("createChat"))
        assertTrue(shell.contains(".padding(innerPadding)"))
        assertFalse(branch.contains("workspaceTrigger()"))
        assertTrue(branch.contains("onNavigateToSettings"))
        val bottom = source("java/io/github/anshuman2401/hermesmobile/navigation/BottomNav.kt")
        assertFalse(bottom.contains("fun BotsScreen("))
    }
    @Test fun channelIsInlineAndBothChatBackPathsReturnToBotRoot() {
        val shell = source("java/io/github/anshuman2401/hermesmobile/navigation/HermesNavShell.kt")
        val branch = shell.substringAfter("BottomNavTab.BOTS -> {").substringBefore("ShellScreen.BOT_CHANNEL ->")
        assertTrue(branch.contains("channelContent = { BotBoardChannelContent() }"))
        assertFalse(branch.contains("currentScreen = ShellScreen.BOT_CHANNEL"))
        assertTrue(shell.contains("embedded = true"))
        assertTrue(shell.contains("BackPressAction.NavigateToChannel -> { currentScreen = ShellScreen.SESSIONS; selectedTab = BottomNavTab.BOTS"))
        assertTrue(shell.contains("if (botChat) { selectedTab = BottomNavTab.BOTS; sessionsViewModel?.refreshSessions() }"))
        val screen = source("java/io/github/anshuman2401/hermesmobile/feature/bots/BotsScreen.kt")
        assertTrue(screen.contains("LazyRow")); assertTrue(screen.contains("ModalBottomSheet"))
        assertTrue(screen.contains("selected = active")); assertTrue(screen.contains("viewModel?.setFavourite("))
    }

    @Test fun nativeScreenUsesLifecycleAdmissionSelectableRowsAndFixedLayout() {
        val screen = source("java/io/github/anshuman2401/hermesmobile/feature/bots/BotsScreen.kt")
        assertTrue(screen.contains("collectAsStateWithLifecycle"))
        assertTrue(screen.contains("DisposableEffect(viewModel, localId, normalizedOrigin)"))
        assertTrue(screen.contains("onDispose { viewModel?.leave() }"))
        assertTrue(screen.contains("viewModel?.presentation("))
        assertTrue(screen.contains("LazyColumn"))
        assertTrue(screen.contains("size(40.dp)"))
        assertTrue(screen.contains("minHeight = 56.dp"))
        assertTrue(screen.contains("spacedBy(16.dp)"))
        assertTrue(screen.contains("maxLines = 1"))
        assertTrue(screen.contains("TextOverflow.Ellipsis"))
        assertTrue(screen.contains("clickable(enabled = enabled, onClick = onClick)"))
        assertTrue(screen.contains("onSelect(row)"))
        assertFalse(screen.contains("session.resume"))
        assertFalse(screen.contains("transport"))
        assertTrue(screen.contains("inJustDecodeBounds = true"))
        assertTrue(screen.contains("Dispatchers.IO"))
        assertTrue(screen.contains("160"))
        assertTrue(screen.contains("Screen status unknown"))
        assertTrue(screen.contains("Stop screen"))
        assertTrue(screen.contains("AlertDialog("))
        val strings = source("res/values/strings.xml")
        listOf("No bots found", "Could not load bots", "Connect to Hermes to see your bots", "Retry").forEach { assertTrue(strings.contains(it)) }
    }
}
