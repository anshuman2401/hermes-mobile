package io.github.anshuman2401.hermesmobile.data.history

import io.github.anshuman2401.hermesmobile.data.auth.AuthenticatedHttpCall
import io.github.anshuman2401.hermesmobile.data.auth.AuthenticatedHttpHeaders
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface StoredHistoryResponse : AuthenticatedHttpHeaders {
    suspend fun readBoundedBody(): ByteArray
}

interface StoredHistoryHttpTransport {
    fun open(origin: CanonicalOrigin, path: String, profile: String, limit: Int, offset: Int,
        headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<StoredHistoryResponse>
}

/** Header-first, no cookie jar, redirects or diagnostics; body cancellation closes the same call. */
class OkHttpStoredHistoryTransport(client: OkHttpClient = OkHttpClient(), private val maxBodyBytes: Int = MAX_BODY_BYTES) : StoredHistoryHttpTransport {
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
                throw IOException("Stored history provider unavailable")
            }
            response
        }.build()

    override fun open(origin: CanonicalOrigin, path: String, profile: String, limit: Int, offset: Int,
        headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<StoredHistoryResponse> {
        val url = origin.resolveHttp(path).toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString())
            .addQueryParameter("order", "latest").addQueryParameter("include_compacted", "true")
            .addQueryParameter("profile", profile).build()
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return object : AuthenticatedHttpCall<StoredHistoryResponse> {
            private val response = AtomicReference<Response?>()
            // 0: unclaimed body, 1: reader owns close, 2: closed/cancelled before claim.
            private val bodyState = AtomicInteger(0)
            override fun cancel() {
                call.cancel() // Thread-safe socket cancellation unblocks a pending reader.
                if (bodyState.compareAndSet(0, 2)) response.getAndSet(null)?.close()
            }
            override suspend fun awaitHeaders(): StoredHistoryResponse = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE))
                    }
                    override fun onResponse(call: Call, received: Response) {
                        response.set(received)
                        if (bodyState.get() == 2) response.getAndSet(null)?.close()
                        val handle = object : StoredHistoryResponse {
                            override val statusCode = received.code
                            override val headers = received.headers.toMultimap()
                            override fun close() { cancel() }
                            override suspend fun readBoundedBody(): ByteArray {
                                if (!bodyState.compareAndSet(0, 1))
                                    throw StoredHistoryException(StoredHistoryFailure.UNAVAILABLE)
                                return suspendCancellableCoroutine { body ->
                                body.invokeOnCancellation { call.cancel() }
                                client.dispatcher.executorService.execute {
                                    try {
                                        val stream = received.body?.byteStream()
                                            ?: throw StoredHistoryException(StoredHistoryFailure.INTEGRITY)
                                        val output = ByteArrayOutputStream()
                                        val chunk = ByteArray(8192)
                                        stream.use {
                                            while (true) {
                                                // Read at most one byte beyond the decompressed cap.
                                                val count = it.read(chunk, 0, minOf(chunk.size, maxBodyBytes + 1 - output.size()))
                                                if (count < 0) break
                                                if (output.size() + count > maxBodyBytes)
                                                    throw StoredHistoryException(StoredHistoryFailure.OVERSIZE)
                                                output.write(chunk, 0, count)
                                            }
                                        }
                                        if (body.isActive) body.resume(output.toByteArray())
                                    } catch (e: Exception) {
                                        close()
                                        if (body.isActive) body.resumeWithException(if (e is StoredHistoryException) e
                                            else StoredHistoryException(StoredHistoryFailure.UNAVAILABLE))
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

    companion object { const val MAX_BODY_BYTES = 4 * 1024 * 1024 }
}
