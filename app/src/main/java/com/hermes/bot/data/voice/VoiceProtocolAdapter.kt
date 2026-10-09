package com.hermes.bot.data.voice

import com.hermes.bot.data.auth.*
import com.hermes.bot.data.local.AuthCredentialLease
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.gateway.json.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

class VoiceTranscription(val transcript: String, val receipt: AuthCredentialLease) {
    override fun toString() = "VoiceTranscription([redacted])"
}
interface VoiceProtocolAdapter {
    suspend fun transcribe(origin: CanonicalOrigin, profile: String, lease: AuthCredentialLease,
        wav: ByteArray, onReceipt: (AuthCredentialLease) -> Unit = {}): VoiceTranscription
}
class DefaultVoiceProtocolAdapter(private val auth: AuthRepository,
    private val transport: VoiceHttpTransport) : VoiceProtocolAdapter {
    override suspend fun transcribe(origin: CanonicalOrigin, profile: String, lease: AuthCredentialLease,
        wav: ByteArray, onReceipt: (AuthCredentialLease) -> Unit): VoiceTranscription {
        if (wav.size !in 45..VoiceLimits.MAX_PCM_BYTES + 44) throw VoiceException(VoiceFailure.INVALID)
        val (payload, deadline) = withContext(Dispatchers.Default) {
            // The two-minute WAV becomes a multi-megabyte JSON body; keep encoding off Main.
            val dataUrl = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)
            val bytes = GatewayJson.serialize(JsonObject(mapOf(
                "data_url" to JsonString(dataUrl), "mime_type" to JsonString("audio/wav")
            ))).toByteArray(Charsets.UTF_8)
            bytes to VoiceLimits.requestTimeoutMillis(dataUrl.length)
        }
        try {
            return withTimeout(deadline) {
                val exchange = auth.exchangeAuthenticated(origin, PATH, lease) { headers ->
                    transport.open(origin, PATH, profile, payload, headers, deadline)
                }.getOrThrow()
                exchange.response.use { response ->
                    onReceipt(exchange.receipt)
                    when (response.statusCode) {
                        404, 405, 501 -> throw VoiceException(VoiceFailure.UNAVAILABLE)
                        400, 413, 422 -> throw VoiceException(VoiceFailure.REJECTED)
                        !in 200..299 -> throw VoiceException(VoiceFailure.NETWORK)
                    }
                    val bytes = response.readBoundedBody()
                    val transcript = withContext(Dispatchers.Default) { decode(bytes) }
                    if (!auth.isResponseCurrent(exchange.receipt)) throw VoiceException(VoiceFailure.STALE)
                    currentCoroutineContext().ensureActive()
                    VoiceTranscription(transcript, exchange.receipt)
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw VoiceException(VoiceFailure.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: VoiceException) { throw failure }
        catch (_: AuthSessionRejectedException) { throw VoiceException(VoiceFailure.AUTH) }
        catch (_: StaleAuthenticatedResponseException) { throw VoiceException(VoiceFailure.STALE) }
        catch (_: Exception) { throw VoiceException(VoiceFailure.NETWORK) }
        finally { payload.fill(0) }
    }
    companion object {
        const val PATH = "/api/audio/transcribe"
        internal fun decode(bytes: ByteArray): String {
            try {
                if (bytes.size > VoiceLimits.MAX_RESPONSE_BYTES) throw VoiceException(VoiceFailure.INVALID)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                val root = GatewayJson.parse(text) as? JsonObject ?: throw VoiceException(VoiceFailure.INVALID)
                if (root["ok"]?.asBooleanOrNull() != true) throw VoiceException(VoiceFailure.INVALID)
                val transcript = root["transcript"]?.asStringOrNull() ?: throw VoiceException(VoiceFailure.INVALID)
                if (transcript.toByteArray(Charsets.UTF_8).size > VoiceLimits.MAX_TRANSCRIPT_BYTES)
                    throw VoiceException(VoiceFailure.INVALID)
                return transcript.trim()
            } catch (failure: VoiceException) { throw failure }
            catch (_: Exception) { throw VoiceException(VoiceFailure.INVALID) }
        }
    }
}
