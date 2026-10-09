package com.hermes.bot.data.voice

import com.hermes.bot.data.auth.AuthenticatedHttpCall
import com.hermes.bot.data.auth.AuthenticatedHttpHeaders
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface VoiceResponse : AuthenticatedHttpHeaders {
    suspend fun readBoundedBody(): ByteArray
}

interface VoiceHttpTransport {
    fun open(origin: CanonicalOrigin, path: String, profile: String, payload: ByteArray,
        headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<VoiceResponse>
}

/** Header-first, no cookie jar, redirects or diagnostics; body cancellation closes the same call. */
class OkHttpVoiceTransport(client: OkHttpClient = OkHttpClient(), private val maxBodyBytes: Int = MAX_BODY_BYTES) : VoiceHttpTransport {
    init { require(maxBodyBytes in 1..MAX_BODY_BYTES) }
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp can follow 503 Retry-After:0 and coalesced 421 even with connection retry disabled.
            // Terminate those provider failures before the follow-up interceptor; preserve credentials.
            if (response.code == 503 || response.code == 421) {
                response.close()
                throw IOException("Voice provider unavailable")
            }
            response
        }.build()

    override fun open(origin: CanonicalOrigin, path: String, profile: String, payload: ByteArray,
        headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<VoiceResponse> {
        val url = origin.resolveHttp(path).toHttpUrl().newBuilder()
            .addQueryParameter("profile", profile).build()
        val request = Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return object : AuthenticatedHttpCall<VoiceResponse> {
            private val response = AtomicReference<Response?>()
            // 0: unclaimed body, 1: reader owns close, 2: closed/cancelled before claim.
            private val bodyState = AtomicInteger(0)
            override fun cancel() {
                call.cancel() // Thread-safe socket cancellation unblocks a pending reader.
                if (bodyState.compareAndSet(0, 2)) response.getAndSet(null)?.close()
            }
            override suspend fun awaitHeaders(): VoiceResponse = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(VoiceException(VoiceFailure.NETWORK))
                    }
                    override fun onResponse(call: Call, received: Response) {
                        response.set(received)
                        if (bodyState.get() == 2) response.getAndSet(null)?.close()
                        val handle = object : VoiceResponse {
                            override val statusCode = received.code
                            override val headers = received.headers.toMultimap()
                            override fun close() { cancel() }
                            override suspend fun readBoundedBody(): ByteArray {
                                if (!bodyState.compareAndSet(0, 1))
                                    throw VoiceException(VoiceFailure.NETWORK)
                                return suspendCancellableCoroutine { body ->
                                body.invokeOnCancellation { call.cancel() }
                                client.dispatcher.executorService.execute {
                                    try {
                                        val stream = received.body?.byteStream()
                                            ?: throw VoiceException(VoiceFailure.INVALID)
                                        val output = ByteArrayOutputStream()
                                        val chunk = ByteArray(8192)
                                        stream.use {
                                            while (true) {
                                                // Read at most one byte beyond the decompressed cap.
                                                val count = it.read(chunk, 0, minOf(chunk.size, maxBodyBytes + 1 - output.size()))
                                                if (count < 0) break
                                                if (output.size() + count > maxBodyBytes)
                                                    throw VoiceException(VoiceFailure.INVALID)
                                                output.write(chunk, 0, count)
                                            }
                                        }
                                        if (body.isActive) body.resume(output.toByteArray())
                                    } catch (e: Exception) {
                                        close()
                                        if (body.isActive) body.resumeWithException(if (e is VoiceException) e
                                            else VoiceException(VoiceFailure.NETWORK))
                                    } finally {
                                        // A stream must never be closed concurrently with its blocking read.
                                        response.getAndSet(null)?.close()
                                        bodyState.set(2)
                                    }
                                }
                                }
                            }
                        }
                        continuation.resume(handle) { _, value, _ -> value.close() }
                    }
                })
            }
        }
    }

    companion object { const val MAX_BODY_BYTES = VoiceLimits.MAX_RESPONSE_BYTES }
}
