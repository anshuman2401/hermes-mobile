package com.hermes.bot.data.gateway

import com.hermes.bot.data.commands.*
import com.hermes.bot.data.gateway.json.*

/** Source-backed projections, not a public arbitrary-RPC or JSON interface. */
internal object SlashWireDecoder {
    private fun text(value: JsonValue?, max: Int = 1024): String? =
        value?.asStringOrNull()?.takeIf { it.length <= max }
    fun catalog(root: JsonValue): SlashCatalog? {
        val pairs = root["pairs"] as? JsonArray ?: return null
        if (pairs.list.size > SlashCommandPolicy.MAX_ROWS) return null
        val meta = root["commands"] as? JsonObject
        val unavailable = meta?.map?.filterValues {
            it["desktop"]?.asStringOrNull() in setOf("advanced", "composer-voice", "messaging", "settings", "terminal", "hidden")
        }?.keys.orEmpty()
        val rows = pairs.list.mapNotNull { pair ->
            val command = text(pair[0], 128) ?: return@mapNotNull null
            if (SlashCommandPolicy.parse(command) == null) return@mapNotNull null
            SlashSuggestion(command, text(pair[1]) ?: "", if (meta?.map?.containsKey(command) == true) "Commands" else "Skills")
        }
        val canon = (root["canon"] as? JsonObject)?.map.orEmpty()
        if (canon.size > 1024) return null
        val aliases = canon.mapNotNull { (key, value) ->
            val target = text(value, 128) ?: return@mapNotNull null
            if (SlashCommandPolicy.parse(key) == null || SlashCommandPolicy.parse(target) == null) null else key to target
        }.toMap()
        return SlashCatalog(rows, aliases, unavailable)
    }
    fun completion(root: JsonValue): SlashCompletion? {
        val items = root["items"] as? JsonArray ?: return null
        if (items.list.size > SlashCommandPolicy.MAX_ROWS) return null
        val from = root["replace_from"]?.asIntOrNull() ?: 1
        if (from < 0 || from > SlashCommandPolicy.MAX_QUERY) return null
        return SlashCompletion(items.list.mapNotNull {
            val insert = text(it["text"]) ?: return@mapNotNull null
            SlashSuggestion(insert, text(it["meta"]) ?: "", if (it["kind"]?.asStringOrNull() == "skill") "Skills" else "Commands")
        }, from)
    }
    fun directive(root: JsonValue): SlashDirective? {
        if (root !is JsonObject) return null
        fun payload(key: String) = text(root[key], SlashCommandPolicy.MAX_PAYLOAD)
        val notice = payload("notice")
        return when (root["type"]?.asStringOrNull()) {
            "alias" -> payload("target")?.let { SlashDirective.Alias(it) }
            "skill", "send" -> payload("message")?.takeIf { it.isNotBlank() }?.let {
                SlashDirective.Send(it, payload("display"), notice)
            }
            "prefill" -> payload("message")?.let { SlashDirective.Prefill(it, notice) }
            "exec", "plugin", null -> payload("output")?.let {
                val warning = payload("warning")
                SlashDirective.Output(if (warning.isNullOrBlank()) it else "$warning\n$it")
            }
            else -> null
        }
    }
    fun compressed(root: JsonValue): SlashDirective.Compressed? {
        if (root !is JsonObject) return null
        val status = root["status"]?.asStringOrNull()
        if (status != null && status !in setOf("compressed", "aborted", "pending")) return null
        val failed = status == "aborted" || root["summary"]?.get("aborted")?.asBooleanOrNull() == true
        val pending = status == "pending" || root["lock_held"]?.asBooleanOrNull() == true
        val changed = !failed && !pending && (status == "compressed" ||
            root["compressed"]?.asBooleanOrNull() == true || root["messages"] is JsonArray)
        if (!pending && !failed && status == null && root["compressed"] == null && root["messages"] == null && root["message"] == null) return null
        return SlashDirective.Compressed(pending, changed,
            if (pending) "Compression is still running on Hermes."
            else if (failed) "Hermes could not compress this conversation."
            else if (changed) "Conversation context compressed." else "Hermes did not compress this conversation.", failed)
    }
}
