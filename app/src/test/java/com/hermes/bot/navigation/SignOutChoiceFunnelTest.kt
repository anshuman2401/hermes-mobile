package com.hermes.bot.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PR-6 TEST-06 source-contract guard, AMENDED 2026-09-23 by owner UI
 * decision: the Disconnect affordance is removed from ALL surfaces
 * (Connect card, Sessions strip, Settings). The choice-carrying sign-out
 * funnel (`SignOutCacheChoiceDialog` + `pendingSignOut`) has no remaining
 * in-app entry point, so the shell no longer hosts it. The ViewModel-level
 * disconnect pathway stays (used by auth-expiry teardown), covered by
 * ConnectViewModelSwitchTest.
 */
class SignOutChoiceFunnelTest {

    private fun moduleRoot(): File {
        var current: File? = File(System.getProperty("user.dir"))
        while (current != null) {
            if (File(current, "app/src/main/java").isDirectory) return current
            current = current.parentFile
        }
        error("module root not found from ${System.getProperty("user.dir")}")
    }

    private fun readSource(vararg segments: String): String =
        File(moduleRoot(), segments.joinToString(File.separator)).readText()

    private val shell: String = readSource(
        "app", "src", "main", "java", "com", "hermes", "bot", "navigation", "HermesNavShell.kt"
    )

    @Test
    fun shell_noLongerHostsSignOutFunnel() {
        // Owner UI decision 2026-09-23: no Disconnect entries remain, so the
        // shared dialog funnel is fully removed from the shell.
        assertEquals(
            "no SignOutCacheChoiceDialog render site may remain",
            0,
            Regex("""SignOutCacheChoiceDialog\(""").findAll(shell).count()
        )
        assertFalse(shell.contains("pendingSignOut"))
        assertFalse(shell.contains("onDisconnect"))
    }

    @Test
    fun screens_carryNoDisconnectAffordance() {
        // Every surface that used to offer Disconnect has dropped it.
        val screens = listOf(
            readSource("app", "src", "main", "java", "com", "hermes", "bot", "feature", "settings", "SettingsScreen.kt"),
            readSource("app", "src", "main", "java", "com", "hermes", "bot", "feature", "connect", "ConnectScreen.kt"),
            readSource("app", "src", "main", "java", "com", "hermes", "bot", "feature", "sessions", "SessionsScreen.kt")
        )
        for ((idx, src) in screens.withIndex()) {
            assertFalse(
                "screen $idx must contain no Disconnect button",
                src.contains("Text(\"Disconnect\")")
            )
            assertFalse(
                "screen $idx must contain no sign-out-choice machinery",
                src.contains("SignOutCacheChoice")
            )
        }
    }
}