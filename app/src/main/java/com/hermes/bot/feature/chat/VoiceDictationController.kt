package com.hermes.bot.feature.chat

import com.hermes.bot.data.voice.*
import kotlinx.coroutines.*

enum class VoicePhase { IDLE, PERMISSION, STARTING, RECORDING, TRANSCRIBING }
data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val elapsedSeconds: Int = 0,
    val message: String? = null,
    val openSettings: Boolean = false,
    val transcriptRevision: Long = 0
) { val isBusy: Boolean get() = phase != VoicePhase.IDLE }

/** One foreground operation. Revoke generation before stopping microphone/request work. */
internal class VoiceDictationController(
    private val coordinator: VoiceDictationCoordinator,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val canStart: () -> Boolean,
    private val publish: (VoiceUiState) -> Unit,
    private val append: (String) -> Unit
) {
    private var generation = 0L
    private var state = VoiceUiState()
    private var operation: VoiceOperation? = null
    private var job: Job? = null
    private var monitor: Job? = null
    private var startedAt = 0L
    private fun set(next: VoiceUiState) { state = next; publish(next) }

    fun requestPermission(): Long? {
        if (state.isBusy || !canStart()) return null
        val token = ++generation
        set(state.copy(phase = VoicePhase.PERMISSION, message = null, openSettings = false, elapsedSeconds = 0))
        return token
    }
    fun permissionResult(token: Long, granted: Boolean, permanent: Boolean, storedId: String, runtimeId: String) {
        if (token != generation || state.phase != VoicePhase.PERMISSION) return
        if (!granted) {
            set(state.copy(phase = VoicePhase.IDLE, message = "Microphone permission is needed for dictation.", openSettings = permanent))
            return
        }
        set(state.copy(phase = VoicePhase.STARTING))
        job = scope.launch {
            try {
                val captured = coordinator.start(storedId, runtimeId)
                if (token != generation) { coordinator.cancel(captured); return@launch }
                operation = captured
                startedAt = nowMillis()
                set(state.copy(phase = VoicePhase.RECORDING))
                monitor = scope.launch {
                    while (token == generation) {
                        delay(250)
                        coordinator.recordingFailure(captured)?.let { failure ->
                            this@VoiceDictationController.cancel(failure.message)
                            return@launch
                        }
                        val owned = current(captured)
                        if (token != generation) return@launch
                        if (!owned) { this@VoiceDictationController.cancel(VoiceFailure.STALE.message); return@launch }
                        if (state.phase == VoicePhase.RECORDING) {
                            val elapsed = ((nowMillis() - startedAt).coerceAtLeast(0) / 1000).toInt()
                            set(state.copy(elapsedSeconds = elapsed.coerceAtMost(VoiceLimits.RECORDING_SECONDS)))
                            if (elapsed >= VoiceLimits.RECORDING_SECONDS) stop()
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) this@VoiceDictationController.cancel((failure as? VoiceException)?.failure?.message ?: VoiceFailure.MICROPHONE.message)
            }
        }
    }
    private suspend fun current(captured: VoiceOperation): Boolean = try { coordinator.isCurrent(captured) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    fun stop() {
        if (state.phase != VoicePhase.RECORDING) return
        val captured = operation ?: return
        val token = generation
        set(state.copy(phase = VoicePhase.TRANSCRIBING))
        job = scope.launch {
            val result = try { coordinator.stopAndTranscribe(captured) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { VoiceResult.Failed(VoiceFailure.NETWORK) }
            if (token != generation) return@launch
            val owned = current(captured)
            if (token != generation) return@launch
            if (!owned) { this@VoiceDictationController.cancel(VoiceFailure.STALE.message); return@launch }
            monitor?.cancel(); monitor = null
            operation = null
            coordinator.cancel(captured)
            when (result) {
                is VoiceResult.Transcript -> {
                    val transcript = result.text.trim()
                    if (transcript.isNotEmpty()) append(transcript)
                    set(state.copy(phase = VoicePhase.IDLE, elapsedSeconds = 0,
                        message = if (transcript.isEmpty()) "No speech detected. Try recording again." else null,
                        transcriptRevision = state.transcriptRevision + if (transcript.isNotEmpty()) 1 else 0))
                }
                is VoiceResult.Failed -> set(state.copy(phase = VoicePhase.IDLE, elapsedSeconds = 0, message = result.failure.message))
            }
        }
    }
    fun cancel(message: String? = null) {
        ++generation
        job?.cancel(); job = null
        monitor?.cancel(); monitor = null
        operation?.let(coordinator::cancel); operation = null
        set(state.copy(phase = VoicePhase.IDLE, elapsedSeconds = 0, message = message, openSettings = false))
    }
    fun dismissMessage() { set(state.copy(message = null, openSettings = false)) }
}

internal fun appendDictation(draft: String, transcript: String): String =
    draft + (if (draft.isNotEmpty() && !draft.endsWith("\n")) "\n" else "") + transcript
