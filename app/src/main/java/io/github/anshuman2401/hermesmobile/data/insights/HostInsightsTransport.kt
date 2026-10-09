package io.github.anshuman2401.hermesmobile.data.insights

import io.github.anshuman2401.hermesmobile.data.auth.AuthenticatedHttpCall
import io.github.anshuman2401.hermesmobile.data.auth.AuthenticatedHttpHeaders
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

interface InsightsResponse : AuthenticatedHttpHeaders { suspend fun body(): ByteArray }
fun interface HostInsightsTransport {
    fun open(origin: CanonicalOrigin, path: String, headers: Map<String, String>): AuthenticatedHttpCall<InsightsResponse>
}
class InsightsTransportException : IOException("Host read unavailable")

/** Header-first, bounded, cancellable reads. No body/query logging or response cache. */
class OkHttpHostInsightsTransport(client: OkHttpClient = OkHttpClient()) : HostInsightsTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code in setOf(421, 503)) { response.close(); throw InsightsTransportException() }
            response
        }.build()
    override fun open(origin: CanonicalOrigin, path: String, headers: Map<String, String>): AuthenticatedHttpCall<InsightsResponse> {
        require(path.substringBefore('?') in setOf("/api/status", "/api/system/stats", "/api/analytics/usage", "/api/logs"))
        val request = Request.Builder().url(origin.resolveHttp(path)).get().apply {
            headers.forEach { (key, value) -> header(key, value) }
            header("Accept", "application/json")
        }.build()
        val call = client.newCall(request)
        call.timeout().timeout(15, TimeUnit.SECONDS)
        return object : AuthenticatedHttpCall<InsightsResponse> {
            private val response = AtomicReference<Response?>()
            // 0 unclaimed; 1 body owns cleanup; 2 closed.
            private val state = AtomicInteger(0)
            override fun cancel() {
                call.cancel()
                if (state.compareAndSet(0, 2)) response.getAndSet(null)?.close()
            }
            override suspend fun awaitHeaders(): InsightsResponse = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(InsightsTransportException())
                    }
                    override fun onResponse(call: Call, received: Response) {
                        response.set(received)
                        if (state.get() == 2) response.getAndSet(null)?.close()
                        val handle = object : InsightsResponse {
                            override val statusCode = received.code
                            override val headers = received.headers.toMultimap()
                            override fun close() = cancel()
                            override suspend fun body(): ByteArray {
                                if (!state.compareAndSet(0, 1)) throw InsightsTransportException()
                                return suspendCancellableCoroutine { body ->
                                    body.invokeOnCancellation { call.cancel() }
                                    client.dispatcher.executorService.execute {
                                        try {
                                            val stream = received.body?.byteStream() ?: throw InsightsTransportException()
                                            val output = ByteArrayOutputStream()
                                            stream.use {
                                                val chunk = ByteArray(8192)
                                                while (true) {
                                                    val n = it.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - output.size()))
                                                    if (n < 0) break
                                                    if (output.size() + n > MAX_BYTES) throw InsightsTransportException()
                                                    output.write(chunk, 0, n)
                                                }
                                            }
                                            body.resume(output.toByteArray()) { _, _, _ -> }
                                        } catch (_: Exception) {
                                            if (body.isActive) body.resumeWithException(InsightsTransportException())
                                        } finally {
                                            response.getAndSet(null)?.close()
                                            state.set(2)
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
    companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}
