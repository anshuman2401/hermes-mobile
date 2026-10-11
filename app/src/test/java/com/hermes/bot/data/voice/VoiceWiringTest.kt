package com.hermes.bot.data.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source checks supplement controller/HTTP behavior; they do not certify Android capture or layout. */
class VoiceWiringTest {
    private val root = if (File("app/src/main").exists()) "app/src/main" else "src/main"
    private fun source(path: String) = File("$root/$path").readText()
    @Test fun productionGraphAndForegroundLifecycleReachCoordinator() {
        val container = source("java/com/hermes/bot/bootstrap/AppContainer.kt")
        assertTrue(container.contains("voiceCoordinator = com.hermes.bot.data.voice.DefaultVoiceDictationCoordinator("))
        assertTrue(container.contains("DefaultVoiceRepository(sessionRepository, vaultStorage, authRepository"))
        assertTrue(container.contains("AndroidVoiceRecorder()"))
        val screen = source("java/com/hermes/bot/feature/chat/ChatScreen.kt")
        assertTrue(screen.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(screen.contains("dictationPermissionResult(token, granted, permanent)"))
        val lifecycle = screen.substring(screen.indexOf("val lifecycleOwner = LocalLifecycleOwner.current"))
        val stop = lifecycle.substring(lifecycle.indexOf("if (event == Lifecycle.Event.ON_STOP)"), lifecycle.indexOf("lifecycleOwner.lifecycle.addObserver(observer)"))
        val dispose = lifecycle.substring(lifecycle.indexOf("onDispose {"), lifecycle.indexOf("uiState?.let { state ->"))
        for (region in listOf(stop, dispose)) {
            assertTrue(region.contains("viewModel?.setQueueVisibility(false, pauseOnHide ="))
            assertTrue(region.contains("viewModel?.setSubagentVisibility(false)"))
            assertTrue(region.contains("viewModel?.setApprovalVisibility(false)"))
            assertTrue(region.contains("viewModel?.cancelDictation()"))
        }
        assertTrue(stop.contains("fileController?.background()"))
        assertTrue(dispose.contains("lifecycleOwner.lifecycle.removeObserver(observer)"))
        assertTrue(screen.contains("BackHandler(enabled = uiState?.voice?.isBusy == true) { viewModel?.cancelDictation() }"))
        val manifest = source("AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO")); assertTrue(manifest.contains("android:allowBackup=\"false\""))
        val recorder = source("java/com/hermes/bot/data/voice/AndroidVoiceRecorder.kt")
        assertTrue("capture retained across cancellable IO return", recorder.contains("acquired.get()?.cancel()"))
        assertFalse(recorder.contains("java.io.File")); assertFalse(recorder.contains("MediaRecorder()"))
        val composer = source("java/com/hermes/bot/feature/chat/components/ChatComposer.kt")
        assertTrue(composer.contains("TextRange(draftText.length)"))
        assertTrue(composer.contains("maxLines = 4")); assertTrue(composer.contains(".size(48.dp)"))
    }
}
