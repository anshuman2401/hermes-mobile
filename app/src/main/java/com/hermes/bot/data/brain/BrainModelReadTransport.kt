package com.hermes.bot.data.brain

import com.hermes.bot.data.auth.AuthenticatedHttpCall
import com.hermes.bot.data.auth.AuthenticatedHttpHeaders
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface BrainModelReadResponse : AuthenticatedHttpHeaders {
    suspend fun readBoundedBody(): ByteArray
}

interface BrainModelReadTransport {
    fun open(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse>

    /** Additive GET seam for the selected-profile auxiliary assignment read. */
    fun openAuxiliaryRead(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = throw BrainModelTransportException()

    /** Additive fail-closed seam for the exact main-model mutation. */
    fun openMainMutation(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        body: String,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = throw BrainModelTransportException()

    /** Additive fail-closed seam for the exact sparse reasoning mutation. */
    fun openReasoningMutation(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        body: String,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = throw BrainModelTransportException()

    /** Additive fail-closed seam for the exact auxiliary assignment mutation. */
    fun openAuxiliaryMutation(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        body: String,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = throw BrainModelTransportException()

    /** Additive GET seam for the selected-profile installed-skills inventory. */
    fun openSkillsRead(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = throw BrainModelTransportException()

    /** Additive fail-closed seam for the exact read-only skill document GET. */
    fun openSkillsContentRead(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = throw BrainModelTransportException()

    /** Additive fail-closed seam for the exact installed-skill toggle mutation. */
    fun openSkillsMutation(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        body: String,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = throw BrainModelTransportException()
}

open class BrainModelTransportException : IOException("Model read unavailable")

/** Narrow response-body overflow signal; ordinary I/O is not classified as oversized. */
class BrainModelBodyTooLargeException : BrainModelTransportException()

/** Header-first GET transport with no cookies, redirects, retries, or body persistence. */
class OkHttpBrainModelReadTransport(
    client: OkHttpClient = OkHttpClient()
) : BrainModelReadTransport {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // Keep provider failures terminal; OkHttp may otherwise schedule a
            // follow-up for Retry-After: 0 or a coalesced 421 response.
            if (response.code == 503 || response.code == 421) {
                response.close()
                throw BrainModelTransportException()
            }
            response
        }
        .build()

    override fun open(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> {
        return openGet(origin, path, headers, timeoutMillis, MODEL_READ_PATHS, "model read")
    }

    override fun openAuxiliaryRead(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = open(origin, path, headers, timeoutMillis)

    override fun openSkillsRead(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> =
        openGet(origin, path, headers, timeoutMillis, SKILLS_READ_PATHS, "skills read")

    override fun openSkillsContentRead(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> {
        require(path.substringBefore('?') == "/api/skills/content") {
            "Unsupported skills content path"
        }
        val url = origin.resolveHttp(path).toHttpUrl()
        require(url.querySize == 2 &&
            (0..1).all { url.queryParameterValue(it) != null } &&
            setOf(url.queryParameterName(0), url.queryParameterName(1)) == setOf("profile", "name")) {
            "Invalid skills content query"
        }
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return newGetCallHandle(call)
    }

    override fun openMainMutation(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        body: String,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = openMutation(
        origin, path, headers, body, timeoutMillis, "POST", setOf("/api/model/set")
    )

    override fun openReasoningMutation(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        body: String,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = openMutation(
        origin, path, headers, body, timeoutMillis, "PUT", setOf("/api/config")
    )

    override fun openAuxiliaryMutation(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        body: String,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = openMutation(
        origin, path, headers, body, timeoutMillis, "POST", setOf("/api/model/set")
    )

    override fun openSkillsMutation(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        body: String,
        timeoutMillis: Long
    ): AuthenticatedHttpCall<BrainModelReadResponse> = openMutation(
        origin, path, headers, body, timeoutMillis, "PUT", setOf("/api/skills/toggle")
    )

    private fun openGet(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        allowedPaths: Set<String>,
        kind: String
    ): AuthenticatedHttpCall<BrainModelReadResponse> {
        require(path.substringBefore('?') in allowedPaths) { "Unsupported $kind path" }
        val url = origin.resolveHttp(path).toHttpUrl()
        require(url.querySize == 1 && url.queryParameterName(0) == "profile" &&
            url.queryParameterValue(0) != null) { "Invalid $kind query" }
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)

        return newGetCallHandle(call)
    }

    private fun newGetCallHandle(call: Call): AuthenticatedHttpCall<BrainModelReadResponse> =
        object : AuthenticatedHttpCall<BrainModelReadResponse> {
            private val response = AtomicReference<okhttp3.Response?>()
            // 0 = unclaimed, 1 = body reader owns cleanup, 2 = closed/cancelled.
            private val bodyState = AtomicInteger(0)

            override fun cancel() {
                call.cancel()
                if (bodyState.compareAndSet(0, 2)) response.getAndSet(null)?.close()
            }

            override suspend fun awaitHeaders(): BrainModelReadResponse =
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(BrainModelTransportException())
                        }

                        override fun onResponse(call: Call, received: okhttp3.Response) {
                            response.set(received)
                            if (bodyState.get() == 2) response.getAndSet(null)?.close()
                            val handle = object : BrainModelReadResponse {
                                override val statusCode: Int = received.code
                                override val headers: Map<String, List<String>> = received.headers.toMultimap()

                                override fun close() = cancel()

                                override suspend fun readBoundedBody(): ByteArray {
                                    if (!bodyState.compareAndSet(0, 1)) {
                                        throw BrainModelTransportException()
                                    }
                                    return suspendCancellableCoroutine { body ->
                                        body.invokeOnCancellation { call.cancel() }
                                        client.dispatcher.executorService.execute {
                                            try {
                                                val stream = received.body?.byteStream()
                                                    ?: throw BrainModelTransportException()
                                                val output = ByteArrayOutputStream()
                                                val chunk = ByteArray(8192)
                                                stream.use {
                                                    while (true) {
                                                        val count = it.read(
                                                            chunk,
                                                            0,
                                                            minOf(chunk.size, MAX_BODY_BYTES + 1 - output.size())
                                                        )
                                                        if (count < 0) break
                                                        if (output.size() + count > MAX_BODY_BYTES) {
                                                            throw BrainModelBodyTooLargeException()
                                                        }
                                                        output.write(chunk, 0, count)
                                                    }
                                                }
                                                if (body.isActive) body.resume(output.toByteArray())
                                            } catch (failure: Exception) {
                                                close()
                                                if (body.isActive) body.resumeWithException(
                                                    when (failure) {
                                                        is BrainModelBodyTooLargeException -> failure
                                                        is BrainModelTransportException -> failure
                                                        else -> BrainModelTransportException()
                                                    }
                                                )
                                            } finally {
                                                // The reader alone owns the response after the claim.
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

    private fun openMutation(
        origin: CanonicalOrigin,
        path: String,
        headers: Map<String, String>,
        body: String,
        timeoutMillis: Long,
        method: String,
        allowedPaths: Set<String>
    ): AuthenticatedHttpCall<BrainModelReadResponse> {
        val route = path.substringBefore('?')
        require(route in allowedPaths && MUTATION_METHODS[route] == method) {
            "Unsupported model mutation method or path"
        }
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) { "Model mutation body too large" }
        val url = origin.resolveHttp(path).toHttpUrl()
        require(url.querySize == 1 && url.queryParameterName(0) == "profile" &&
            url.queryParameterValue(0) != null) { "Invalid model mutation query" }
        val request = Request.Builder().url(url).method(
            method,
            body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        ).apply {
            headers.forEach { (key, value) -> header(key, value) }
            header("Content-Type", "application/json")
            header("Accept", "application/json")
        }.build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return newCallHandle(call)
    }

    private fun newCallHandle(call: Call): AuthenticatedHttpCall<BrainModelReadResponse> =
        object : AuthenticatedHttpCall<BrainModelReadResponse> {
            private val response = AtomicReference<okhttp3.Response?>()
            private val bodyState = AtomicInteger(0)

            override fun cancel() {
                call.cancel()
                if (bodyState.compareAndSet(0, 2)) response.getAndSet(null)?.close()
            }

            override suspend fun awaitHeaders(): BrainModelReadResponse =
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(BrainModelTransportException())
                        }

                        override fun onResponse(call: Call, received: okhttp3.Response) {
                            response.set(received)
                            if (bodyState.get() == 2) response.getAndSet(null)?.close()
                            val handle = object : BrainModelReadResponse {
                                override val statusCode: Int = received.code
                                override val headers: Map<String, List<String>> = received.headers.toMultimap()
                                override fun close() = cancel()

                                override suspend fun readBoundedBody(): ByteArray {
                                    if (!bodyState.compareAndSet(0, 1)) throw BrainModelTransportException()
                                    return suspendCancellableCoroutine { body ->
                                        body.invokeOnCancellation { call.cancel() }
                                        client.dispatcher.executorService.execute {
                                            try {
                                                val stream = received.body?.byteStream()
                                                    ?: throw BrainModelTransportException()
                                                val output = ByteArrayOutputStream()
                                                val chunk = ByteArray(8192)
                                                stream.use {
                                                    while (true) {
                                                        val count = it.read(chunk, 0, minOf(chunk.size, MAX_BODY_BYTES + 1 - output.size()))
                                                        if (count < 0) break
                                                        if (output.size() + count > MAX_BODY_BYTES) throw BrainModelTransportException()
                                                        output.write(chunk, 0, count)
                                                    }
                                                }
                                                if (body.isActive) body.resume(output.toByteArray())
                                            } catch (failure: Exception) {
                                                close()
                                                if (body.isActive) body.resumeWithException(
                                                    if (failure is BrainModelTransportException) failure else BrainModelTransportException()
                                                )
                                            } finally {
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

    companion object {
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        private val MODEL_READ_PATHS = setOf(
            "/api/model/options",
            "/api/model/info",
            "/api/model/auxiliary"
        )
        private val SKILLS_READ_PATHS = setOf("/api/skills")
        private val MUTATION_METHODS = mapOf(
            "/api/model/set" to "POST",
            "/api/config" to "PUT",
            "/api/skills/toggle" to "PUT"
        )
    }
}
