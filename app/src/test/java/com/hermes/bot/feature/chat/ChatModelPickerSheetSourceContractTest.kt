package com.hermes.bot.feature.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring/geometry checks supplement the picker policy and ViewModel behavior tests. */
class ChatModelPickerSheetSourceContractTest {
    private fun source(relativePath: String): String {
        val direct = File("src/main/java/$relativePath")
        val fromRoot = File("app/src/main/java/$relativePath")
        val file = if (direct.exists()) direct else fromRoot
        assertTrue("Missing source $relativePath", file.exists())
        return file.readText()
    }

    private fun dialog() = source("com/hermes/bot/feature/chat/components/ModelPickerDialog.kt")

    @Test fun chatRendersCenteredDialogWithoutBottomSheet() {
        val screen = source("com/hermes/bot/feature/chat/ChatScreen.kt")
        assertTrue(screen.contains("ModelPickerDialog("))
        assertFalse(screen.contains("ModelPickerSheet("))
        assertTrue(dialog().contains("Dialog(onDismissRequest = onDismiss"))
        assertTrue(dialog().contains("fillMaxWidth(0.92f).widthIn(min = 280.dp, max = 560.dp)"))
        assertTrue(dialog().contains("modelPickerDialogHeightPx("))
    }

    @Test fun chatKeepsExistingSessionScopedActionsAndDismissesAfterSelection() {
        val screen = source("com/hermes/bot/feature/chat/ChatScreen.kt")
        assertTrue(screen.contains("onRefresh = { viewModel?.openSessionPicker() }"))
        assertTrue(screen.contains("onPickModel = { viewModel?.pickSessionModel(it) }"))
        assertTrue(screen.contains("onPickReasoning = { viewModel?.pickSessionReasoning(it) }"))
        assertTrue(dialog().contains("onClick = { onPickModel(option); onDismiss() }"))
        assertTrue(dialog().contains("onPickReasoning(level); onDismiss()"))
        assertFalse(dialog().contains("onApply"))
    }

    @Test fun settingsPatternHasSearchRadioRowsAndFixedReasoningFooter() {
        val source = dialog()
        assertTrue(source.contains("Text(\"Choose chat model\""))
        assertTrue(source.contains("Search models or providers"))
        assertTrue(source.contains("LazyColumn("))
        assertTrue(source.contains("role = Role.RadioButton"))
        assertTrue(source.contains("RadioButton(selected = selected"))
        assertTrue(source.indexOf("LazyColumn(") < source.indexOf("Text(\"Reasoning\""))
        assertTrue(source.contains("DropdownMenu("))
        assertTrue(source.contains("Text(\"Close\")"))
    }

    @Test fun noAmbiguousSelectedRadioAndHostOrderIsRetained() {
        val source = dialog()
        assertTrue(source.contains("SessionModelPickerGroupingPolicy.group(options)"))
        assertTrue(source.contains("SessionModelPickerGroupingPolicy.filter(groups, searchQuery)"))
        assertTrue(source.contains("SessionModelPickerGroupingPolicy.uniqueCurrentOption(options, currentModelName)"))
        assertTrue(source.contains("selected = selectedOption === option"))
        assertTrue(source.contains("searching || expandedProvider == group.providerSlug"))
    }

    @Test fun unavailableAndPendingStatesRemainInDialog() {
        val source = dialog()
        assertTrue(source.contains("val readOnly = !canOpenPicker"))
        assertTrue(source.contains("Can't switch models"))
        assertTrue(source.contains("Connect to Hermes to change the model or reasoning."))
        assertTrue(source.contains("pickerPendingNotice?.takeIf { it != pickerNotice }"))
        assertTrue(source.contains("Text(\"Check status\")"))
        assertTrue(source.contains("No configured models available."))
        assertTrue(source.contains("No models or providers match your search."))
    }

    @Test fun openerStillWorksOfflineAndPickerHasNoViewModelDependency() {
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")
        assertTrue(composer.contains(".clickable(enabled = !voice.isBusy, onClick = onOpenPicker)"))
        assertTrue(source("com/hermes/bot/feature/chat/ChatScreen.kt").contains(
            "onOpenPicker = { showModelPicker = true; viewModel?.openSessionPicker() }"))
        assertFalse(dialog().contains("import com.hermes.bot.feature.chat.ChatViewModel"))
    }
}
