package io.github.anshuman2401.hermesmobile.feature.brain

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Supplementary source checks; Compose rendering remains outside JVM proof. */
class BrainMainModelDialogSourceContractTest {
    @Test
    fun fixedChromeAndListOnlyScrollAreDeclared() {
        val source = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainMainModelDialog.kt")
        assertTrue(source.contains("LazyColumn("))
        assertTrue(source.contains(".weight(1f)"))
        assertTrue(source.contains("OutlinedTextField("))
        assertTrue(source.contains("Text(\"Reasoning\""))
        assertTrue(source.contains("Text(\"Cancel\""))
        assertTrue(source.contains("Text(\"Apply\""))
        assertTrue(source.contains("DialogProperties("))
        assertTrue(source.contains("brainDialogHeightPx"))
        assertTrue(
            "the dialog must not make the whole column the scrolling surface",
            !source.contains("verticalScroll(rememberScrollState())")
        )
    }

    @Test
    fun draftAndSavingContractsAreVisibleInProductionSources() {
        val viewModel = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainMainSettingsViewModel.kt")
        val dialog = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainMainModelDialog.kt")
        assertTrue(viewModel.contains("fun selectModel"))
        assertTrue(viewModel.contains("fun selectReasoning"))
        assertTrue(viewModel.contains("if (dialog.saving"))
        assertTrue(viewModel.contains("ConfirmationRequired"))
        assertTrue(viewModel.contains("Model saved; reasoning not saved"))
        assertTrue(dialog.contains("!state.saving"))
        assertTrue(dialog.contains("Role.RadioButton"))
    }

    @Test
    fun sourceOnlyRecoveryActionAndReloadWiringAreExpected() {
        val screen = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainMainSettingsScreen.kt")
        val dialog = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainMainModelDialog.kt")
        assertTrue("source-only: dialog needs visible Reload settings recovery", dialog.contains("Reload settings"))
        assertTrue("source-only: screen needs to wire the existing reload", screen.contains("onReload = viewModel::load"))
    }

    private fun source(relativePath: String): String {
        val direct = File("src/main/java/$relativePath")
        val fromRepoRoot = File("app/src/main/java/$relativePath")
        val file = if (direct.exists()) direct else fromRepoRoot
        assertTrue("Expected source file to exist: $relativePath", file.exists())
        return file.readText()
    }
}
