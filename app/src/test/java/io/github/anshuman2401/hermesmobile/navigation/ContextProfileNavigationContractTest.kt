package io.github.anshuman2401.hermesmobile.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TASK-B5: Source-contract guard test for Context Profile navigation wiring.
 * Verifies:
 * - Only Sessions provides a workspace trigger; all three tabs provide Settings.
 * - Compact and expanded Sessions each provide one trigger.
 * - Global connection profile chip (ProfileSwitcherMenu) is completely absent and never re-introduced.
 * - Row click action points to the fixed non-switching explanation message without mutation RPCs.
 */
class ContextProfileNavigationContractTest {

    private val navShellFile = File("src/main/java/io/github/anshuman2401/hermesmobile/navigation/HermesNavShell.kt").takeIf { it.exists() }
        ?: File("app/src/main/java/io/github/anshuman2401/hermesmobile/navigation/HermesNavShell.kt")

    @Test
    fun navShell_existsAndIsReadable() {
        assertTrue("HermesNavShell.kt must exist", navShellFile.exists())
    }

    @Test
    fun navShell_noGlobalConnectionProfileChipReturns() {
        val content = navShellFile.readText()
        assertFalse("ProfileSwitcherMenu must not be referenced in HermesNavShell", content.contains("ProfileSwitcherMenu"))

        val oldProfileSwitcherFile = File("src/main/java/io/github/anshuman2401/hermesmobile/navigation/ProfileSwitcherMenu.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/io/github/anshuman2401/hermesmobile/navigation/ProfileSwitcherMenu.kt")
        assertFalse("Old ProfileSwitcherMenu.kt must remain deleted", oldProfileSwitcherFile.exists())
    }

    @Test
    fun navShell_wiresSwitcherOnlyOnSessionsAndSettingsOnAllTabs() {
        val content = navShellFile.readText()
        val sessions = content.substringAfter("BottomNavTab.CHATS -> {").substringBefore("BottomNavTab.WORK -> {")
        org.junit.Assert.assertEquals(2, Regex("profileSwitcher = workspaceTrigger").findAll(sessions).count())
        val work = content.substringAfter("BottomNavTab.WORK -> {").substringBefore("BottomNavTab.BOTS -> {")
        val bots = content.substringAfter("BottomNavTab.BOTS -> {").substringBefore("ShellScreen.BOT_CHANNEL -> {")
        listOf(sessions, work, bots).forEach { assertTrue(it.contains("onNavigateToSettings")) }
        assertFalse(content.contains("workspaceTrigger()"))
        org.junit.Assert.assertEquals(2, Regex("profileSwitcher = workspaceTrigger").findAll(content).count())
        assertTrue(content.contains("ContextProfileBottomSheet"))
        assertTrue(content.contains("ContextProfileSwitcherTrigger"))
        assertFalse(content.contains("BottomNavTab.HOME"))
        listOf("work/WorkScreen.kt", "bots/BotsScreen.kt").forEach { relative ->
            val screen = File(navShellFile.parentFile.parentFile, "feature/$relative").readText()
            assertTrue(screen.contains("onClick = onNavigateToSettings"))
            assertTrue(screen.contains("Open settings"))
            assertTrue(screen.contains("48.dp"))
        }
    }

    @Test
    fun navShell_botAndSheetReuseLocalSelectionRoute_noDirectPost() {
        val content = navShellFile.readText()
        assertFalse("Must not invoke POST /api/profiles/active directly", content.contains("POST"))
        assertFalse("Normal selection must not reference activateProfile", content.contains("activateProfile"))
        val helperFile = File(navShellFile.parentFile, "ChatShellCleanupPolicy.kt")
        val helper = helperFile.readText()
        org.junit.Assert.assertEquals(2, Regex("\\bperformWorkspaceSelection\\(").findAll(content).count())
        org.junit.Assert.assertEquals(1, Regex("repository\\.selectProfile\\(").findAll(helper).count())
        assertTrue(helper.indexOf("resetWorkspaceOwnership(owner, resetShell)") < helper.indexOf("repository.selectProfile(name)"))
        assertTrue(helper.contains("owner.activeKeys().forEach(owner::clear)"))
        assertFalse(helper.contains("activateProfile"))
        assertFalse(helper.contains("POST"))
        assertTrue(content.contains("isCreatingSession == true"))
        assertTrue(content.contains("ContextProfileSwitcherPolicy.isSwitchingAllowed"))

        val repositoryFile = File("src/main/java/io/github/anshuman2401/hermesmobile/data/context/ContextProfileRepository.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/io/github/anshuman2401/hermesmobile/data/context/ContextProfileRepository.kt")
        assertFalse(
            "Context profile repository must not retain a host-mutation POST path",
            repositoryFile.readText().contains("method = \"POST\"")
        )
    }

