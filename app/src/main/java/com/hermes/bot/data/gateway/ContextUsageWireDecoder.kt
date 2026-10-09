package com.hermes.bot.data.gateway

import com.hermes.bot.data.context.*
import com.hermes.bot.data.gateway.json.*

/** Strict, bounded projection of Desktop-backed session RPCs. No manifest/path retention. */
internal object ContextUsageWireDecoder {
    private fun nonnegative(value: JsonValue?): Long? =
        (value as? JsonNumber)?.asLongOrNull()?.takeIf { it >= 0 }
    private fun text(value: JsonValue?, limit: Int): String? =
        (value as? JsonString)?.value?.takeIf { it.length <= limit }?.let(GatewayRedactor::redactString)
    fun breakdown(root: JsonValue): ContextBreakdown? {
        if (root !is JsonObject) return null
        val used = nonnegative(root["context_used"]) ?: return null
        val max = nonnegative(root["context_max"]) ?: return null
        if (used > max) return null
        val percent = (root["context_percent"] as? JsonNumber)?.value
            ?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        val estimated = (root["context_estimated"] as? JsonBoolean)?.value ?: return null
        val rows = root["categories"] as? JsonArray ?: return null
        if (rows.list.size > 32) return null
        val categories = rows.list.map { row ->
            val id = text(row["id"], 64)?.takeIf { it.isNotBlank() } ?: return null
            val label = text(row["label"], 128)?.takeIf { it.isNotBlank() } ?: return null
            val tokens = nonnegative(row["tokens"]) ?: return null
            ContextCategory(id, label, tokens)
        }
        if (categories.map { it.id }.distinct().size != categories.size) return null
        val model = if (root["model"] == null || root["model"] == JsonNull) null
            else text(root["model"], 256) ?: return null
        return ContextBreakdown(used, max, percent, estimated, model, categories)
    }
    fun live(payload: JsonObject): LiveUsageSummary {
        val root = payload["usage"] as? JsonObject ?: return LiveUsageSummary()
        // Reuse occupancy bounds, without inventing category data for the event.
        val context = breakdown(JsonObject(root.map + mapOf("categories" to JsonArray(emptyList()), "model" to JsonNull)))
        val model = text(root["model"], 256)?.trim()?.takeIf { it.isNotBlank() }
        return LiveUsageSummary(context, nonnegative(root["total"]), model)
    }
    fun usage(root: JsonValue): SessionUsage? {
        if (root !is JsonObject) return null
        val total = if (root["total"] == null || root["total"] == JsonNull) null
            else nonnegative(root["total"]) ?: return null
        fun lines(key: String): List<String>? {
            val array = root[key]
            if (array == null || array == JsonNull) return emptyList()
            if (array !is JsonArray || array.list.size > AccountAllowanceParser.MAX_LINES) return null
            return array.list.map { text(it, AccountAllowanceParser.MAX_LINE_LENGTH) ?: return null }
        }
        val all = (lines("account_lines") ?: return null) + (lines("credits_lines") ?: return null)
        if (all.size > AccountAllowanceParser.MAX_LINES) return null
        return SessionUsage(total, AccountAllowanceParser.parse(all))
    }
}
