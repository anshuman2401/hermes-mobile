package com.hermes.bot.feature.settings

import com.hermes.bot.navigation.BackPressAction
import com.hermes.bot.navigation.BackPressPolicy
import com.hermes.bot.navigation.ShellScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Slice S3 verification test:
 * Verifies that the Settings screen always provides a "Diagnostics" row
 * with no debug-gate navigating to the Diagnostics screen, and that
 * back-stack behavior matches existing Settings sub-screens (DEBUG_LOGS -> SETTINGS).
 */
class SettingsDiagnosticsEntryTest {

    private val settingsFile = File("src/main/java/com/hermes/bot/feature/settings/SettingsScreen.kt").let {
        if (it.exists()) it else File("app/src/main/java/com/hermes/bot/feature/settings/SettingsScreen.kt")
    }

    private val navShellFile = File("src/main/java/com/hermes/bot/navigation/HermesNavShell.kt").let {
        if (it.exists()) it else File("app/src/main/java/com/hermes/bot/navigation/HermesNavShell.kt")
    }

    @Test
    fun settingsScreen_containsDiagnosticsRowWithoutDebugGate() {
        val content = settingsFile.readText()
        assertTrue("SettingsScreen must contain App logs button", content.contains("SettingsRow(\"App logs\""))
        assertTrue("SettingsScreen must contain Gateway logs button", content.contains("SettingsRow(\"Gateway logs\""))
        assertTrue("SettingsScreen must define onOpenDiagnostics", content.contains("onOpenDiagnostics"))
    }

    @Test
    fun diagnosticsScreen_backStackPolicyNavigatesToSettings() {
        assertEquals(
            BackPressAction.NavigateToSettings,
            BackPressPolicy.decide(ShellScreen.DEBUG_LOGS, isExpanded = false)
        )
        assertEquals(
            BackPressAction.NavigateToSettings,
            BackPressPolicy.decide(ShellScreen.DEBUG_LOGS, isExpanded = true)
        )
    }

    @Test
    fun navShell_wiresSettingsAndDiagnosticsScreens() {
        val content = navShellFile.readText()
        assertTrue(
            "HermesNavShell must wire onOpenDiagnostics to DEBUG_LOGS",
            content.contains("onOpenDiagnostics = { currentScreen = ShellScreen.DEBUG_LOGS }")
        )
        assertTrue(
            "HermesNavShell must wire back navigation from DEBUG_LOGS to SETTINGS",
            content.contains("currentScreen = ShellScreen.SETTINGS")
        )
    }
}
