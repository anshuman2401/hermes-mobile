package io.github.anshuman2401.hermesmobile.data.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source checks supplement controller/HTTP behavior; they do not certify Android capture or layout. */
class VoiceWiringTest {
    private val root = if (File("app/src/main").exists()) "app/src/main" else "src/main"
    private fun source(path: String) = File("$root/$path").readText()
    @Test fun productionGraphAndForegroundLifecycleReachCoordinator() {
        val container = source("java/io/github/anshuman2401/hermesmobile/bootstrap/AppContainer.kt")
        assertTrue(container.contains("voiceCoordinator = io.github.anshuman2401.hermesmobile.data.voice.DefaultVoiceDictationCoordinator("))
        assertTrue(container.contains("DefaultVoiceRepository(sessionRepository, vaultStorage, authRepository"))
        assertTrue(container.contains("AndroidVoiceRecorder()"))
        val screen = source("java/io/github/anshuman2401/hermesmobile/feature/chat/ChatScreen.kt")
        assertTrue(screen.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(screen.contains("dictationPermissionResult(token, granted, permanent)"))
        assertTrue(screen.contains("Lifecycle.Event.ON_STOP) { fileController?.background(); viewModel?.setSubagentVisibility(false); viewModel?.setApprovalVisibility(false); viewModel?.cancelDictation() }"))
        assertTrue(screen.contains("onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel?.setSubagentVisibility(false); viewModel?.setApprovalVisibility(false); viewModel?.cancelDictation() }"))
        assertTrue(screen.contains("BackHandler(enabled = uiState?.voice?.isBusy == true) { viewModel?.cancelDictation() }"))
        val manifest = source("AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO")); assertTrue(manifest.contains("android:allowBackup=\"false\""))
        val recorder = source("java/io/github/anshuman2401/hermesmobile/data/voice/AndroidVoiceRecorder.kt")
        assertTrue("capture retained across cancellable IO return", recorder.contains("acquired.get()?.cancel()"))
        assertFalse(recorder.contains("java.io.File")); assertFalse(recorder.contains("MediaRecorder()"))
        val composer = source("java/io/github/anshuman2401/hermesmobile/feature/chat/components/ChatComposer.kt")
        assertTrue(composer.contains("TextRange(draftText.length)"))
        assertTrue(composer.contains("maxLines = 4")); assertTrue(composer.contains(".size(48.dp)"))
    }
}
