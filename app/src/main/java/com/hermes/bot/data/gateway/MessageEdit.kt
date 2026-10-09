package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.json.*

/** Memory-only provenance. A renderer id or matching text is never a rewind address. */
data class EditableMessage(val rowId: Long) {
    init { require(rowId > 0) }
    override fun toString() = "EditableMessage([REDACTED])"
}

object MessageEditPolicy {
    const val MAX_TEXT_CHARS = 65_536
    fun plainText(text: String): Boolean = text.isNotBlank() && text.length <= MAX_TEXT_CHARS &&
        !text.trimStart().startsWith("/") && !text.contains("[IMPORTANT:") &&
        !text.contains("[Image", ignoreCase = true) && !text.contains("[Attached", ignoreCase = true) &&
        !text.contains("[File", ignoreCase = true) && !text.contains("<skill", ignoreCase = true) &&
        !text.contains("data:image", ignoreCase = true) && !text.contains("@file:", ignoreCase = true) &&
        !text.contains("![") && !text.contains("MEDIA:", ignoreCase = true)

    fun target(obj: Map<String, JsonValue>, rowId: Long?, rendered: String, truncated: Boolean = false): EditableMessage? {
        if (rowId == null || rowId <= 0 || truncated || !plainText(rendered)) return null
        // Compact text projections can omit image parts; only complete raw content proves plain text.
        val raw = (obj["content"] as? JsonString)?.value ?: return null
        if (raw != rendered || GatewayRedactor.redactString(raw) != raw) return null
        if (obj["display_kind"]?.asStringOrNull()?.let { it !in setOf("", "user", "text") } == true ||
            obj["display_content"]?.asStringOrNull()?.let { it != raw } == true ||
            obj["truncated"]?.asBooleanOrNull() == true ||
            listOf("images", "attachments", "files", "skill", "skill_invocation", "display_metadata", "_compressed_summary").any {
                val value = obj[it]
                value != null && value !is JsonNull && !(value is JsonArray && value.list.isEmpty())
            }) return null
        return EditableMessage(rowId)
    }
}
