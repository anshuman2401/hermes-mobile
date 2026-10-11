package com.hermes.bot.feature.chat

import com.hermes.bot.data.attachments.CameraPhoto
import com.hermes.bot.data.attachments.CameraPhotoStore
import com.hermes.bot.data.attachments.CameraPhotoTooLargeException
import com.hermes.bot.data.attachments.CameraUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

enum class CameraPhase { IDLE, PREPARING, READY, CAPTURING, REVIEW }

data class CameraUiState(
    val phase: CameraPhase = CameraPhase.IDLE,
    val token: Long? = null,
    val preview: ByteArray? = null,
    val error: String? = null
)

/** Owns one memory-only camera attempt across Activity recreation, never across chat ownership. */
internal class CameraCaptureController(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val store: CameraPhotoStore?,
    private val readContext: CoroutineContext,
    private val attach: (String) -> Job?
) {
    private class Attempt(val token: Long, val durable: String?, val runtime: String, var photo: CameraPhoto? = null)
    private val mutableState = MutableStateFlow(CameraUiState())
    val state = mutableState.asStateFlow()
    private var active: Attempt? = null
    private var preparation: Job? = null

    fun start() {
        val current = uiState.value
        if (active != null || !current.canAttachImage) return
        if (store == null) { fail("Camera is unavailable on this device."); return }
        val attempt = Attempt(tokens.incrementAndGet(), current.durableSessionId, current.runtimeSessionId ?: return)
        active = attempt
        uiState.update { it.copy(cameraBusy = true) }
        mutableState.value = CameraUiState(CameraPhase.PREPARING, attempt.token)
        preparation = scope.launch {
            var created: CameraPhoto? = null
            var adopted = false
            try {
                // Retain the handle even if cancellation races file creation, so finally can delete it.
                withContext(NonCancellable + readContext) { created = store.create() }
                if (!owns(attempt)) { if (active === attempt) cancel(); return@launch }
                attempt.photo = created
                adopted = true
                mutableState.value = CameraUiState(CameraPhase.READY, attempt.token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: CameraUnavailableException) {
                if (active === attempt) fail("Camera is unavailable on this device. Choose Photos instead.")
            } catch (_: Exception) {
                if (active === attempt) fail("Could not open camera. Try again.")
            } finally {
                if (!adopted) created?.let(store::discard)
            }
        }
    }

    /** Consumes the launch once before the external Activity starts; recomposition cannot relaunch it. */
    fun claimLaunch(token: Long): String? {
        val attempt = active ?: return null
        if (attempt.token != token || state.value.phase != CameraPhase.READY) return null
        if (!owns(attempt)) { cancel(); return null }
        val uri = attempt.photo?.uri ?: return null
        mutableState.value = CameraUiState(CameraPhase.CAPTURING, token)
        return uri
    }

    fun result(token: Long, success: Boolean, launchFailed: Boolean = false) {
        val attempt = active ?: return
        if (attempt.token != token || state.value.phase != CameraPhase.CAPTURING) return
        if (!owns(attempt)) { cancel(); return }
        if (!success) {
            if (launchFailed) fail("Could not open camera. Choose Photos or try again.") else cancel()
            return
        }
        val photo = attempt.photo ?: return
        mutableState.value = CameraUiState(CameraPhase.PREPARING, token)
        preparation = scope.launch {
            try {
                val preview = withContext(readContext) { store!!.preview(photo) }
                if (owns(attempt)) mutableState.value = CameraUiState(CameraPhase.REVIEW, token, preview)
                else if (active === attempt) cancel()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: CameraPhotoTooLargeException) {
                if (active === attempt) fail("Photos must be 25 MB or smaller.")
            } catch (_: Exception) {
                if (active === attempt) fail("Could not read camera photo. Try again.")
            }
        }
    }

    fun usePhoto() {
        val attempt = active ?: return
        if (state.value.phase != CameraPhase.REVIEW) return
        if (!owns(attempt)) { cancel(); return }
        val photo = attempt.photo ?: return
        active = null
        mutableState.value = CameraUiState()
        uiState.update { it.copy(cameraBusy = false) }
        // The existing attachment controller reads/uploads once. Keep the original until it finishes.
        try {
            val job = attach(photo.uri)
            if (job == null) store?.discard(photo) else job.invokeOnCompletion { store?.discard(photo) }
        } catch (failure: Exception) {
            store?.discard(photo)
            throw failure
        }
    }

    fun retake() { if (state.value.phase == CameraPhase.REVIEW) { cancel(); start() } }

    fun reconcile() { active?.let { if (!owns(it)) cancel() } }

    fun background(changingConfigurations: Boolean) {
        // The external camera necessarily stops this Activity. Its in-flight result remains owned.
        if (!changingConfigurations && state.value.phase != CameraPhase.CAPTURING) cancel()
    }

    fun cancel() {
        val attempt = active
        active = null
        preparation?.cancel()
        preparation = null
        attempt?.photo?.let { store?.discard(it) }
        mutableState.value = CameraUiState()
        uiState.update { it.copy(cameraBusy = false) }
    }

    fun dismissError() { mutableState.update { it.copy(error = null) } }

    private fun owns(attempt: Attempt): Boolean {
        val current = uiState.value
        return active === attempt && current.durableSessionId == attempt.durable &&
            current.runtimeSessionId == attempt.runtime && current.copy(cameraBusy = false).canAttachImage
    }

    private fun fail(message: String) { cancel(); mutableState.value = CameraUiState(error = message) }

    private companion object { val tokens = AtomicLong() }
}
