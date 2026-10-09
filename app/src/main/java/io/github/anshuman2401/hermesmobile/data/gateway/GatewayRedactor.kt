package io.github.anshuman2401.hermesmobile.data.gateway

/**
 * Centralized secret and credential redaction utility.
 * Sanitizes ticket-like, token-like, and credential-bearing strings and map payloads.
 */
object GatewayRedactor {

    private val CREDENTIAL_KEY_REGEX = Regex(
        "(?i).*(ticket|token|password|authorization|cookie|secret|session).*"
    )

    private val TICKET_PREFIX_REGEX = Regex(
        """hermes-gateway-ticket\.[^\s,"'\)>]+"""
    )

    private val KV_SECRET_REGEX = Regex(
        """(?i)(\b(?:ticket|token|password|passwd|pwd|authorization|cookie|secret|session|hermes_session)=)["']?([^"'\s,&;\)>]+)["']?"""
    )

    private val BEARER_REGEX = Regex(
        """(?i)\b(bearer\s+)[^\s,"'\)>]+"""
    )

    private val COMMON_KEY_PREFIX_REGEX = Regex(
        "\\b(sk-[a-zA-Z0-9_-]{16,}|ghp_[a-zA-Z0-9]{20,}|gho_[a-zA-Z0-9]{20,}|xoxb-[a-zA-Z0-9]{10,})\\b"
    )

    fun isCredentialKey(key: String): Boolean {
        return CREDENTIAL_KEY_REGEX.matches(key)
    }

    fun redactString(text: String): String {
        var redacted = text
        redacted = TICKET_PREFIX_REGEX.replace(redacted, "hermes-gateway-ticket.[REDACTED]")
        redacted = KV_SECRET_REGEX.replace(redacted) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        redacted = BEARER_REGEX.replace(redacted) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        redacted = COMMON_KEY_PREFIX_REGEX.replace(redacted, "[REDACTED]")
        return redacted
    }

    fun redactMap(map: Map<*, *>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((k, v) in map) {
            val keyStr = k.toString()
            if (isCredentialKey(keyStr)) {
                result[keyStr] = "[REDACTED]"
            } else {
                result[keyStr] = redactAny(v)
            }
        }
        return result
    }

    fun redactAny(value: Any?): Any? {
        return when (value) {
            null -> null
            is String -> redactString(value)
            is Map<*, *> -> redactMap(value)
            is List<*> -> value.map { redactAny(it) }
            else -> value
        }
    }
}
