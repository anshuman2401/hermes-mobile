package com.hermes.bot.data.files

import com.hermes.bot.data.auth.*
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.local.AuthCredentialLease
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ManagedTransferReceipt(val lease: AuthCredentialLease, val bytes: Long = 0)
data class ManagedListReceipt(val lease: AuthCredentialLease, val listing: ManagedFileListing)

interface ManagedFileProtocolAdapter {
    suspend fun list(origin: CanonicalOrigin, lease: AuthCredentialLease, path: String?): ManagedListReceipt
    suspend fun upload(origin: CanonicalOrigin, lease: AuthCredentialLease, target: String,
        source: ManagedUploadSource, overwrite: Boolean): ManagedTransferReceipt
    suspend fun download(origin: CanonicalOrigin, lease: AuthCredentialLease, path: String, destination: File,
        maxBytes: Long): ManagedTransferReceipt
}

interface ManagedResponse : AuthenticatedHttpHeaders {
    suspend fun readBounded(limit: Int): ByteArray
    suspend fun copyBounded(destination: File, limit: Long): Long
}

interface ManagedFileTransport {
    fun open(url: String, method: String, headers: Map<String, String>,
        source: ManagedUploadSource? = null, target: String? = null,
        overwrite: Boolean = false): AuthenticatedHttpCall<ManagedResponse>
}

class OkHttpManagedFileTransport : ManagedFileTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .callTimeout(120, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 503 || response.code == 421) {
                response.close()
                throw IOException("Managed file request unavailable")
            }
            response
        }.build()

    override fun open(url: String, method: String, headers: Map<String, String>,
        source: ManagedUploadSource?, target: String?, overwrite: Boolean): AuthenticatedHttpCall<ManagedResponse> {
        val body = source?.let { uploadBody(it, target ?: throw IllegalArgumentException(), overwrite) }
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.method(method, body).build()
        val call = client.newCall(request)
        val responseRef = AtomicReference<Response?>()
        val cancelled = AtomicBoolean(false)
        return object : AuthenticatedHttpCall<ManagedResponse> {
            override fun cancel() { cancelled.set(true); call.cancel(); responseRef.getAndSet(null)?.close() }
            override suspend fun awaitHeaders(): ManagedResponse = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        responseRef.set(response)
                        if (cancelled.get()) { responseRef.getAndSet(null)?.close(); return }
                        val handle = object : ManagedResponse {
                            override val statusCode = response.code
                            override val headers = response.headers.toMultimap()
                            override fun close() { responseRef.getAndSet(null)?.close() }
                            override suspend fun readBounded(limit: Int): ByteArray = withContext(Dispatchers.IO) {
                                val stream = response.body?.byteStream() ?: throw IOException("Missing body")
                                val out = ByteArrayOutputStream()
                                val chunk = ByteArray(8192)
                                while (true) {
                                    val count = stream.read(chunk, 0, minOf(chunk.size, limit + 1 - out.size()))
                                    if (count < 0) break
                                    if (out.size() + count > limit) throw ManagedFileException(ManagedFileFailure.INVALID)
                                    out.write(chunk, 0, count)
                                }
                                out.toByteArray()
                            }
                            override suspend fun copyBounded(destination: File, limit: Long): Long = withContext(Dispatchers.IO) {
                                val stream = response.body?.byteStream() ?: throw IOException("Missing body")
                                var total = 0L
                                destination.outputStream().use { output ->
                                    val chunk = ByteArray(8192)
                                    while (true) {
                                        val count = stream.read(chunk)
                                        if (count < 0) break
                                        total += count
                                        if (total > limit) throw ManagedFileException(ManagedFileFailure.TOO_LARGE)
                                        output.write(chunk, 0, count)
                                    }
                                }
                                total
                            }
                        }
                        continuation.resume(handle) { _, value, _ -> value.close() }
                    }
                })
            }
        }
    }

    private fun uploadBody(source: ManagedUploadSource, target: String, overwrite: Boolean): RequestBody {
        val boundary = "hermes-android-${java.util.UUID.randomUUID()}"
        val type = "multipart/form-data; boundary=$boundary".toMediaType()
        val safeName = ManagedFilePaths.uploadName(source.name) ?: throw ManagedFileException(ManagedFileFailure.INVALID)
        return object : RequestBody() {
            override fun contentType(): MediaType = type
            override fun writeTo(sink: okio.BufferedSink) {
                fun field(name: String, value: String) {
                    sink.writeUtf8("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                field("path", target)
                field("overwrite", overwrite.toString())
                sink.writeUtf8("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${safeName.replace("\"", "_")}\"\r\n")
                sink.writeUtf8("Content-Type: application/octet-stream\r\n\r\n")
                var total = 0L
                source.open().use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        total += count
                        if (total > ManagedFileLimits.TRANSFER_BYTES) throw IOException("Upload exceeds limit")
                        sink.write(chunk, 0, count)
                    }
                }
                sink.writeUtf8("\r\n--$boundary--\r\n")
            }
        }
    }
}

