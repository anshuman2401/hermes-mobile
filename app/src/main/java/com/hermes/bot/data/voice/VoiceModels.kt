package com.hermes.bot.data.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

object VoiceLimits {
    const val RECORDING_SECONDS = 120
    const val SAMPLE_RATE = 16_000
    const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * RECORDING_SECONDS
    const val MAX_RESPONSE_BYTES = 256 * 1024
    const val MAX_TRANSCRIPT_BYTES = 64 * 1024
    fun requestTimeoutMillis(dataUrlLength: Int): Long =
        kotlin.math.ceil(dataUrlLength * 0.1).toLong().coerceIn(180_000L, 600_000L)

    fun wav(pcm: ByteArray): ByteArray {
        require(pcm.isNotEmpty() && pcm.size <= MAX_PCM_BYTES && pcm.size % 2 == 0)
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}

enum class VoiceFailure(val message: String) {
    MICROPHONE("Could not record audio. Check microphone access and try again."),
    UNAVAILABLE("Transcription is unavailable for this Hermes host or profile."),
    REJECTED("Hermes could not transcribe this recording. Check transcription setup and try again."),
    AUTH("Sign in again to use dictation."),
    NETWORK("Could not transcribe audio. Check your connection and record again."),
    TIMEOUT("Transcription timed out. Try a shorter recording."),
    INVALID("Hermes returned an invalid or oversized transcription."),
    STALE("Dictation cancelled because the chat connection changed.")
}
class VoiceException(val failure: VoiceFailure) : Exception(failure.name)
sealed interface VoiceResult {
    class Transcript(val text: String) : VoiceResult { override fun toString() = "Transcript([redacted])" }
    class Failed(val failure: VoiceFailure) : VoiceResult
}
interface VoiceCapture {
    val failure: VoiceFailure? get() = null
    suspend fun stop(): ByteArray
    fun cancel()
}
interface VoiceRecorder { suspend fun start(): VoiceCapture }
