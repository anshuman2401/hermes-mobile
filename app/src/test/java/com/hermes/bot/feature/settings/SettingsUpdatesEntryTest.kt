package com.hermes.bot.feature.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring supplement; state and one-shot behavior are exercised by HostUpdateViewModelTest. */
class SettingsUpdatesEntryTest {
    private fun source(path: String) = sequenceOf(File("."), File("app"))
        .map { it.resolve("src/main/java/com/hermes/bot/$path") }.first { it.exists() }.readText()

    @Test fun updatesEntryIsAfterHealthAndOpensPageLocalRoute() {
        val screen = source("feature/settings/SettingsScreen.kt")
        val group = screen.substringAfter("SettingsGroupLabel(\"Your host\")").substringBefore("SettingsGroupLabel(\"On this device\")")
        assertTrue(group.indexOf("SettingsRow(\"Health\"") < group.indexOf("SettingsRow(\"Updates\""))
        assertTrue(group.indexOf("SettingsRow(\"Updates\"") < group.indexOf("SettingsRow(\"Analytics\""))
        assertTrue(group.contains("onClick = onOpenUpdates"))
        val shell = source("navigation/HermesNavShell.kt")
        assertTrue(shell.contains("HostUpdateRoute("))
        assertTrue(shell.contains("onOpenUpdates = { updatesOpen = true }"))
        val route = source("feature/settings/HostUpdateScreen.kt")
        assertTrue(route.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"))
        assertTrue(route.contains("onDispose { store.clear() }"))
    }
}