class DefaultManagedFileProtocolAdapter(private val auth: AuthRepository,
    private val transport: ManagedFileTransport = OkHttpManagedFileTransport()) : ManagedFileProtocolAdapter {
    override suspend fun list(origin: CanonicalOrigin, lease: AuthCredentialLease, path: String?): ManagedListReceipt {
        if (path != null && !ManagedFilePaths.validPath(path)) throw ManagedFileException(ManagedFileFailure.INVALID)
        val url = origin.resolveHttp("/api/files").toHttpUrl().newBuilder().apply {
            path?.let { addQueryParameter("path", it) }
        }.build().toString()
        val exchange = exchange(origin, "/api/files", lease, url, "GET")
        exchange.response.use { response ->
            if (response.statusCode == 404) throw ManagedFileException(ManagedFileFailure.UNSUPPORTED)
            status(response.statusCode, false)
            val body = response.readBounded(ManagedFileLimits.LIST_BYTES).toString(Charsets.UTF_8)
            return ManagedListReceipt(exchange.receipt, ManagedFileListingParser.parse(body))
        }
    }

    override suspend fun upload(origin: CanonicalOrigin, lease: AuthCredentialLease, target: String,
        source: ManagedUploadSource, overwrite: Boolean): ManagedTransferReceipt {
        if (!ManagedFilePaths.validPath(target)) throw ManagedFileException(ManagedFileFailure.INVALID)
        if (source.size?.let { it < 0 || it > ManagedFileLimits.TRANSFER_BYTES } == true)
            throw ManagedFileException(ManagedFileFailure.TOO_LARGE)
        val path = "/api/files/upload-stream"
        // The transport receives the target as an explicit per-call value, never shared state.
        val exchange = try {
            auth.exchangeAuthenticated(origin, path, lease) { headers ->
                transport.open(origin.resolveHttp(path), "POST", headers, source, target, overwrite)
            }.getOrThrow()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw convert(failure, true) }
        exchange.response.use { response ->
            status(response.statusCode, true)
            val result = try { response.readBounded(64 * 1024).toString(Charsets.UTF_8) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { throw ManagedFileException(ManagedFileFailure.UNKNOWN) }
            if (GatewayJson.parseOrNull(result)?.get("ok")?.asBooleanOrNull() != true)
                throw ManagedFileException(ManagedFileFailure.UNKNOWN)
            return ManagedTransferReceipt(exchange.receipt)
        }
    }

    override suspend fun download(origin: CanonicalOrigin, lease: AuthCredentialLease, path: String,
        destination: File, maxBytes: Long): ManagedTransferReceipt {
        if (!ManagedFilePaths.validPath(path)) throw ManagedFileException(ManagedFileFailure.INVALID)
        val route = "/api/files/download"
        val url = origin.resolveHttp(route).toHttpUrl().newBuilder().addQueryParameter("path", path).build().toString()
        val exchange = exchange(origin, route, lease, url, "GET")
        exchange.response.use { response ->
            status(response.statusCode, false)
            return ManagedTransferReceipt(exchange.receipt, response.copyBounded(destination,
                minOf(maxBytes, ManagedFileLimits.TRANSFER_BYTES)))
        }
    }

    private suspend fun exchange(origin: CanonicalOrigin, path: String, lease: AuthCredentialLease,
        url: String, method: String): AuthenticatedExchange<ManagedResponse> = try {
        auth.exchangeAuthenticated(origin, path, lease) { headers -> transport.open(url, method, headers) }.getOrThrow()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { throw convert(failure, false) }

    private fun status(code: Int, write: Boolean) {
        val failure = when (code) {
            in 200..299 -> return
            401, 403 -> ManagedFileFailure.AUTH
            404 -> ManagedFileFailure.MISSING
            405, 501 -> ManagedFileFailure.UNSUPPORTED
            409 -> ManagedFileFailure.CONFLICT
            413 -> ManagedFileFailure.TOO_LARGE
            else -> if (write) ManagedFileFailure.UNKNOWN else ManagedFileFailure.NETWORK
        }
        throw ManagedFileException(failure)
    }
    private fun convert(failure: Exception, write: Boolean) = when (failure) {
        is ManagedFileException -> failure
        is AuthSessionRejectedException -> ManagedFileException(ManagedFileFailure.AUTH)
        is StaleAuthenticatedResponseException -> ManagedFileException(ManagedFileFailure.STALE)
        else -> ManagedFileException(if (write) ManagedFileFailure.UNKNOWN else ManagedFileFailure.NETWORK)
    }
}
