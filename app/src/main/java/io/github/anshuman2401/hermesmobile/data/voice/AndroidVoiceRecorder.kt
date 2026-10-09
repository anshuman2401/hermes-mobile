package io.github.anshuman2401.hermesmobile.data.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** PCM exists only in bounded memory; each capture owns and releases its microphone. */
class AndroidVoiceRecorder : VoiceRecorder {
    @SuppressLint("MissingPermission") // UI permission request; AudioRecord also enforces permission at runtime.
    override suspend fun start(): VoiceCapture {
        // withContext can discard its return on cancellation between IO and Main dispatch.
        val acquired = java.util.concurrent.atomic.AtomicReference<VoiceCapture?>()
        try {
            return withContext(Dispatchers.IO) {
                val minimum = AudioRecord.getMinBufferSize(VoiceLimits.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) throw VoiceException(VoiceFailure.MICROPHONE)
                val audio = AudioRecord(MediaRecorder.AudioSource.MIC, VoiceLimits.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8192))
                try {
                    if (audio.state != AudioRecord.STATE_INITIALIZED) throw VoiceException(VoiceFailure.MICROPHONE)
                    audio.startRecording()
                    if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw VoiceException(VoiceFailure.MICROPHONE)
                } catch (_: Exception) { audio.release(); throw VoiceException(VoiceFailure.MICROPHONE) }
                val capture = Capture(audio)
                acquired.set(capture)
                currentCoroutineContext().ensureActive()
                capture
            }
        } catch (failure: Exception) {
            acquired.get()?.cancel()
            throw failure
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private class Capture(private val audio: AudioRecord) : VoiceCapture {
        private val fault = java.util.concurrent.atomic.AtomicReference<VoiceFailure?>()
        override val failure: VoiceFailure? get() = fault.get()
        private val stopping = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()
        private var released = false
        private val result = scope.async(start = CoroutineStart.ATOMIC) {
            val pcm = ByteArray(VoiceLimits.MAX_PCM_BYTES)
            var size = 0
            try {
                while (!stopping.get() && size < pcm.size) {
                    ensureActive()
                    val count = audio.read(pcm, size, minOf(8192, pcm.size - size), AudioRecord.READ_BLOCKING)
                    if (count < 0 && !stopping.get()) throw VoiceException(VoiceFailure.MICROPHONE)
                    if (count > 0) size += count
                }
                ensureActive()
                if (cancelled.get() || size < 2) throw VoiceException(VoiceFailure.MICROPHONE)
                val retained = pcm.copyOf(size - size % 2)
                try { VoiceLimits.wav(retained) } finally { retained.fill(0) }
            } finally {
                pcm.fill(0)
                synchronized(lock) {
                    if (!released) { runCatching { audio.stop() }; audio.release(); released = true }
                }
            }
        }
        init {
            result.invokeOnCompletion { cause ->
                if (cause != null && cause !is CancellationException) fault.set(VoiceFailure.MICROPHONE)
            }
        }
        private fun stopHardware() {
            stopping.set(true)
            synchronized(lock) { if (!released) runCatching { audio.stop() } }
        }
        override suspend fun stop(): ByteArray {
            stopHardware()
            return try { result.await() } finally { scope.cancel() }
        }
        override fun cancel() {
            cancelled.set(true)
            stopHardware()
            // Wipe a completed result as well as the active PCM buffer.
            result.invokeOnCompletion { if (!result.isCancelled) runCatching { result.getCompleted().fill(0) } }
            scope.cancel()
        }
    }
}
