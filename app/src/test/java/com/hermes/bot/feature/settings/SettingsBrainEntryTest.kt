package com.hermes.bot.feature.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring supplement; overlay behavior is exercised by SettingsPresentationStateTest. */
class SettingsBrainEntryTest {
    private fun source(): String = sequenceOf(File("."), File("app"))
        .map { it.resolve("src/main/java/com/hermes/bot/feature/settings/SettingsScreen.kt") }
        .first { it.exists() }.readText()

    @Test fun brainIsFirstInTheHostGroupAndUsesTheExistingCallback() {
        val screen = source()
        val host = screen.substringAfter("SettingsGroupLabel(\"Your host\")")
            .substringBefore("SettingsGroupLabel(\"On this device\")")
        val entries = listOf("Brain", "Health", "Analytics", "Gateway logs")
        val positions = entries.map { host.indexOf("SettingsRow(\"$it\"") }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertTrue(host.contains("onClick = onOpenBrain"))
        assertTrue(host.contains("Models, auxiliary models & skills"))
        assertTrue(screen.contains("defaultMinSize(minHeight = 68.dp)"))
        assertTrue(screen.contains("Surface(onClick = onClick"))
    }

    @Test fun existingSettingsActionsRemainReachable() {
        val screen = source()
        listOf("Appearance", "Connections", "Data & privacy", "App logs", "Add connection", "Clear offline cache", "Clear all local app data").forEach {
            assertTrue(screen.contains("\"$it\""))
        }
        listOf("onThemeChanged(pref)", "onSwitchProfile(profile.localId)", "onRemoveProfile", "onAddConnection", "onClearOfflineCache()", "onClearLocalData()", "onClick = onOpenDiagnostics", "onClick = onNavigateBack", "profileSwitcher()").forEach {
            assertTrue(screen.contains(it))
        }
    }
}
