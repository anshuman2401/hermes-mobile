package io.github.anshuman2401.hermesmobile.feature.chat

import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anshuman2401.hermesmobile.design.HermesBotTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Synthetic presentation test: deliberately bypasses gateway, disk and owner data. */
class StreamRenderingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: ChatViewModel
    private lateinit var state: MutableStateFlow<ChatUiState>
    @Volatile private var atBottom = false
    private var scale = androidx.compose.runtime.mutableFloatStateOf(1f)
    private val history = List<TimelineEntry>(500) { i ->
        if (i % 2 == 0) UserMessage("synthetic-user-$i", "Synthetic question $i", timestamp = 0)
        else AssistantMessage("synthetic-answer-$i", "Synthetic answer $i with **formatting**.", timestamp = 0)
    }
    private val body = buildString {
        repeat(80) { i ->
            append("## Section $i\n\nSynthetic paragraph with **bold**, _emphasis_ and [link](https://example.com).\n\n")
            append("- Item one\n- Item two\n\n| A | B |\n|---|---|\n| one | two |\n\n```kotlin\nval number = $i\n```\n\n")
        }
    }
    @Before fun setup() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".streamprofile")) {
            "Run only with -PstreamProfile in the isolated synthetic package"
        }
        compose.runOnUiThread {
            model = ChatViewModel("synthetic-session")
            // Existing public preview state cannot edit the composer. A test-only
            // fixture writes presentation state; real composer intents still use the VM.
            val field = ChatViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            state = field.get(model) as MutableStateFlow<ChatUiState>
            state.value = ChatUiState(durableSessionId = "synthetic-session", runtimeSessionId = "synthetic-runtime",
                timeline = history, isInitialLoading = false)
            ChatRenderProbe.layout = { atBottom = !it }
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.floatValue)) {
                HermesBotTheme { ChatScreen("synthetic-session", {}, {}, viewModel = model) }
            }
        }
        compose.waitForIdle()
        Thread.sleep(1000)
    }
    @After fun teardown() {
        ChatRenderProbe.beforePreparation = null
        ChatRenderProbe.preparation = null
        ChatRenderProbe.layout = null
        compose.runOnUiThread {
            androidx.lifecycle.ViewModelStore().apply { put("synthetic", model); clear() }
        }
    }
    private fun publish(text: String, terminal: Boolean = false) = compose.runOnUiThread {
        state.value = state.value.copy(timeline = history + AssistantMessage("synthetic-live", text,
            if (terminal) AssistantMessageState.COMPLETE else AssistantMessageState.STREAMING, timestamp = 0),
            isStreaming = !terminal)
    }
    private fun waitBottom() {
        compose.waitUntil(10_000) { atBottom }
        Thread.sleep(200)
        assertTrue("Measured layout must remain at the latest end", atBottom)
    }
    @Test fun delayedGrowthKeyboardAndUserIntent() {
        ChatRenderProbe.beforePreparation = { kotlinx.coroutines.delay(750) }
        val marker = "Synthetic delayed end marker"
        publish(body.take(800) + "\n\n" + marker)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(marker).fetchSemanticsNodes().isNotEmpty() }
        waitBottom()
        compose.onNodeWithText(marker).assertIsDisplayed()
        ChatRenderProbe.beforePreparation = null
        publish(body); waitBottom()
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Synthetic draft")
        waitBottom()
        compose.onNode(hasSetTextAction()).assertTextContains("Synthetic draft")
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertFalse("Upward reading pauses following", atBottom)
        publish(body + "\n\nA later synthetic paragraph.")
        Thread.sleep(500); compose.waitForIdle()
        assertFalse("Formatting must not pull an unpinned reader", atBottom)
        compose.onNodeWithContentDescription(NEW_ACTIVITY_SEMANTICS).performClick()
        waitBottom()
        compose.runOnUiThread { scale.floatValue = 1.6f }
        waitBottom()
        publish(body.repeat(5), true); waitBottom()
        compose.onNode(hasSetTextAction()).assertTextContains("Synthetic draft")
    }
    @Test fun prependPreservesUnpinnedViewport() {
        waitBottom()
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(100)
        compose.waitForIdle()
        val row = compose.onNodeWithText("Synthetic question 100")
        row.assertIsDisplayed()
        val before = row.fetchSemanticsNode().boundsInRoot.top
        compose.runOnUiThread {
            state.value = state.value.copy(
                timeline = listOf(UserMessage("synthetic-older", "Synthetic earlier message", timestamp = 0)) + state.value.timeline,
                olderHistoryRevision = state.value.olderHistoryRevision + 1)
        }
        compose.waitForIdle()
        row.assertIsDisplayed()
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot.top, 1f)
        assertFalse(atBottom)
    }
}
