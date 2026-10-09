package com.hermes.bot.data.auth

import com.hermes.bot.data.gateway.okhttp.OkHttpGatewayTransport
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

import com.hermes.bot.data.diagnostics.DiagnosticEvent
import com.hermes.bot.data.diagnostics.DiagnosticRing
import com.hermes.bot.data.diagnostics.DiagnosticSanitizer

/**
 * Production OkHttp-backed implementation of AuthHttpTransport.
 * CHG-001 (ADR-0004): Allows http and https schemes for canonical origins.
 * Prohibits cross-origin or scheme-changing redirects; enforces exact-origin routing.
 */
class OkHttpAuthTransport(
    client: OkHttpClient = OkHttpGatewayTransport.defaultClient(),
    private val diagnosticRing: DiagnosticRing? = null
) : AuthHttpTransport {

    // Disable automatic redirects to prevent cross-origin credential movement per ADR-0003 & RPR-003
    private val client: OkHttpClient = client.newBuilder()
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): HttpResponse = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val pathShape = DiagnosticRing.sanitizePathShape(url)
        val parsedUrl = url.toHttpUrl()
        val canonicalOrigin = CanonicalOrigin.parse(
            "${parsedUrl.scheme}://${parsedUrl.host}:${parsedUrl.port}"
        ).getOrElse {
            val ex = SecurityException("Rejected request URL because its origin is not canonical.", it)
            val durationMs = System.currentTimeMillis() - startMs
            val err = DiagnosticSanitizer.sanitize(ex.javaClass.simpleName + ": " + (ex.message ?: ""))
            diagnosticRing?.record(
                DiagnosticEvent.Http.RequestCompleted(
                    method = method,
                    urlPathShape = pathShape,
                    statusCode = 0,
                    durationMs = durationMs,
                    error = err
                )
            )
            throw ex
        }
        // Query parameters cannot alter scheme, host, or port, so a
        // query-bearing URL remains same-origin (the scoped session list
        // GET /api/profiles/sessions?profile=<name>&limit=<n> is the only
        // query-bearing call in the app). Scheme equality, fragment
        // rejection, and redirect fail-closed remain enforced.
        if (canonicalOrigin.scheme.value != parsedUrl.scheme || parsedUrl.fragment != null) {
            val ex = SecurityException("Rejected request URL because its origin is not canonical.")
            val durationMs = System.currentTimeMillis() - startMs
            val err = DiagnosticSanitizer.sanitize(ex.javaClass.simpleName + ": " + (ex.message ?: ""))
            diagnosticRing?.record(
                DiagnosticEvent.Http.RequestCompleted(
                    method = method,
                    urlPathShape = pathShape,
                    statusCode = 0,
                    durationMs = durationMs,
                    error = err
                )
            )
            throw ex
        }

        val requestBuilder = Request.Builder().url(parsedUrl)
        for ((k, v) in headers) {
            requestBuilder.header(k, v)
        }

        val requestBody = if (method == "POST" || method == "PUT") {
            (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        } else null

        requestBuilder.method(method, requestBody)

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                // All redirects fail closed; credentials cannot be forwarded to a follow-up request.
                if (response.isRedirect) {
                    val ex = AuthOriginPolicyRejectedException()
                    val durationMs = System.currentTimeMillis() - startMs
                    val err = DiagnosticSanitizer.sanitize(ex.javaClass.simpleName + ": " + (ex.message ?: ""))
                    diagnosticRing?.record(
                        DiagnosticEvent.Http.RequestCompleted(
                            method = method,
                            urlPathShape = pathShape,
                            statusCode = response.code,
                            durationMs = durationMs,
                            error = err
                        )
                    )
                    throw ex
                }

                val durationMs = System.currentTimeMillis() - startMs
                diagnosticRing?.record(
                    DiagnosticEvent.Http.RequestCompleted(
                        method = method,
                        urlPathShape = pathShape,
                        statusCode = response.code,
                        durationMs = durationMs,
                        error = null
                    )
                )

                HttpResponse(
                    statusCode = response.code,
                    headers = response.headers.toMultimap(),
                    body = response.body?.string() ?: ""
                )
            }
        } catch (e: Exception) {
            if (e !is SecurityException) {
                val durationMs = System.currentTimeMillis() - startMs
                val err = DiagnosticSanitizer.sanitize(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
                diagnosticRing?.record(
                    DiagnosticEvent.Http.RequestCompleted(
                        method = method,
                        urlPathShape = pathShape,
                        statusCode = 0,
                        durationMs = durationMs,
                        error = err
                    )
                )
            }
            throw e
        }
    }
}
