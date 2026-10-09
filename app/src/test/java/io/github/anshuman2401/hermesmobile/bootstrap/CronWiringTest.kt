package io.github.anshuman2401.hermesmobile.bootstrap

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Supplemental composition coverage; state/auth behavior lives in cron/work tests. */
class CronWiringTest {
    @Test fun productionCompositionSuppliesCronsToTheWorkTab() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java")).first { it.exists() }
        val container = root.resolve("io/github/anshuman2401/hermesmobile/bootstrap/AppContainer.kt").readText()
        val activity = root.resolve("io/github/anshuman2401/hermesmobile/bootstrap/MainActivity.kt").readText()
        val shell = root.resolve("io/github/anshuman2401/hermesmobile/navigation/HermesNavShell.kt").readText()
        val nav = root.resolve("io/github/anshuman2401/hermesmobile/navigation/BottomNav.kt").readText()
        assertTrue(container.contains("override val cronRepository: CronRepository by lazy"))
        assertTrue(activity.contains("WorkCronsViewModel(repository, container.contextProfileRepository)"))
        assertTrue(activity.contains("workCronsViewModel = workCronsViewModel"))
        val work = shell.substringAfter("BottomNavTab.WORK -> {").substringBefore("BottomNavTab.BOTS -> {")
        assertTrue(work.contains("WorkScreen(workCronsViewModel, kanbanViewModel = kanbanViewModel,"))
        assertTrue(work.contains("filesViewModel = managedFilesViewModel"))
        assertTrue(work.contains("currentTheme = themePreference"))
        assertTrue(activity.contains("kanbanViewModel = kanbanViewModel"))
        assertTrue(work.contains("onNavigateToSettings"))
        assertTrue(work.contains("onReconnect = onRetry"))
        assertTrue(work.contains("currentScreen = ShellScreen.SETTINGS"))
        assertFalse(nav.contains("fun WorkScreen("))
    }
    @Test fun workReadsAreOwnedByVisibleStartedLifecycle() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java")).first { it.exists() }
        val screen = root.resolve("io/github/anshuman2401/hermesmobile/feature/work/WorkScreen.kt").readText()
        assertTrue(screen.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"))
        assertTrue(screen.contains("viewModel.pageStarted()"))
        assertTrue(screen.contains("finally { viewModel.pageStopped() }"))
        assertFalse(screen.contains("LaunchedEffect(state.online, state.accessGeneration)"))
        assertTrue(screen.contains("if (state.online) viewModel.refresh() else onReconnect()"))
    }

}
