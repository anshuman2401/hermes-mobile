package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.data.subagent.*

/** One bounded decoder for event and optional RPC projections. No raw payload escapes. */
object SubagentWireDecoder {
    val eventTypes = setOf("subagent.spawn_requested", "subagent.start", "subagent.progress", "subagent.thinking", "subagent.tool", "subagent.complete")
    const val MAX_CHILDREN = 128
    fun status(raw: String?, terminal: Boolean = false): SubagentStatus = when (raw) {
        "queued" -> if (terminal) SubagentStatus.UNKNOWN else SubagentStatus.QUEUED
        "running" -> if (terminal) SubagentStatus.UNKNOWN else SubagentStatus.RUNNING
        "ok", "completed" -> SubagentStatus.COMPLETED
        "failed", "failure", "error", "timeout" -> SubagentStatus.FAILED
        "interrupted", "cancelled", "canceled" -> SubagentStatus.INTERRUPTED
        "dispatched" -> SubagentStatus.DISPATCHED
        else -> SubagentStatus.UNKNOWN
    }
    private fun string(o: JsonValue, key: String) = o[key]?.asStringOrNull()
    private fun identity(o: JsonValue, key: String) = string(o, key)?.takeIf { it.isNotBlank() && it == it.trim() && it.length <= 256 && it.none(Char::isISOControl) && it != "[REDACTED]" }
    private fun text(s: String?) = s?.let { SubagentText.preview(it) }?.takeIf(String::isNotBlank)
    private fun number(o: JsonValue, key: String) = o[key]?.asLongOrNull()?.takeIf { it >= 0 }
    fun child(o: JsonValue, type: String = ""): Subagent? {
        val id = identity(o, "subagent_id") ?: return null
        val supplied = string(o, "status")
        val state = if (supplied == null && type == "subagent.spawn_requested") SubagentStatus.QUEUED
            else if (supplied == null && type in eventTypes && type != "subagent.complete") SubagentStatus.RUNNING
            else status(supplied, type == "subagent.complete")
        val activity = mutableListOf<String>()
        val tool = text(string(o, "tool_name"))
        if (tool != null) activity += tool
        text(string(o, "text") ?: string(o, "tool_preview"))?.let { activity += it }
        (o["output_tail"] as? JsonArray)?.list?.takeLast(24)?.forEach { entry ->
            text(string(entry, "preview"))?.let { activity += it }
        }
        text(string(o, "summary"))?.let { activity += it }
        if (supplied == "timeout" && activity.isEmpty()) activity += "Agent timed out."
        val input = number(o, "input_tokens"); val output = number(o, "output_tokens")
        return Subagent(id, text(string(o, "goal")) ?: "Delegated task", state,
            identity(o, "parent_id"), identity(o, "delegation_id"), number(o, "task_index")?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
            text(string(o, "model")), o["started_at"]?.asDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE / 1000.0 }?.times(1000)?.toLong(),
            o["duration_seconds"]?.asDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }, number(o, "tool_count"),
            if (input != null || output != null) (input ?: 0).let { a -> (output ?: 0).let { b -> if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b } } else null,
            o["accepting_steer"]?.asBooleanOrNull(), activity.takeLast(24), finished = type == "subagent.complete" || state.terminal)
    }
    fun roster(o: JsonValue): SubagentRoster? {
        val children = (o["subagents"] as? JsonArray)?.list ?: return null
        val failures = (o["delegations"] as? JsonArray)?.list.orEmpty().take(MAX_CHILDREN).mapNotNull { row ->
            val id = identity(row, "delegation_id") ?: return@mapNotNull null
            val status = status(string(row, "status"), true)
            if (status != SubagentStatus.FAILED && status != SubagentStatus.INTERRUPTED) return@mapNotNull null
            Subagent("failed:$id:${number(row, "task_index") ?: 0}", text(string(row, "goal")) ?: "Delegated task", status,
                delegationId = id, taskIndex = number(row, "task_index")?.takeIf { it <= Int.MAX_VALUE }?.toInt(), activity = listOfNotNull(text(string(row, "error"))))
        }
        return SubagentRoster(children.take(MAX_CHILDREN).mapNotNull { child(it) }, failures, children.size > MAX_CHILDREN)
    }
    fun tail(o: JsonValue): SubagentTail? {
        val available = o["available"]?.asBooleanOrNull() ?: return null
        val raw = string(o, "text") ?: if (available) return null else ""
        return SubagentTail(available, SubagentText.tail(raw), o["truncated"]?.asBooleanOrNull() == true || raw.toByteArray(Charsets.UTF_8).size > 16384)
    }
}
