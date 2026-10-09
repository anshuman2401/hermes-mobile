package io.github.anshuman2401.hermesmobile.data.update

import io.github.anshuman2401.hermesmobile.data.auth.AuthenticatedHttpCall
import io.github.anshuman2401.hermesmobile.data.insights.InsightsResponse
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

fun interface HostUpdateTransport {
    fun open(origin: CanonicalOrigin, path: String, method: String, headers: Map<String, String>): AuthenticatedHttpCall<InsightsResponse>
}

class HostUpdateTransportException : IOException("Host update transport unavailable")

/** Exact update routes, bounded bodies, no redirects, automatic retry, cookie jar or response cache. */
class OkHttpHostUpdateTransport(client: OkHttpClient = OkHttpClient()) : HostUpdateTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp may otherwise follow 421/503 even with connection retry disabled.
            if (response.code == 421 || response.code == 503) {
                response.close(); throw HostUpdateTransportException()
            }
            response
        }.build()

    override fun open(origin: CanonicalOrigin, path: String, method: String, headers: Map<String, String>): AuthenticatedHttpCall<InsightsResponse> {
        val route = path.substringBefore('?')
        require((method == "POST" && route == "/api/hermes/update" && path == route) ||
            (method == "GET" && route in setOf("/api/hermes/update/check", "/api/actions/hermes-update/status")))
        val request = Request.Builder().url(origin.resolveHttp(path)).method(method,
            if (method == "POST") ByteArray(0).toRequestBody() else null).apply {
            headers.forEach { (key, value) -> header(key, value) }
            header("Accept", "application/json")
        }.build()
        val call = client.newCall(request)
        call.timeout().timeout(if (method == "POST") 30 else 15, TimeUnit.SECONDS)
        return object : AuthenticatedHttpCall<InsightsResponse> {
            private val response = AtomicReference<Response?>()
            private val state = AtomicInteger(0) // 0 unclaimed, 1 body reading, 2 closed
            override fun cancel() {
                call.cancel()
                if (state.compareAndSet(0, 2)) response.getAndSet(null)?.close()
            }
            override suspend fun awaitHeaders(): InsightsResponse = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(HostUpdateTransportException())
                    }
                    override fun onResponse(call: Call, received: Response) {
                        response.set(received)
                        if (state.get() == 2) response.getAndSet(null)?.close()
                        val handle = object : InsightsResponse {
                            override val statusCode = received.code
                            override val headers = received.headers.toMultimap()
                            override fun close() = cancel()
                            override suspend fun body(): ByteArray {
                                if (!state.compareAndSet(0, 1)) throw HostUpdateTransportException()
                                return suspendCancellableCoroutine { body ->
                                    body.invokeOnCancellation { call.cancel() }
                                    client.dispatcher.executorService.execute {
                                        try {
                                            val stream = received.body?.byteStream() ?: throw HostUpdateTransportException()
                                            val output = ByteArrayOutputStream()
                                            stream.use {
                                                val chunk = ByteArray(4096)
                                                while (true) {
                                                    val n = it.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - output.size()))
                                                    if (n < 0) break
                                                    if (output.size() + n > MAX_BYTES) throw HostUpdateTransportException()
                                                    output.write(chunk, 0, n)
                                                }
                                            }
                                            if (body.isActive) body.resume(output.toByteArray()) { _, _, _ -> }
                                        } catch (_: Exception) {
                                            if (body.isActive) body.resumeWithException(HostUpdateTransportException())
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
    companion object { const val MAX_BYTES = 64 * 1024 }
}
