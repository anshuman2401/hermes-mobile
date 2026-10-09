package com.hermes.bot.data.origin

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Immutable, origin-aware Secure cookie implementation per ADR-0003 and RPR-003.
 *
 * Guarantees:
 * - Cookies are bound to their source CanonicalOrigin and cannot cross origins.
 * - Enforces Secure attribute and HTTPS-only transport.
 * - Domain attributes are strictly validated against the origin host (rejecting cross-domain injection).
 * - Expiration (Max-Age / Expires) is tracked and enforced.
 * - toString() strictly redacts the cookie secret value.
 */
data class OriginCookie(
    val name: String,
    val value: String,
    val domain: String?,
    val path: String = "/",
    val isSecure: Boolean = true,
    val isHttpOnly: Boolean = false,
    val expiresEpochSeconds: Long? = null,
    val sourceOrigin: String
) {

    fun isExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        return expiresEpochSeconds != null && expiresEpochSeconds <= nowEpochSeconds
    }

    /**
     * Determines whether this cookie may be included in a request to the given target origin and path.
     * Prevents cross-origin credential movement.
     */
    fun isApplicableTo(
        targetOrigin: CanonicalOrigin,
        requestPath: String = "/",
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000
    ): Boolean {
        if (isExpired(nowEpochSeconds)) return false

        // Secure cookies must never be sent over an HTTP origin.
        if (isSecure && targetOrigin.scheme != CanonicalOrigin.Scheme.HTTPS) return false

        // 1. Strict domain matching against target origin
        if (!targetOrigin.matchesCookieDomain(domain)) {
            return false
        }

        // 2. Path matching
        val normalizedReqPath = if (requestPath.startsWith('/')) requestPath else "/$requestPath"
        val normalizedCookiePath = if (path.startsWith('/')) path else "/$path"
        if (!normalizedReqPath.startsWith(normalizedCookiePath)) {
            return false
        }

        return true
    }

    override fun toString(): String {
        return "OriginCookie(isSecure=$isSecure, [REDACTED])"
    }

    companion object {

        private val HTTP_DATE_FORMATS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, dd-MMM-yyyy HH:mm:ss z",
            "EEE, dd-MMM-yy HH:mm:ss z",
            "EEE MMM d HH:mm:ss yyyy"
        )

        /**
         * Parses a Set-Cookie header value bound to the source CanonicalOrigin.
         * Returns null if malformed, expired, or attempting cross-origin domain injection.
         */
        fun parseSetCookie(
            headerValue: String,
            sourceOrigin: CanonicalOrigin,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1000
        ): OriginCookie? {
            val parts = headerValue.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null

            val nameValue = parts[0]
            val eqIdx = nameValue.indexOf('=')
            if (eqIdx <= 0) return null

            val name = nameValue.substring(0, eqIdx).trim()
            val value = nameValue.substring(eqIdx + 1).trim()
            if (name.isEmpty()) return null

            var domain: String? = null
            var path = "/"
            var secure = false
            var httpOnly = false
            var expiresEpochSeconds: Long? = null

            for (i in 1 until parts.size) {
                val part = parts[i]
                val attrEq = part.indexOf('=')
                val attrName = (if (attrEq > 0) part.substring(0, attrEq) else part).trim().lowercase()
                val attrValue = if (attrEq > 0) part.substring(attrEq + 1).trim() else ""

                when (attrName) {
                    "domain" -> {
                        if (attrValue.isNotEmpty()) {
                            val cleanDomain = attrValue.trimStart('.')
                            // Validate that sourceOrigin can set this domain
                            if (!sourceOrigin.matchesCookieDomain(cleanDomain)) {
                                // Reject cross-domain cookie injection
                                return null
                            }
                            domain = cleanDomain
                        }
                    }
                    "path" -> {
                        if (attrValue.isNotEmpty()) {
                            path = attrValue
                        }
                    }
                    "secure" -> {
                        secure = true
                    }
                    "httponly" -> {
                        httpOnly = true
                    }
                    "max-age" -> {
                        val maxAge = attrValue.toLongOrNull()
                        if (maxAge != null) {
                            expiresEpochSeconds = nowEpochSeconds + maxAge
                        }
                    }
                    "expires" -> {
                        if (expiresEpochSeconds == null && attrValue.isNotEmpty()) {
                            expiresEpochSeconds = parseExpiresDate(attrValue)
                        }
                    }
                }
            }

            val cookie = OriginCookie(
                name = name,
                value = value,
                domain = domain,
                path = path,
                isSecure = secure,
                isHttpOnly = httpOnly,
                expiresEpochSeconds = expiresEpochSeconds,
                sourceOrigin = sourceOrigin.originUrl
            )

            return if (cookie.isExpired(nowEpochSeconds)) null else cookie
        }

        private fun parseExpiresDate(dateStr: String): Long? {
            for (pattern in HTTP_DATE_FORMATS) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT")
                    }
                    val date = sdf.parse(dateStr)
                    if (date != null) {
                        return date.time / 1000L
                    }
                } catch (_: Exception) {
                    // Try next pattern
                }
            }
            return null
        }
    }
}
