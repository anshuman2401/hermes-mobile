package com.hermes.bot.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import com.hermes.bot.data.diagnostics.DiagnosticEvent
import com.hermes.bot.data.diagnostics.DiagnosticRing
import com.hermes.bot.data.diagnostics.DiagnosticSanitizer

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = ""
)

interface AuthHttpTransport {
    suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): HttpResponse
}

/**
 * Production HTTPS-only implementation using standard HttpsURLConnection.
 * Enforces strict HTTPS origin and trust chain verification.
 */
class DefaultAuthHttpTransport(
    private val diagnosticRing: DiagnosticRing? = null
) : AuthHttpTransport {

    override suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): HttpResponse = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val pathShape = DiagnosticRing.sanitizePathShape(url)
        val parsedUrl = try {
            val p = URL(url)
            if (p.protocol.lowercase() != "https") {
                val ex = SecurityException("Insecure protocol rejected: HTTPS required. Origin: $url")
                val durationMs = System.currentTimeMillis() - startMs
                diagnosticRing?.record(
                    DiagnosticEvent.Http.RequestCompleted(
                        method = method,
                        urlPathShape = pathShape,
                        statusCode = 0,
                        durationMs = durationMs,
                        error = DiagnosticSanitizer.sanitize(ex.javaClass.simpleName + ": " + (ex.message ?: ""))
                    )
                )
                throw ex
            }
            p
        } catch (e: Exception) {
            if (e !is SecurityException) {
                val durationMs = System.currentTimeMillis() - startMs
                diagnosticRing?.record(
                    DiagnosticEvent.Http.RequestCompleted(
                        method = method,
                        urlPathShape = pathShape,
                        statusCode = 0,
                        durationMs = durationMs,
                        error = DiagnosticSanitizer.sanitize(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
                    )
                )
            }
            throw e
        }

        val conn = parsedUrl.openConnection() as HttpsURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = false

        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }

        if (body != null && (method == "POST" || method == "PUT")) {
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }
        }

        val code = conn.responseCode
        val headerFields = conn.headerFields ?: emptyMap()

        val responseBody = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            conn.disconnect()
        }

        val durationMs = System.currentTimeMillis() - startMs
        diagnosticRing?.record(
            DiagnosticEvent.Http.RequestCompleted(
                method = method,
                urlPathShape = pathShape,
                statusCode = code,
                durationMs = durationMs,
                error = null
            )
        )

        HttpResponse(
            statusCode = code,
            headers = headerFields,
            body = responseBody
        )
    }
}
