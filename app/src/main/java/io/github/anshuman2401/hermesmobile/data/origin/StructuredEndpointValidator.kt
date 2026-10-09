package io.github.anshuman2401.hermesmobile.data.origin

import java.net.URI

/**
 * CHG-001: Validates structured host/port inputs from the Connect form and constructs
 * CanonicalOrigin. Accepts hostname, IPv4, or bare/bracketed IPv6.
 *
 * Rejects: schemes, embedded ports, paths, query, fragment, userinfo, backslashes,
 * percent authority, whitespace/control/bidi, malformed names/IPs, IPv6 zones,
 * and invalid/non-ASCII/non-decimal ports.
 *
 * Server name is local metadata only and never affects network requests.
 */
object StructuredEndpointValidator {

    private val BIDI_CONTROL_REGEX = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C\\uFEFF]")
    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+\\-.]*://")
    @Suppress("unused")
    private val EMBEDDED_PORT_REGEX = Regex(".*:\\d+$")

    sealed class ValidationResult {
        data class Success(val origin: CanonicalOrigin) : ValidationResult()
        data class HostError(val message: String) : ValidationResult()
        data class PortError(val message: String) : ValidationResult()
    }

    /**
     * Parse structured protocol/host/port into a CanonicalOrigin.
     * Only HTTP is the executable protocol for CHG-001.
     */
    fun parse(protocol: ConnectProtocol, hostDraft: String, portDraft: String): ValidationResult {
        // Validate host first
        val hostResult = validateHost(hostDraft)
        if (hostResult is ValidationResult.HostError) return hostResult
        val canonicalHost = (hostResult as ValidationResult.Success).origin.host

        // Validate port
        val portResult = validatePort(portDraft)
        if (portResult is ValidationResult.PortError) return portResult
        val port = (portResult as ValidationResult.Success).origin.port

        // Build canonical origin
        return buildOrigin(protocol, canonicalHost, port)
    }

    private fun validateHost(hostDraft: String): ValidationResult {
        if (hostDraft.isBlank()) {
            return ValidationResult.HostError("Enter a hostname or IP address.")
        }

        if (hostDraft != hostDraft.trim()) {
            return ValidationResult.HostError("Host must not contain whitespace.")
        }
        val trimmed = hostDraft

        // Reject ASCII control chars and whitespace within the string
        for (c in trimmed) {
            if (c.code in 0..31 || c.code == 127) {
                return ValidationResult.HostError("Host contains forbidden control characters.")
            }
            if (c.isWhitespace()) {
                return ValidationResult.HostError("Host must not contain whitespace.")
            }
        }

        // Reject bidi control chars
        if (BIDI_CONTROL_REGEX.containsMatchIn(trimmed)) {
            return ValidationResult.HostError("Host contains forbidden bidirectional control characters.")
        }

        // Reject schemes
        if (SCHEME_REGEX.containsMatchIn(trimmed)) {
            return ValidationResult.HostError("Enter a hostname or IP address without http://, a port, or a path.")
        }

        // Reject userinfo
        if ('@' in trimmed) {
            return ValidationResult.HostError("Host must not contain userinfo credentials (@).")
        }

        // Reject backslashes
        if ('\\' in trimmed) {
            return ValidationResult.HostError("Host must not contain backslashes.")
        }

        // Reject query and fragment
        if ('?' in trimmed || '#' in trimmed) {
            return ValidationResult.HostError("Host must not contain query or fragment characters.")
        }

        // Reject percent-encoded authority ambiguity
        if ('%' in trimmed) {
            return ValidationResult.HostError("Host must not contain percent-encoded characters.")
        }

        // Check for embedded port pattern (host:port) — reject
        // But allow IPv6 brackets like [::1] — those don't contain embedded port in host field
        val isIpv6Bracketed = trimmed.startsWith('[') && trimmed.endsWith(']')
        if (!isIpv6Bracketed) {
            // Reject host:port pattern
            if (':' in trimmed) {
                return ValidationResult.HostError("Enter a hostname or IP address without a port. Use the Port field below.")
            }
        }

        // Validate as IPv6 (bare or bracketed)
        if (isIpv6Bracketed || isLikelyIpv6(trimmed)) {
            return validateIpv6(trimmed)
        }

        // Validate as IPv4 or DNS hostname
        return if (looksLikeIpv4(trimmed)) {
            validateIpv4(trimmed)
        } else {
            validateDnsHostname(trimmed)
        }
    }

    private fun isLikelyIpv6(s: String): Boolean {
        // An IPv6 literal has multiple colons
        return s.count { it == ':' } >= 2
    }

    private fun validateIpv6(raw: String): ValidationResult {
        // Unwrap brackets if present
        val bare = if (raw.startsWith('[') && raw.endsWith(']')) {
            raw.substring(1, raw.length - 1)
        } else raw

        // Reject zone IDs (% separator)
        if ('%' in bare) {
            return ValidationResult.HostError("IPv6 zone IDs are not supported in v1. Enter the address without %zone.")
        }

        // Validate by trying URI parsing with brackets
        return try {
            val uri = URI("http://[$bare]:9119/")
            val parsedHost = uri.host
            if (parsedHost.isNullOrBlank()) {
                ValidationResult.HostError("Invalid IPv6 address.")
            } else {
                // parsedHost from URI will not have brackets, store as-is (bare)
                ValidationResult.Success(CanonicalOrigin(host = bare.lowercase(), port = 9119, rawPort = 9119, pathPrefix = "", scheme = CanonicalOrigin.Scheme.HTTP))
            }
        } catch (e: Exception) {
            ValidationResult.HostError("Invalid IPv6 address: ${e.message?.take(80)}")
        }
    }

    private fun looksLikeIpv4(s: String): Boolean {
        return s.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))
    }

    private fun validateIpv4(raw: String): ValidationResult {
        val parts = raw.split('.')
        if (parts.size != 4) return ValidationResult.HostError("Invalid IPv4 address.")
        for (part in parts) {
            val n = part.toIntOrNull() ?: return ValidationResult.HostError("Invalid IPv4 address: '$part' is not a decimal number.")
            if (n < 0 || n > 255) return ValidationResult.HostError("Invalid IPv4 address: octet $n is out of range 0-255.")
        }
        val canonical = parts.joinToString(".") { it.toInt().toString() }
        return ValidationResult.Success(CanonicalOrigin(host = canonical, port = 9119, rawPort = 9119, pathPrefix = "", scheme = CanonicalOrigin.Scheme.HTTP))
    }

    private fun validateDnsHostname(raw: String): ValidationResult {
        // Reject paths
        if ('/' in raw) {
            return ValidationResult.HostError("Enter a hostname or IP address without a path.")
        }

        val lowercase = raw.lowercase()

        // Must not start or end with dot, no empty labels (double dots)
        if (lowercase.startsWith('.') || lowercase.endsWith('.') || lowercase.contains("..")) {
            return ValidationResult.HostError("Invalid hostname: labels must not be empty.")
        }

        // Validate labels
        val labels = lowercase.split('.')
        for (label in labels) {
            if (label.isEmpty()) {
                return ValidationResult.HostError("Invalid hostname: empty label.")
            }
            // Each label: only alphanumeric and hyphens; must not start/end with hyphen
            if (!label.matches(Regex("[a-z0-9][a-z0-9\\-]*[a-z0-9]|[a-z0-9]"))) {
                return ValidationResult.HostError("Invalid hostname label: '$label'. Labels must contain only letters, digits, and hyphens.")
            }
            if (label.startsWith('-') || label.endsWith('-')) {
                return ValidationResult.HostError("Invalid hostname label '$label': must not start or end with a hyphen.")
            }
        }

        return ValidationResult.Success(CanonicalOrigin(host = lowercase, port = 9119, rawPort = 9119, pathPrefix = "", scheme = CanonicalOrigin.Scheme.HTTP))
    }

    private fun validatePort(portDraft: String): ValidationResult {
        if (portDraft.isBlank()) {
            return ValidationResult.PortError("Enter a port from 1 to 65535.")
        }

        if (portDraft != portDraft.trim()) {
            return ValidationResult.PortError("Enter a port from 1 to 65535.")
        }
        val trimmed = portDraft

        // Only ASCII decimal digits allowed
        if (!trimmed.all { it in '0'..'9' }) {
            return ValidationResult.PortError("Enter a port from 1 to 65535.")
        }

        val port = trimmed.toIntOrNull() ?: return ValidationResult.PortError("Enter a port from 1 to 65535.")

        if (port < 1 || port > 65535) {
            return ValidationResult.PortError("Enter a port from 1 to 65535.")
        }

        return ValidationResult.Success(CanonicalOrigin(host = "", port = port, rawPort = port, pathPrefix = "", scheme = CanonicalOrigin.Scheme.HTTP))
    }

    private fun buildOrigin(protocol: ConnectProtocol, canonicalHost: String, port: Int): ValidationResult {
        // bracket IPv6 if needed for URL
        val scheme = when (protocol) {
            ConnectProtocol.HTTP -> CanonicalOrigin.Scheme.HTTP
        }
        return ValidationResult.Success(
            CanonicalOrigin(
                host = canonicalHost,
                port = port,
                rawPort = port,
                pathPrefix = "",
                scheme = scheme
            )
        )
    }
}

enum class ConnectProtocol { HTTP }
