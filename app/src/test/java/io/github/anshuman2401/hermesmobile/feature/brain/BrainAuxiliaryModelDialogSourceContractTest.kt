package io.github.anshuman2401.hermesmobile.feature.brain

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source checks supplement the actual ViewModel behavior; they are not rendering proof. */
class BrainAuxiliaryModelDialogSourceContractTest {
    @Test
    fun dialogKeepsFixedChromeAndListOnlyScrolling() {
        val source = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainAuxiliaryModelDialog.kt")
        assertTrue(source.contains("LazyColumn("))
        assertTrue(source.contains(".weight(1f)"))
        assertTrue(source.contains("OutlinedTextField"))
        assertTrue(source.contains("Return to Auto"))
        assertTrue(source.contains("Text(\"Cancel\""))
        assertTrue(source.contains("Text(\"Apply\""))
        assertTrue(source.contains("DialogProperties("))
        assertTrue(source.contains("brainDialogHeightPx"))
        assertTrue(!source.contains("verticalScroll(rememberScrollState())"))
        assertTrue(!source.contains("Reasoning"))
    }

    @Test
    fun productionBoundaryUsesTypedRepositoryAndNoWireAccessInUiFiles() {
        val viewModel = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainAuxiliaryModelsViewModel.kt")
        val screen = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainAuxiliaryModelsScreen.kt")
        val appContainer = source("io/github/anshuman2401/hermesmobile/bootstrap/AppContainer.kt")
        assertTrue(viewModel.contains("BrainAuxiliaryModelsRepository"))
        assertTrue(viewModel.contains("ConfirmationRequired"))
        assertTrue(viewModel.contains("reloadRequired = true"))
        assertTrue(screen.contains("readSelectedProfileAuthoritative").not())
        assertTrue(appContainer.contains("createBrainAuxiliaryModelsViewModel"))
        assertTrue(!viewModel.contains("OkHttp"))
        assertTrue(!viewModel.contains("Cookie"))
        assertTrue(!viewModel.contains("http://"))
        assertTrue(!screen.contains("http://"))
    }

    @Test
    fun readyPageExposesErrorOnlyReloadThroughPublicLoad() {
        val screen = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainAuxiliaryModelsScreen.kt")
        val readyBranch = screen
            .substringAfter("is BrainAuxiliaryModelsLoadState.Ready -> {")
            .substringBefore("\n                }\n            }")
        assertTrue(readyBranch.contains("uiState.reloadRequired"))
        assertTrue(readyBranch.contains("Reload settings"))
        assertTrue(readyBranch.contains("TextButton(onClick = viewModel::load)"))
    }

    @Test
    fun savingGuardsDismissAndControlsAcrossDialogSources() {
        val viewModel = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainAuxiliaryModelsViewModel.kt")
        val dialog = source("io/github/anshuman2401/hermesmobile/feature/brain/BrainAuxiliaryModelDialog.kt")
        assertTrue(viewModel.contains("if (dialog.saving"))
        assertTrue(viewModel.contains("fun returnToAuto"))
        assertTrue(viewModel.contains("fun confirmWarning"))
        assertTrue(viewModel.contains("fun declineWarning"))
        assertTrue(dialog.contains("dismissOnBackPress = !state.saving"))
        assertTrue(dialog.contains("dismissOnClickOutside = !state.saving"))
        assertTrue(dialog.contains("enabled = controlsEnabled"))
        assertTrue(dialog.contains("Reload settings"))
    }

    private fun source(relativePath: String): String {
        val direct = File("src/main/java/$relativePath")
        val fromRepoRoot = File("app/src/main/java/$relativePath")
        val file = if (direct.exists()) direct else fromRepoRoot
        assertTrue("Expected source file to exist: $relativePath", file.exists())
        return file.readText()
    }
}
