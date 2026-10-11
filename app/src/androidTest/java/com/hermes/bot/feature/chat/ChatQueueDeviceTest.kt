package com.hermes.bot.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.hermes.bot.data.commands.SlashInvocation
import com.hermes.bot.data.queue.*
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.HermesBotTheme
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fresh synthetic emulator; actual screen/VM/coordinator, isolated test store, no production graph or network. */
class ChatQueueDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: ChatViewModel
    private class Store(private var saved: ChatQueueState) : ChatQueueStore {
        override suspend fun read() = saved
        override suspend fun write(state: ChatQueueState): Boolean { saved = state; return true }
        override suspend fun isCurrent() = true
    }
    private fun show(items: List<QueueItem> = emptyList(), large: Boolean = false, dark: Boolean = false) {
        compose.runOnUiThread {
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            model = ChatViewModel("synthetic-chat", chatQueueStore = Store(ChatQueueState(waiting = items)))
            val field = ChatViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val state = field.get(model) as MutableStateFlow<ChatUiState>
            state.value = model.uiState.value.copy(durableSessionId = "synthetic-chat", runtimeSessionId = "synthetic-runtime",
                isInitialLoading = false, isStreaming = true, queueEnabled = true,
                headerConnectionStatus = ConnectionStatus.CONNECTED,
                timeline = listOf(AssistantMessage("synthetic-answer", "Synthetic streaming response", state = AssistantMessageState.STREAMING)))
            ChatViewModel::class.java.getDeclaredField("activeTurnBuffer").apply { isAccessible = true }
                .set(model, AssistantTurnStreamBuffer().apply { appendDelta("Synthetic streaming response") })
            ChatViewModel::class.java.getDeclaredField("activeAssistantEntryId").apply { isAccessible = true }.set(model, "synthetic-answer")
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.5f else 1f)) {
                HermesBotTheme(themePreference = if (dark) AppThemePreference.DARK else AppThemePreference.LIGHT) {
                    ChatScreen("synthetic-chat", {}, {}, viewModel = model)
                }
            }
        }
        compose.waitForIdle()
    }
    @After fun close() {
        if (::model.isInitialized) compose.runOnUiThread { androidx.lifecycle.ViewModelStore().apply { put("synthetic", model); clear() } }
    }
    @Test fun editablePromptAndCancelOnlyCommandAboveComposer() {
        val prompt = QueueItem(payload = QueuePayload.Prompt("Synthetic prompt"))
        show(listOf(prompt, QueueItem(payload = QueuePayload.Command(SlashInvocation("yolo", "on")))))
        compose.onAllNodesWithText("Edit").assertCountEquals(1)
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Synthetic edited")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(prompt.id, model.uiState.value.queue.waiting.first().id)
            assertEquals("Synthetic edited", (model.uiState.value.queue.waiting.first().payload as QueuePayload.Prompt).text) }
        compose.onAllNodesWithText("Cancel").onLast().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, model.uiState.value.queue.waiting.size) }
        compose.onNodeWithText("Stop generating").assertIsDisplayed()
    }
    @Test fun queueWhileStreamingKeepsStopAccessibleWithKeyboardAndLargeFonts() {
        show(large = true, dark = true)
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("Synthetic new prompt")
        compose.onNodeWithContentDescription("Add to queue").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, model.uiState.value.queue.waiting.size)
            assertEquals("", model.uiState.value.composerDraft); assertTrue(model.uiState.value.isStreaming) }
        compose.onNodeWithText("Stop generating").assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse(model.uiState.value.isStreaming)
            assertNotNull(model.uiState.value.queue.pause); assertEquals(1, model.uiState.value.queue.waiting.size) }
        compose.onNodeWithText("Resume queue").performScrollTo().assertIsDisplayed()
    }
}
