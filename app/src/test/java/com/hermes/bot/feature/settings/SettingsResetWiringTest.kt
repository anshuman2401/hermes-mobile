package com.hermes.bot.feature.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan CACHE-PR5 TEST-09 — wiring evidence on the JVM (no Compose/robolectric):
 * both Settings rows arm a pending choice, each confirm invokes exactly ONE
 * root callback, the shell forwards both callbacks (with the CONNECT gate for
 * the full reset), MainActivity performs teardown-before-wipe in that order,
 * and the dead single-button copy is gone.
 */
class SettingsResetWiringTest {

    private fun source(relPath: String): String {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (candidate in listOf(File(dir, relPath), File(dir, "app/$relPath"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("source not found: $relPath")
    }

    private val settings = source("src/main/java/com/hermes/bot/feature/settings/SettingsScreen.kt")
    private val shell = source("src/main/java/com/hermes/bot/navigation/HermesNavShell.kt")
    private val main = source("src/main/java/com/hermes/bot/bootstrap/MainActivity.kt")
    private val container = source("src/main/java/com/hermes/bot/bootstrap/AppContainer.kt")

    @Test
    fun bothRowsArmOneChoice_eachConfirmInvokesExactlyOneRootCallback() {
        // Two explicit rows (FILE-07 labels), each arming only the choice state.
        assertTrue(settings.contains("Text(\"Clear offline cache\")"))
        assertTrue(settings.contains("Text(\"Clear all local app data\")"))
        assertTrue(settings.contains("presentation.requestReset(SettingsResetChoice.CLEAR_OFFLINE_CACHE)"))
        assertTrue(settings.contains("presentation.requestReset(SettingsResetChoice.CLEAR_ALL_LOCAL_DATA)"))
        // Confirmation copy comes from the policy (REQ-01).
        assertTrue(settings.contains("SettingsResetPolicy.copyFor(pendingReset)"))
        // Exactly one root callback per confirm, exhaustive per choice.
        assertTrue(settings.contains("SettingsResetChoice.CLEAR_OFFLINE_CACHE -> onClearOfflineCache()"))
        assertTrue(settings.contains("SettingsResetChoice.CLEAR_ALL_LOCAL_DATA -> onClearLocalData()"))
        // Dismiss/back returns to IDLE with no callback.
        assertTrue(settings.contains("onDismissRequest = { presentation = presentation.dismiss() }"))
        assertTrue(settings.contains("onClick = { presentation = presentation.dismiss() }"))
        // Dead single-button copy is gone.
        assertTrue("!dead label".let { !settings.contains("Reset local cache & credentials") })
        assertTrue(!settings.contains("Clear local session cache, drafts"))
    }

    @Test
    fun shellForwardsBothCallbacks_fullResetGatesBackToConnect() {
        assertTrue(shell.contains("onClearOfflineCache = onClearOfflineCache"))
        val start = shell.indexOf("onClearLocalData = {")
        assertTrue("shell receives onClearLocalData", start > 0)
        val block = shell.substring(start, minOf(start + 240, shell.length))
        assertTrue(block.contains("onClearLocalData()"))
        assertTrue(
            "full reset gates the shell back to CONNECT (single root, plan REQ-04)",
            block.contains("currentScreen = ShellScreen.CONNECT")
        )
        // The pre-existing back navigation is untouched.
        assertTrue(shell.contains("onNavigateBack = { currentScreen = previousScreen }"))
    }

    @Test
    fun mainActivity_createsTheSettingsViewModel_andRunsTeardownBeforeWipe() {
        assertTrue(main.contains("settingsViewModel = remember(container) { container?.createSettingsViewModel() }"))
        assertTrue(main.contains("onClearOfflineCache = { settingsViewModel?.clearOfflineCache() }"))
        val start = main.indexOf("onClearLocalData = {")
        assertTrue("MainActivity receives onClearLocalData", start > 0)
        val block = main.substring(start, minOf(start + 300, main.length))
        val disconnect = block.indexOf("effectiveConnectVm.disconnect()")
        val wipe = block.indexOf("settingsViewModel?.clearAllLocalData()")
        assertTrue("teardown present", disconnect > 0)
        assertTrue("wipe present", wipe > 0)
        assertTrue(
            "REQ-07: local sign-out teardown BEFORE the full wipe",
            disconnect < wipe
        )
        // Composition-root seam exists (FGU-004).
        assertTrue(container.contains("fun createSettingsViewModel()"))
        assertTrue(container.contains("DefaultLocalDataResetCoordinator(vault = vaultStorage, clearMemory = { botMemory.clear() })"))
    }
}