    @Test
    fun navShell_sheetCannotBypassPolicy() {
        val navContent = navShellFile.readText()
        assertTrue("NavShell must enforce isSwitchingAllowed", navContent.contains("ContextProfileSwitcherPolicy.isSwitchingAllowed"))

        val switcherFile = File("src/main/java/io/github/anshuman2401/hermesmobile/navigation/ContextProfileSwitcher.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/io/github/anshuman2401/hermesmobile/navigation/ContextProfileSwitcher.kt")
        val switcherContent = switcherFile.readText()
        assertTrue("ContextProfileSwitcher must delegate to handleRowClick", switcherContent.contains("ContextProfileSwitcherPolicy.handleRowClick"))
    }

    @Test
    fun sessionsFetch_alwaysCarriesProfileParam_whenPreferenceSet() {
        val sessionRepoFile = File("src/main/java/io/github/anshuman2401/hermesmobile/data/session/DefaultSessionRepository.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/io/github/anshuman2401/hermesmobile/data/session/DefaultSessionRepository.kt")
        val sessionRepoContent = sessionRepoFile.readText()
        val list = sessionRepoContent.substringAfter("override suspend fun listSessions(")
        assertTrue("Capture owner before HTTP", list.indexOf("vaultStorage.captureWorkspaceRecoveryOwner()") < list.indexOf("repo.getScopedSessionsCaptured("))
        assertTrue("Fetch binds explicit selection to original lease", list.contains("repo.getScopedSessionsCaptured(selectedProfile, captured.owner.lease)"))
        assertFalse("No ambient scoped fallback", list.contains("getScopedSessionsTyped(selectedProfile)"))

        val contextRepoFile = File("src/main/java/io/github/anshuman2401/hermesmobile/data/context/ContextProfileRepository.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/io/github/anshuman2401/hermesmobile/data/context/ContextProfileRepository.kt")
        val contextRepoContent = contextRepoFile.readText()
        assertTrue("ContextProfileRepository must append profile param", contextRepoContent.contains("/api/profiles/sessions?profile="))
        val captured = contextRepoContent.substringAfter("override suspend fun getScopedSessionsCaptured(").substringBefore("private suspend fun fetchScopedSessions")
        assertTrue(captured.contains("vaultStorage.readAuthCredentialLease(expectedLease.origin)"))
        assertTrue(captured.contains("fetchScopedSessions(profile, limit, expectedLease.credential)"))
        assertFalse(captured.contains("getAuthCredential()"))
    }

    @Test
    fun offlineRosterCaptionStrings_areOwnedByTheSwitcherPolicyOnly() {
        val policyFile = File("src/main/java/io/github/anshuman2401/hermesmobile/navigation/ContextProfileSwitcherPolicy.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/io/github/anshuman2401/hermesmobile/navigation/ContextProfileSwitcherPolicy.kt")
        val policyContent = policyFile.readText()
        assertTrue("Policy must own the exact offline label", policyContent.contains("\"Offline cache\""))
        assertTrue("Policy must own the exact saved-prefix", policyContent.contains("\"Offline cache (saved \""))

        val switcherFile = File("src/main/java/io/github/anshuman2401/hermesmobile/navigation/ContextProfileSwitcher.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/io/github/anshuman2401/hermesmobile/navigation/ContextProfileSwitcher.kt")
        assertFalse(
            "Sheet composable must not inline caption copy",
            switcherFile.readText().contains("Offline cache")
        )
        assertFalse(
            "Nav shell must not inline caption copy",
            navShellFile.readText().contains("Offline cache")
        )
    }
}
