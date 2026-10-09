package com.hermes.bot.data.origin

import java.net.URI

/**
 * Immutable, fully validated canonical origin type.
 *
 * CHG-001 (ADR-0004): Adds HTTP scheme support for the Connect form's structured HTTP endpoint.
 * HTTP is the sole executable protocol for CHG-001. HTTPS remains parseable for legacy migration.
 *
 * ADR-0003 / RPR-003 security controls remain in force except the
 * https-only and no-cleartext clauses narrowly superseded for the CHG-001 connection path.
 *
 * Enforces:
 * - HTTP or HTTPS scheme (HTTPS parsed for legacy migration only, not executable from form)
 * - 1-to-1 originUrl / webSocketUrl mapping derived from scheme/host/port
 * - Rejection of userinfo, query, fragment, ASCII control chars, and Unicode bidi controls
 * - Rejection of malformed/encoded authority ambiguity
 * - Rejection of blank/empty hosts and unsafe/invalid ports
 * - Rejection of path traversals in subpaths
 * - Full component-wise parsing; NEVER uses naïve prefix-only checks
 * - IPv6 hosts are stored without brackets; URL representations add brackets
 */
data class CanonicalOrigin(
    val host: String,
    val port: Int,     // Effective port (1..65535)
    val rawPort: Int,  // -1 if default (for legacy path-prefix parse compatibility)
    val pathPrefix: String, // Normalized prefix, e.g. "" or "/hermes"
    val scheme: Scheme = Scheme.HTTP
) {

    enum class Scheme(val value: String) {
        HTTP("http"),
        HTTPS("https")
    }

    /** True if this is an IPv6 address (stored without brackets). */
    private val isIpv6: Boolean = host.contains(':')

    /** Host formatted for URL use: bracketed if IPv6. */
    private val urlHost: String = if (isIpv6) "[$host]" else host

    /** Canonical origin URL: http://host:port or https://host:port. */
    val originUrl: String = buildString {
        append(scheme.value)
        append("://")
        append(urlHost)
        if (rawPort != -1) {
            append(':')
            append(port)
        }
        append(pathPrefix)
    }

    /** Canonical WebSocket URL: ws://host:port or wss://host:port. */
    val webSocketUrl: String = buildString {
        val wsScheme = if (scheme == Scheme.HTTPS) "wss" else "ws"
        append(wsScheme)
        append("://")
        append(urlHost)
        if (rawPort != -1) {
            append(':')
            append(port)
        }
        append(pathPrefix)
    }


    /**
     * Resolves an HTTP subpath relative to this origin.
     * Guaranteed to stay within this canonical origin.
     */
    fun resolveHttp(subpath: String): String {
        val cleanSubpath = sanitizeSubpath(subpath)
        return "$originUrl$cleanSubpath"
    }

    /**
     * Resolves a WebSocket subpath relative to this origin.
     * Guaranteed to stay within this canonical origin.
     */
    fun resolveWs(subpath: String): String {
        val cleanSubpath = sanitizeSubpath(subpath)
        return "$webSocketUrl$cleanSubpath"
    }

    /**
     * Checks if another CanonicalOrigin has identical scheme, host, port, and path prefix.
     * HTTP and HTTPS are distinct origins.
     */
    fun isSameOrigin(other: CanonicalOrigin): Boolean {
        return scheme == other.scheme &&
            host.equals(other.host, ignoreCase = true) &&
            port == other.port &&
            pathPrefix == other.pathPrefix
    }

    /**
     * Origin-aware domain matching for cookies.
     * Returns true if the cookie domain is acceptable for this origin's host.
     */
    fun matchesCookieDomain(cookieDomain: String?): Boolean {
        if (cookieDomain.isNullOrBlank()) return true
        val cleanDomain = cookieDomain.trim().trimStart('.').lowercase()
        val lowerHost = host.lowercase()
        if (lowerHost == cleanDomain) return true
        if (lowerHost.endsWith(".$cleanDomain")) {
            if (!cleanDomain.contains('.')) return false
            return true
        }
        return false
    }

    override fun toString(): String = originUrl

    private fun sanitizeSubpath(subpath: String): String {
        val trimmed = subpath.trim()
        if (trimmed.contains("..") || trimmed.contains("./") || trimmed.contains("/.")) {
            throw IllegalArgumentException("Path traversal prohibited in subpath: $subpath")
        }
        if (trimmed.contains('#') || trimmed.contains('\\')) {
            throw IllegalArgumentException("Invalid characters in subpath: $subpath")
        }
        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    }

    companion object {
        private val BIDI_CONTROL_REGEX = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C\\uFEFF]")

        /**
         * Parses and strictly validates a raw origin string.
         * Accepts https:// origins (for legacy migration/compatibility).
         * Accepts http:// origins (CHG-001 deliberate HTTP support).
         * Fails closed on any invalid or hostile URL representation.
         */
        fun parse(raw: String?): Result<CanonicalOrigin> {
            if (raw.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Origin cannot be null or blank"))
            }

            val trimmed = raw.trim()

            // 1. Check for ASCII control characters and whitespace
            for (c in trimmed) {
                if (c.code in 0..31 || c.code == 127 || c.isWhitespace()) {
                    return Result.failure(
                        SecurityException("Origin contains forbidden ASCII control character or whitespace (code: ${c.code})")
                    )
                }
            }

            // 2. Check for Unicode Bidirectional controls
            if (BIDI_CONTROL_REGEX.containsMatchIn(trimmed)) {
                return Result.failure(
                    SecurityException("Origin contains forbidden Unicode bidirectional control characters")
                )
            }

            // 3. Prohibit fragment identifiers (#) and query parameters (?)
            if ('#' in trimmed) {
                return Result.failure(SecurityException("Origin must not contain a fragment identifier (#)"))
            }
            if ('?' in trimmed) {
                return Result.failure(SecurityException("Origin must not contain query parameters (?)"))
            }

            // 4. Prohibit userinfo (@) and backslashes
            if ('@' in trimmed) {
                return Result.failure(SecurityException("Origin must not contain userinfo credentials (@)"))
            }
            if ('\\' in trimmed) {
                return Result.failure(SecurityException("Origin must not contain backslashes (\\)"))
            }

            // 5. Parse as standard URI
            val uri = try {
                URI(trimmed)
            } catch (e: Exception) {
                return Result.failure(SecurityException("Malformed origin URI: ${e.message}", e))
            }

            if (!uri.isAbsolute) {
                return Result.failure(SecurityException("Origin must be an absolute URL"))
            }

            // 6. Scheme: accept http or https only
            val schemeStr = uri.scheme?.lowercase()
            val scheme = when (schemeStr) {
                "https" -> Scheme.HTTPS
                "http" -> Scheme.HTTP
                "ws", "wss" -> return Result.failure(SecurityException("Use http:// or https:// scheme; ws/wss are derived automatically."))
                else -> return Result.failure(SecurityException("Prohibited scheme '$schemeStr'. Use http:// or https://."))
            }

            // 7. Verify URI components
            if (uri.rawUserInfo != null) {
                return Result.failure(SecurityException("Origin must not contain userinfo"))
            }
            if (uri.rawQuery != null) {
                return Result.failure(SecurityException("Origin must not contain query parameters"))
            }
            if (uri.rawFragment != null) {
                return Result.failure(SecurityException("Origin must not contain a fragment"))
            }

            // 8. Authority and host verification
            val rawAuthority = uri.rawAuthority
            if (rawAuthority.isNullOrBlank()) {
                return Result.failure(SecurityException("Origin authority cannot be blank"))
            }
            if ('%' in rawAuthority) {
                return Result.failure(SecurityException("Percent-encoded characters in authority are prohibited"))
            }

            val host = uri.host
            if (host.isNullOrBlank()) {
                return Result.failure(SecurityException("Origin host cannot be blank"))
            }
            val lowerHost = host.lowercase().removeSurrounding("[", "]")
            if (lowerHost.contains(' ') || lowerHost.startsWith('.') || lowerHost.endsWith('.') || lowerHost.contains("..")) {
                return Result.failure(SecurityException("Invalid hostname format: $host"))
            }

            // 9. Port validation
            val uriPort = uri.port
            if (uriPort == 0) {
                return Result.failure(SecurityException("Port 0 is prohibited and unsafe"))
            }
            if (uriPort < -1 || uriPort > 65535) {
                return Result.failure(SecurityException("Port $uriPort is outside the valid range 1..65535"))
            }
            val effectivePort = if (uriPort == -1) {
                when (scheme) {
                    Scheme.HTTPS -> 443
                    Scheme.HTTP -> 80
                }
            } else uriPort

            // 10. Path prefix validation
            val rawPath = uri.rawPath ?: ""
            if (rawPath.contains("..") || rawPath.contains("./") || rawPath.contains("/.")) {
                return Result.failure(SecurityException("Path traversal sequence prohibited in origin path prefix"))
            }
            val normalizedPath = when {
                rawPath.isEmpty() || rawPath == "/" -> ""
                else -> {
                    val p = rawPath.trimEnd('/')
                    if (!p.startsWith('/')) "/$p" else p
                }
            }

            return Result.success(
                CanonicalOrigin(
                    host = lowerHost,
                    port = effectivePort,
                    rawPort = uriPort,
                    pathPrefix = normalizedPath,
                    scheme = scheme
                )
            )
        }

        fun parseOrThrow(raw: String?): CanonicalOrigin = parse(raw).getOrThrow()
    }
}
