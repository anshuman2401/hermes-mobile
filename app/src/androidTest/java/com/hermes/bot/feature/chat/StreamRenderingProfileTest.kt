package com.hermes.bot.feature.chat

import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.bot.design.HermesBotTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Real Android frame clock: deliberately does not install a Compose test rule. */
class StreamRenderingProfileTest {
    @Test fun profile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".streamprofile"))
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val frames = Collections.synchronizedList(mutableListOf<Long>())
        val preparations = Collections.synchronizedList(mutableListOf<Long>())
        val visibility = Collections.synchronizedList(mutableListOf<Long>())
        val completed = AtomicLong(); val canceled = AtomicLong(); val latestPrepared = AtomicLong()
        val bottom = java.util.concurrent.atomic.AtomicBoolean()
        val thread = HandlerThread("synthetic-frame-metrics").apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var activity: ComponentActivity
        lateinit var model: ChatViewModel
        lateinit var state: MutableStateFlow<ChatUiState>
        val history = List<TimelineEntry>(500) { i ->
            if (i % 2 == 0) UserMessage("synthetic-user-$i", "Synthetic question $i", timestamp = 0)
            else AssistantMessage("synthetic-answer-$i", "Synthetic answer $i with **formatting**.", timestamp = 0)
        }
        val body = buildString {
            repeat(80) { i ->
                append("## Section $i\n\nSynthetic paragraph with **bold**, _emphasis_ and [link](https://example.com).\n\n")
                append("- Item one\n- Item two\n\n| A | B |\n|---|---|\n| one | two |\n\n```kotlin\nval number = $i\n```\n\n")
            }
        }
        fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            // Controls are after the timeline; search them before traversing a
            // large reply's accessibility descendants during rapid updates.
            for (index in node.childCount - 1 downTo 0) {
                find(node.getChild(index), predicate)?.let { return it }
            }
            return null
        }
        fun appRoot(): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
            return automation.windows.firstNotNullOfOrNull { window ->
            window.root?.takeIf { it.packageName?.toString() == instrumentation.targetContext.packageName }
        } ?: automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == instrumentation.targetContext.packageName }
        }
        fun editable(): AccessibilityNodeInfo {
            repeat(50) {
                find(appRoot()) { node ->
                    node.isEditable || node.className?.toString() == "android.widget.EditText"
                }?.let { return it }
                Thread.sleep(20)
            }
            error("Synthetic composer unavailable; activeRoot=${appRoot()?.className}")
        }
        fun percentile(values: List<Long>, percent: Int): Long {
            val snapshot = synchronized(values) { values.sorted() }
            return if (snapshot.isEmpty()) 0 else snapshot[(snapshot.size - 1) * percent / 100] / 1000
        }
        val listener = Window.OnFrameMetricsAvailableListener { _, metric, _ ->
            frames.add(metric.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            try {
                ChatRenderProbe.layout = { bottom.set(!it) }
                scenario.onActivity {
                    activity = it
                    it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    model = ChatViewModel("synthetic-session")
                    val field = ChatViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    state = field.get(model) as MutableStateFlow<ChatUiState>
                    state.value = ChatUiState(durableSessionId = "synthetic-session", runtimeSessionId = "synthetic-runtime",
                        timeline = history, isInitialLoading = false)
                    it.setContent { HermesBotTheme { ChatScreen("synthetic-session", {}, {}, viewModel = model) } }
                    it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                }
                Thread.sleep(2000)
                ChatRenderProbe.preparation = { duration, success ->
                    preparations.add(duration)
                    if (success) { completed.incrementAndGet(); latestPrepared.set(System.nanoTime()) } else canceled.incrementAndGet()
                }
                ChatRenderProbe.layout = { forward ->
                    bottom.set(!forward)
                    if (!forward) {
                        val prepared = latestPrepared.getAndSet(0)
                        if (prepared != 0L) visibility.add(System.nanoTime() - prepared)
                    }
                }
                repeat(4) { run ->
                    instrumentation.runOnMainSync {
                        activity.currentFocus?.clearFocus()
                        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                        state.value = state.value.copy(timeline = history, isStreaming = false, composerDraft = "")
                    }
                    Thread.sleep(1000)
                    frames.clear(); preparations.clear(); visibility.clear(); completed.set(0); canceled.set(0); latestPrepared.set(0)
                    val gcBefore = Debug.getRuntimeStat("art.gc.gc-count").toLong()
                    val bytesBefore = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
                    val start = System.nanoTime()
                    val publications = AtomicLong()
                    fun until(millis: Long) {
                        val remaining = start + millis * 1_000_000 - System.nanoTime()
                        if (remaining > 0) Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    }
                    val producer = scope.launch {
                        repeat(1500) { tick ->
                            val remaining = start + tick * 20_000_000L - System.nanoTime()
                            if (remaining > 0) delay((remaining / 1_000_000).coerceAtLeast(1))
                            val text = body.take(((tick + 1) * body.length / 1500).coerceAtLeast(1))
                            state.update { it.copy(timeline = history + AssistantMessage("synthetic-live", text,
                                AssistantMessageState.STREAMING, timestamp = 0), isStreaming = true) }
                            publications.incrementAndGet()
                        }
                        state.update { it.copy(timeline = history + AssistantMessage("synthetic-live", body,
                            AssistantMessageState.COMPLETE, timestamp = 0), isStreaming = false) }
                    }
                    until(10_000)
                    assertTrue(editable().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    Thread.sleep(300)
                    var keyboardVisible = false
                    for (attempt in 0 until 50) {
                        instrumentation.runOnMainSync {
                            keyboardVisible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                        }
                        if (keyboardVisible) break
                        Thread.sleep(100)
                    }
                    assertTrue("Profile must exercise the visible keyboard", keyboardVisible)
                    val typed = "synthetic typing"
                    for (length in 1..typed.length) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, typed.take(length))
                        }
                        assertTrue(editable().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                        Thread.sleep(80)
                    }
                    for (attempt in 0 until 50) {
                        if (state.value.composerDraft == typed) break
                        Thread.sleep(100)
                    }
                    assertEquals("Synthetic draft edits must reach the real composer", typed, state.value.composerDraft)
                    until(15_000)
                    val composerBounds = Rect().also { editable().getBoundsInScreen(it) }
                    val scrollNode = find(appRoot()) { node ->
                        val bounds = Rect().also { node.getBoundsInScreen(it) }
                        node.isScrollable && !node.isEditable && node.className?.toString() != "android.widget.EditText" &&
                            bounds.bottom <= composerBounds.top && bounds.height() > 48
                    } ?: error("Synthetic timeline viewport unavailable")
                    val viewport = Rect().also { scrollNode.getBoundsInScreen(it) }
                    assertTrue("Timeline must remain usable with keyboard open", viewport.height() > 48)
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "gesture run=$run viewport=$viewport composer=$composerBounds class=${scrollNode.className}\n")
                    })
                    val x = viewport.exactCenterX()
                    val from = viewport.top + viewport.height() * .3f
                    val to = viewport.top + viewport.height() * .8f
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                        "input swipe ${x.toInt()} ${from.toInt()} ${x.toInt()} ${to.toInt()} 300"
                    )).use { it.readBytes() }
                    Thread.sleep(500)
                    until(20_000)
                    var jump: AccessibilityNodeInfo? = null
                    for (attempt in 0 until 100) {
                        jump = find(appRoot()) { it.contentDescription?.toString() == NEW_ACTIVITY_SEMANTICS }
                        if (jump != null) break
                        Thread.sleep(50)
                    }
                    if (jump == null) {
                        val screenshot = automation.takeScreenshot()
                        java.io.File(instrumentation.targetContext.cacheDir, "synthetic-gesture.png").outputStream().use {
                            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        screenshot.recycle()
                    }
                    checkNotNull(jump) { "Synthetic jump-to-latest unavailable after upward gesture; bottom=${bottom.get()} elapsedMs=${(System.nanoTime() - start) / 1_000_000}" }
                    // Refresh on a revoked accessibility ID and require actual
                    // activation rather than assuming an injected tap landed.
                    var activated = false
                    repeat(20) {
                        if (!activated) {
                            activated = find(appRoot()) { node -> node.contentDescription?.toString() == NEW_ACTIVITY_SEMANTICS }
                                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                            if (!activated) Thread.sleep(50)
                        }
                    }
                    assertTrue("Jump-to-latest action must be accepted", activated)
                    until(25_000)
                    instrumentation.runOnMainSync {
                        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                    }
                    runBlocking { producer.join() }
                    Thread.sleep(1500)
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "run=$run warmup=${run == 0} publications=${publications.get()} frames=${frames.size} frameP50Us=${percentile(frames, 50)} frameP95Us=${percentile(frames, 95)} frameP99Us=${percentile(frames, 99)} over16ms=${synchronized(frames) { frames.count { it > 16_666_667 } }} preparations=${preparations.size} completed=${completed.get()} canceled=${canceled.get()} prepareP95Us=${percentile(preparations, 95)} visibilityP95Us=${percentile(visibility, 95)} visibilitySamples=${visibility.size} gc=${Debug.getRuntimeStat("art.gc.gc-count").toLong() - gcBefore} allocatedBytes=${Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - bytesBefore} bottom=${bottom.get()} elapsedMs=${(System.nanoTime() - start) / 1_000_000}\n")
                    })
                    assertEquals(1500L, publications.get())
                }
            } finally {
                scope.cancel()
                ChatRenderProbe.preparation = null; ChatRenderProbe.layout = null
                instrumentation.runOnMainSync {
                    activity.window.removeOnFrameMetricsAvailableListener(listener)
                    androidx.lifecycle.ViewModelStore().apply { put("synthetic", model); clear() }
                }
                thread.quitSafely()
            }
        }
    }
}
