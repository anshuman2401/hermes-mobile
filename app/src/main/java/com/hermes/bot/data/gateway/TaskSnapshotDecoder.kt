package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.domain.ProgressTask
import com.hermes.bot.domain.TaskSnapshot
import com.hermes.bot.domain.TaskStatus

/** Optional full snapshots only: tool arguments/merge intentions never become confirmed progress. */
internal object TaskSnapshotDecoder {
    const val MAX_ITEMS = 256
    const val MAX_DESCRIPTION_SCALARS = 512
    const val MAX_RESULT_CHARS = 512_000
    private const val MAX_ID_CHARS = 128

    fun isTaskTool(name: String?) = name == "todo_list" || name == "todo"

    fun decode(value: JsonValue?): TaskSnapshot? {
        val root = when (value) {
            is JsonString -> {
                if (value.value.length > MAX_RESULT_CHARS || !boundedDepth(value.value)) return null
                GatewayJson.parseOrNull(value.value) as? JsonObject
            }
            is JsonObject -> value
            else -> null
        } ?: return null
        if (root["error"]?.let { it != JsonNull && it != JsonBoolean(false) && it != JsonString("") } == true ||
            root["ok"] == JsonBoolean(false) || root["success"] == JsonBoolean(false)) return null
        val list = root["todos"] as? JsonArray ?: return null
        if (list.list.size > MAX_ITEMS) return null
        val revisionValue = root["revision"]
        val revision = if (revisionValue == null) null else
            (revisionValue as? JsonNumber)?.takeIf { it.isInteger }?.longValue?.takeIf { it >= 0 } ?: return null
        val ids = HashSet<String>()
        var truncated = false
        val items = list.list.map { raw ->
            val item = raw as? JsonObject ?: return null
            val id = identity(item["id"]) ?: return null
            if (!ids.add(id)) return null
            val text = item["content"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (text.length > 16_000) return null
            val status = when (item["status"]?.asStringOrNull()) {
                "pending" -> TaskStatus.PENDING
                "in_progress" -> TaskStatus.IN_PROGRESS
                "completed" -> TaskStatus.COMPLETED
                "cancelled" -> TaskStatus.CANCELLED
                else -> return null
            }
            val safeText = GatewayRedactor.redactString(text)
            val scalarCount = safeText.codePointCount(0, safeText.length)
            val bounded = if (scalarCount > MAX_DESCRIPTION_SCALARS) {
                truncated = true
                safeText.substring(0, safeText.offsetByCodePoints(0, MAX_DESCRIPTION_SCALARS - 1)) + "…"
            } else safeText
            ProgressTask(id, bounded, status,
                identity(item["parent"])?.takeUnless { it == id })
        }
        return TaskSnapshot(items, revision, truncated)
    }

    private fun identity(value: JsonValue?): String? = value?.asStringOrNull()?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_CHARS && it.none(Char::isISOControl) }

    /** Reject deeply nested string results before invoking the recursive JSON parser. */
    private fun boundedDepth(text: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 8) return false }
                '}', ']' -> depth--
            }
        }
        return true
    }
}
