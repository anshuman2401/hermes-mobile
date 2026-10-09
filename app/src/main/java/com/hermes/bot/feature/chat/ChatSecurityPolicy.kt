package com.hermes.bot.feature.chat

import java.net.URI
import java.util.Locale

/**
 * Security, sanitization, and redaction policy for the Chat surface per ARCH-001 / ADR-0003.
 * Enforces strict external URI validation, defense-in-depth secret pattern redaction,
 * and HTML literal escaping to prevent injection surfaces or client-side execution.
 */
object ChatSecurityPolicy {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    private val BEARER_REGEX = Regex("Bearer\\s+[A-Za-z0-9_\\-\\.]+", RegexOption.IGNORE_CASE)
    private val PASSWORD_REGEX = Regex("(password|passwd|pwd)[\"':=\\s]+([^\"'\\s,;}{]+)", RegexOption.IGNORE_CASE)
    private val SECRET_KEY_REGEX = Regex("(?i)(api[_-]?key|secret|token|auth_token)[\"':=\\s]+([^\"'\\s,;}{]+)")
    private val COMMON_KEY_PREFIX_REGEX = Regex("\\b(sk-[a-zA-Z0-9]{20,}|ghp_[a-zA-Z0-9]{20,}|gho_[a-zA-Z0-9]{20,}|xoxb-[a-zA-Z0-9]{10,})\\b")

    /**
     * Validates whether a candidate URL is a safe external HTTP or HTTPS link.
     * Strictly rejects file:, content:, intent:, javascript:, data:, and all custom command schemes.
     */
    fun isValidExternalUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // Fast rejection for dangerous schemes
        if (lower.startsWith("javascript:") ||
            lower.startsWith("data:") ||
            lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("intent:") ||
            lower.startsWith("vbscript:")
        ) {
            return false
        }

        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            scheme != null && scheme in ALLOWED_SCHEMES && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns a validated URL string, or null if the URL is invalid or unsafe.
     */
    fun sanitizeUrl(url: String?): String? {
        return if (isValidExternalUrl(url)) url?.trim() else null
    }

    /**
     * Defense-in-depth secret redaction for tool arguments, results, and raw payloads.
     */
    fun redactSensitiveData(text: String?): String {
        if (text.isNullOrEmpty()) return ""

        var redacted = text
        redacted = BEARER_REGEX.replace(redacted, "Bearer [REDACTED]")
        redacted = PASSWORD_REGEX.replace(redacted) { matchResult ->
            val key = matchResult.groupValues[1]
            "$key: [REDACTED]"
        }
        redacted = SECRET_KEY_REGEX.replace(redacted) { matchResult ->
            val key = matchResult.groupValues[1]
            "$key: [REDACTED]"
        }
        redacted = COMMON_KEY_PREFIX_REGEX.replace(redacted, "[REDACTED]")
        return redacted
    }

    /**
     * Escapes HTML entities so raw markup (e.g. <script>, <iframe>) is rendered as plain inert text.
     */
    fun escapeHtml(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    /**
     * Frozen 1 MB crash-guard ceiling on a single tool output per PRD v2 (owner decision #2).
     */
    const val MAX_TOOL_OUTPUT_BYTES = 1024 * 1024

    /**
     * Formats tool arguments into an untruncated string per PRD v2 (owner decision #2 & #5).
     * Retains full text without display-level security redaction (wire-level redaction remains).
     */
    fun formatToolArgs(args: Map<String, Any?>?): String {
        if (args.isNullOrEmpty()) return "(no arguments)"
        val sb = StringBuilder()
        formatRawValue(args, sb)
        return sb.toString()
    }

    /**
     * Formats a tool result into an untruncated string per PRD v2 (owner decision #2 & #5),
     * capped only by the 1 MB technical crash-guard ceiling with a visible omitted label.
     */
    fun formatToolResult(result: Any?): String {
        if (result == null) return "(empty result)"
        val text = if (result is String) {
            result
        } else {
            val sb = StringBuilder()
            formatRawValue(result, sb)
            sb.toString()
        }
        return applyCrashGuardCap(text)
    }

    private fun applyCrashGuardCap(text: String): String {
        val totalBytes = BoundedPayloadMapper.utf8ByteCount(text)
        if (totalBytes <= MAX_TOOL_OUTPUT_BYTES) {
            return text
        }
        val (keptBytes, keptIndex) = utf8ScalarWalk(text, MAX_TOOL_OUTPUT_BYTES)
        val omittedBytes = totalBytes - keptBytes
        val omittedKb = (omittedBytes + 1023) / 1024
        return text.substring(0, keptIndex) + "\n[output too large, $omittedKb KB omitted]"
    }

    private fun utf8ScalarWalk(text: String, byteLimit: Int): Pair<Int, Int> {
        var i = 0
        var bytes = 0
        while (i < text.length) {
            val c = text[i]
            val cpBytes: Int
            val advance: Int
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    cpBytes = 4; advance = 2
                } else {
                    cpBytes = 3; advance = 1
                }
            } else if (Character.isLowSurrogate(c)) {
                cpBytes = 3; advance = 1
            } else {
                cpBytes = when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
                advance = 1
            }
            if (bytes + cpBytes > byteLimit) break
            bytes += cpBytes
            i += advance
        }
        return bytes to i
    }

    private fun formatRawValue(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> {
                sb.append('"').append(value).append('"')
            }
            is Number, is Boolean -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (!first) sb.append(", ")
                    first = false
                    sb.append(k?.toString() ?: "null").append(": ")
                    formatRawValue(v, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(", ")
                    first = false
                    formatRawValue(v, sb)
                }
                sb.append(']')
            }
            else -> sb.append(value.toString())
        }
    }
}
