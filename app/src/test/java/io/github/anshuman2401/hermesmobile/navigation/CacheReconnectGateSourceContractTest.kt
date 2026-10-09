package io.github.anshuman2401.hermesmobile.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PR-7 (plan TEST-07) — source contract for the shell wiring the plan pins:
 * the reconnect effect keys the two sessions flags (and reads them as
 * composition-observed StateFlow values, closing the read-once-per-key-change
 * gap), the Workspaces sheet reload decision belongs to
 * [CacheReconnectGatePolicy], the sheet read is ONE remembered
 * getProfiles-only lambda gated by retainOnError, and the sessions flag
 * label sources stay flag-only (no connection state).
 */
class CacheReconnectGateSourceContractTest {

    private fun source(path: String): String = File(path).readText()

    private val navShell: String
        get() = source("src/main/java/io/github/anshuman2401/hermesmobile/navigation/HermesNavShell.kt")

    private val sessionsScreen: String
        get() = source("src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/SessionsScreen.kt")

    private val offlineSection: String
        get() = source("src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/SessionsOfflineSectionPolicy.kt")

    @Test
    fun reconnectEffect_keysEveryReadValueIncludingBothSessionsFlags() {
        assertTrue(
            "the reconnect effect must key isOfflineCache/hasLocalCacheError (stale-key fix)",
            navShell.contains(
                "LaunchedEffect(connectUiState.state, currentScreen, isSwitchPending, isOfflineCache, hasLocalCacheError)"
            )
        )
    }

    @Test
    fun sessionsFlags_areCompositionObservedStateFlowReads() {
        assertTrue(
            navShell.contains("val sessionsUiState by sessionsStateFlow.collectAsState()")
        )
        assertTrue(navShell.contains("val isOfflineCache = sessionsUiState.isOfflineCache"))
        assertTrue(
            navShell.contains("val hasLocalCacheError = sessionsUiState.error is SessionError.LocalCacheFailure")
        )
        assertFalse(
            "the read-once-per-key-change capture must be gone",
            navShell.contains("sessionsViewModel?.uiState?.value?.isOfflineCache")
        )
    }

    @Test
    fun sheetReconnectReload_decisionBelongsToTheGatePolicy() {
        val gateIdx = navShell.indexOf("CacheReconnectGatePolicy.shouldReloadSheet(")
        assertTrue("shell delegates the reload decision to CacheReconnectGatePolicy", gateIdx >= 0)
        val openCall = navShell.indexOf("loadWorkspaceSheet(false)")
        val gatedCall = navShell.indexOf("loadWorkspaceSheet(true)")
        assertTrue("the open-path read exists", openCall >= 0)
        assertTrue("the gated CACHED reload exists", gatedCall >= 0)
        assertTrue("open path precedes the gated reload", openCall < gateIdx)
        assertTrue("gated reload runs only from inside the gate decision", gatedCall > gateIdx)
    }

    @Test
    fun sheetRead_isOneRememberedGetProfilesOnlyRetainLambda() {
        assertTrue(navShell.contains("val loadWorkspaceSheet: suspend (Boolean) -> Unit"))
        val start = navShell.indexOf("val loadWorkspaceSheet")
        val end = navShell.indexOf("val handleSwitchProfile")
        assertTrue("sheet region is bounded in HermesNavShell.kt", start in 0 until end)
        val region = navShell.substring(start, end)
        assertTrue("shared lambda carries the retainOnError flag", region.contains("retainOnError"))
        assertTrue("shared lambda performs the roster read", region.contains("getProfiles()"))
        assertFalse("GET-only reload: no activation call in the sheet region", region.contains("selectProfile"))
        assertFalse("GET-only reload: no write verb in the sheet region", region.contains("POST"))
    }

    @Test
    fun sessionsCacheFlagRemainsRepositoryOwnedAlongsideStartupConnectionNotice() {
        assertTrue(sessionsScreen.contains("val isOfflineCache = uiState?.isOfflineCache ?: false"))
        // Startup may show a connection notice, but it cannot infer cache provenance.
        val cacheFlag = sessionsScreen.substringAfter("val isOfflineCache =").substringBefore("\n")
        assertEquals(" uiState?.isOfflineCache ?: false", cacheFlag)
        assertTrue(sessionsScreen.contains("SessionsOfflineSectionPolicy.sectionLabel(isOfflineCache)"))
        assertFalse(sessionsScreen.contains("SessionsOfflineSectionPolicy.sectionSubtitle("))
        assertTrue(offlineSection.contains("fun sectionLabel(isOfflineCache: Boolean)"))
        assertFalse(
            "offline-section label is flag-only (PR-7 must not widen it)",
            offlineSection.contains("ConnectState")
        )
    }
}
