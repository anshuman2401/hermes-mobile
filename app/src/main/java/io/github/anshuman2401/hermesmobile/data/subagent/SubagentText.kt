package io.github.anshuman2401.hermesmobile.data.subagent

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayRedactor

/** Mirrors the chat's 256 code-point preview / 16 KiB result budgets without a data-to-UI dependency. */
object SubagentText {
    fun preview(value: String): String {
        val redacted = GatewayRedactor.redactString(value)
        val count = redacted.codePointCount(0, redacted.length)
        return if (count <= 256) redacted else redacted.substring(0, redacted.offsetByCodePoints(0, 255)) + "…"
    }
    fun tail(value: String): String {
        val redacted = GatewayRedactor.redactString(value)
        var index = 0; var bytes = 0
        while (index < redacted.length) {
            val cp = redacted.codePointAt(index)
            val size = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
            if (bytes + size > 16384) break
            bytes += size; index += Character.charCount(cp)
        }
        return redacted.substring(0, index)
    }
}
