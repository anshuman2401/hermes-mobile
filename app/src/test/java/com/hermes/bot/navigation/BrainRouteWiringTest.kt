package com.hermes.bot.navigation

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Supplemental source contracts, not Compose execution. */
class BrainRouteWiringTest {
    private fun source(path: String): String = sequenceOf(File("."), File("app"))
        .map { it.resolve("src/main/java/com/hermes/bot/$path") }.first { it.exists() }.readText()

    @Test fun rootLazyFactoriesReachAllThreeOwnedPageConsumers() {
        val root = source("bootstrap/MainActivity.kt")
        val shell = source("navigation/HermesNavShell.kt")
        val host = source("feature/brain/BrainScreen.kt")
        val owner = source("feature/brain/BrainNavigationViewModel.kt")
        val bindings = listOf(
            Triple("brainModels", "createBrainMainSettingsViewModel", "models"),
            Triple("brainAuxiliary", "createBrainAuxiliaryModelsViewModel", "auxiliary"),
            Triple("brainSkills", "createBrainSkillsViewModel", "skills")
        )
        bindings.forEach { (rootName, factory, hostName) ->
            val declaration = root.substringAfter("val ${rootName}Provider:").substringBefore("\n    val ")
            assertTrue(declaration.contains("remember(container)"))
            assertTrue(declaration.contains("container?.let { c -> { c.$factory() } }"))
            assertTrue(root.contains("${rootName}ViewModelProvider = ${rootName}Provider"))
            assertTrue(shell.contains("${hostName}ViewModelProvider = ${rootName}ViewModelProvider"))
            assertTrue(host.contains("owner.$hostName()"))
        }
        assertTrue(host.contains("owner.updateProviders(modelsViewModelProvider, auxiliaryViewModelProvider, skillsViewModelProvider)"))
        listOf("BrainMainSettingsScreen(it, back", "BrainAuxiliaryModelsScreen(it, back", "BrainSkillsScreen(it, back").forEach {
            assertTrue(host.contains(it))
        }
        assertTrue(owner.contains("ViewModelProvider(childStore, factory).get(destination.name, type)"))
        assertTrue(host.contains("DisposableEffect(store) { onDispose { store.clear() } }"))
        assertTrue(host.contains("key(it)"))
        assertFalse(root.contains("as DefaultAppContainer"))
    }

    @Test fun settingsBranchOwnsVisitAndPreservesPreviousAndExistingCallbacks() {
        val shell = source("navigation/HermesNavShell.kt")
        val branch = shell.substringAfter("ShellScreen.SETTINGS -> {").substringBefore("ShellScreen.DEBUG_LOGS ->")
        assertTrue(branch.contains("var brainOpen by remember { mutableStateOf(false) }"))
        // Brain visits remain non-restorable; unrelated Settings child routes may restore their destination.
        assertFalse(branch.contains("var brainOpen by rememberSaveable"))
        assertTrue(branch.contains("if (brainOpen)"))
        assertTrue(branch.contains("onNavigateBack = { brainOpen = false }"))
        assertTrue(branch.contains("onOpenBrain = { brainOpen = true }"))
        assertTrue(branch.contains("onNavigateBack = { currentScreen = previousScreen }"))
        listOf("onClearOfflineCache", "onClearLocalData", "onOpenDebugLogs", "onOpenDiagnostics", "onSwitchProfile", "onRemoveProfile", "onAddConnection", "profileSwitcher = null").forEach {
            assertTrue(branch.contains(it))
        }
        val screens = shell.substringAfter("enum class ShellScreen {").substringBefore("}")
        assertEquals(listOf("CONNECT", "SESSIONS", "CHAT", "BOT_SCREEN", "BOT_CHANNEL", "SETTINGS", "DEBUG_LOGS"), screens.split(',').map { it.trim() })
        assertTrue(branch.contains("contextProfileRepository?.readSelectedProfileAuthoritative()"))
        assertFalse(branch.contains("getSelectedProfile()"))
        assertFalse(branch.contains("getProfiles()"))
    }

    @Test fun hostBackPrecedesDetailHandlerAndToolbarSharesOrdinaryReturnWithoutReload() {
        val shell = source("navigation/HermesNavShell.kt")
        val host = source("feature/brain/BrainScreen.kt")
        val skills = source("feature/brain/BrainSkillsScreen.kt")
        val owner = source("feature/brain/BrainNavigationViewModel.kt")
        assertTrue(shell.indexOf("BackHandler(enabled = true)") < shell.indexOf("BrainScreen("))
        assertTrue(host.indexOf("BackHandler(onBack = back)") < host.indexOf("BrainSkillsScreen("))
        assertTrue(host.contains("val back: () -> Unit = { if (owner.back() == BrainBackAction.SETTINGS) onNavigateBack() }"))
        val detail = skills.substringAfter("if (openIdentity != null && viewModel.hasDetailViewModelFactory()) {").substringBefore("val titleProfile")
        assertTrue(detail.contains("val returnToSkills = brainDetailReturn(detailStore) { detailIdentity = null }"))
        assertTrue(detail.indexOf("BackHandler(onBack = returnToSkills)") < detail.indexOf("BrainSkillDetailScreen("))
        assertTrue(detail.contains("onNavigateBack = returnToSkills"))
        assertTrue(detail.contains("onReopenFromSkills = returnToSkills"))
        val reload = detail.substringAfter("onReloadSkills = {").substringBefore("},")
        assertTrue(reload.contains("viewModel.load()"))
        assertEquals(1, Regex("viewModel\\.load\\(\\)").findAll(detail).count())
        val ordinaryReturn = owner.substringAfter("internal fun brainDetailReturn").substringBefore("internal fun brainNavigationOwner")
        assertTrue(ordinaryReturn.contains("detailStore.clear()"))
        assertTrue(ordinaryReturn.contains("clearIdentity()"))
        assertFalse(ordinaryReturn.contains("load("))
    }

    @Test fun overviewOrderReadOnlyCaptionAndMissingProvidersHaveWorkingBack() {
        val host = source("feature/brain/BrainScreen.kt")
        val overview = host.substringAfter("private fun BrainOverview").substringBefore("private fun BrainEntry")
        assertTrue(overview.indexOf("BrainHeading(\"Brain\", back)") < overview.indexOf("Text(caption"))
        assertTrue(overview.indexOf("BrainEntry(\"Models\"") < overview.indexOf("BrainEntry(\"Auxiliary Models\""))
        assertTrue(overview.indexOf("BrainEntry(\"Auxiliary Models\"") < overview.indexOf("BrainEntry(\"Skills\""))
        listOf("Models", "Auxiliary Models", "Skills").forEach {
            assertTrue(host.contains("?: BrainUnavailable(\"$it\", back, modifier)"))
        }
        val unavailable = host.substringAfter("private fun BrainUnavailable").substringBefore("private fun BrainHeading")
        assertTrue(unavailable.contains("BrainHeading(title, back)"))
        assertTrue(unavailable.contains("Brain page unavailable"))
        listOf("Tools", "Memory", "Switcher", "getProfiles", "getSelectedProfile", "Vault", "OkHttp").forEach {
            assertFalse(host.contains(it))
        }
        assertTrue(host.contains("remember(readSelectedProfile) { ViewModelStore() }"))
    }
}
