package com.hermes.bot.feature.settings

import com.hermes.bot.data.local.CacheClearResult
import com.hermes.bot.data.local.LocalDataResetCoordinator
import com.hermes.bot.test.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Plan CACHE-PR5 TEST-08 — Settings' first view model: each action defers to
 * the coordinator exactly once (FGU-004), a thrown coordinator error never
 * escapes to the UI (REQ-02/OQ-02), and the source references ONLY the
 * coordinator seam — no vault type, gateway, transport, or sign-out
 * vocabulary (SEC-04).
 */
class SettingsViewModelResetTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class RecordingCoordinator(
        private val throwOnCall: Boolean = false,
    ) : LocalDataResetCoordinator {
        val calls = mutableListOf<String>()
        override suspend fun clearOfflineCache(): CacheClearResult {
            calls += "clearOfflineCache"
            if (throwOnCall) error("injected coordinator failure")
            return CacheClearResult.Complete
        }

        override suspend fun clearAllLocalData(): CacheClearResult {
            calls += "clearAllLocalData"
            if (throwOnCall) error("injected coordinator failure")
            return CacheClearResult.Complete
        }
    }

    @Test
    fun clearOfflineCache_defersToCoordinatorOnce() {
        val coordinator = RecordingCoordinator()
        val vm = SettingsViewModel(coordinator)
        vm.clearOfflineCache()
        assertEquals(listOf("clearOfflineCache"), coordinator.calls)
    }

    @Test
    fun clearAllLocalData_defersToCoordinatorOnce() {
        val coordinator = RecordingCoordinator()
        val vm = SettingsViewModel(coordinator)
        vm.clearAllLocalData()
        assertEquals(listOf("clearAllLocalData"), coordinator.calls)
    }

    @Test
    fun coordinatorFailure_neverEscapesToTheUi() {
        val coordinator = RecordingCoordinator(throwOnCall = true)
        val vm = SettingsViewModel(coordinator)
        // Swallowed by contract: neither call throws (REQ-02/OQ-02).
        vm.clearOfflineCache()
        vm.clearAllLocalData()
        assertEquals(
            "both attempts reached the coordinator exactly once",
            listOf("clearOfflineCache", "clearAllLocalData"), coordinator.calls
        )
    }

    @Test
    fun source_referencesOnlyTheCoordinatorSeam_noVaultGatewayTransportOrSignOutVocabulary() {
        val src = source("src/main/java/com/hermes/bot/feature/settings/SettingsViewModel.kt")
        assertTrue(src.contains("resetCoordinator.clearOfflineCache()"))
        assertTrue(src.contains("resetCoordinator.clearAllLocalData()"))
        for (banned in listOf("EncryptedVault", "Gateway", "Origin", "origin", "transport", "signOut", "sign-out")) {
            assertTrue(
                "SEC-04: '$banned' must not appear in SettingsViewModel",
                !src.contains(banned)
            )
        }
    }

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
}
