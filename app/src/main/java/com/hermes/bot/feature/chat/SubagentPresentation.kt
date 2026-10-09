package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.SubagentWireDecoder
import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.data.subagent.*

/** Tool-derived descriptions never authorize child requests. Not serialized by offline caches. */
object SubagentPresentation {
    fun tasks(name: String, args: Any?, result: Any?, callId: String, historical: Boolean = false): List<Subagent> {
        if (name != "delegate_task") return emptyList()
        fun json(value: Any?): JsonValue = try { if (value is String) GatewayJson.parse(value) else GatewayJson.fromAny(value) } catch (_: Exception) { JsonObject() }
        val input = json(args); val output = json(result)
        val action = input["action"]?.asStringOrNull()
        if (action != null && action !in setOf("", "run", "spawn")) return emptyList()
        val batch = (input["tasks"] as? JsonArray)?.list.orEmpty()
        val goals = if (batch.isNotEmpty()) batch.take(128).map { it["goal"]?.asStringOrNull() ?: "Delegated task" }
            else input["goal"]?.asStringOrNull()?.let(::listOf)
                ?: (output["goals"] as? JsonArray)?.list.orEmpty().take(128).mapNotNull { it.asStringOrNull() }
        val results = (output["results"] as? JsonArray)?.list.orEmpty()
        val identities = (output["subagent_ids"] as? JsonArray)?.list.orEmpty()
        fun identity(value: JsonValue?) = value?.asStringOrNull()?.takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
        val delegation = identity(output["delegation_id"])
        val projections = goals.mapIndexed { index, goal ->
            val row = results.getOrNull(index)
            val status = if (row != null) SubagentWireDecoder.status(row["status"]?.asStringOrNull(), true)
                else if (output["status"]?.asStringOrNull() in setOf("error", "failed", "timeout")) SubagentStatus.FAILED
                else if (output["status"]?.asStringOrNull() == "dispatched") SubagentStatus.DISPATCHED
                else if (historical) SubagentStatus.UNKNOWN else SubagentStatus.QUEUED
            Subagent("task:${callId.take(64)}:$index", BoundedPayloadMapper.boundedCollapsedSummary(goal), status,
                delegationId = delegation, taskIndex = index, model = row?.get("model")?.asStringOrNull()?.let(BoundedPayloadMapper::boundedNameType),
                durationSeconds = row?.get("duration_seconds")?.asDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
                activity = listOfNotNull(row?.get("summary")?.asStringOrNull()?.let(BoundedPayloadMapper::boundedCollapsedSummary)), observed = false, historical = historical, sourceChildId = identity(identities.getOrNull(index)))
        }
        var bytes = 0
        val retained = projections.takeWhile { child ->
            val size = projectionBytes(child)
            (bytes + size <= 4096).also { if (it) bytes += size }
        }
        return if (retained.size < projections.size && retained.isNotEmpty()) retained.dropLast(1) + retained.last().copy(projectionTruncated = true) else retained
    }
    internal fun projectionBytes(child: Subagent): Int = 96 + listOfNotNull(child.id, child.goal, child.delegationId, child.model, child.sourceChildId).plus(child.activity).sumOf(BoundedPayloadMapper::utf8ByteCount)
    fun rows(tool: ToolActivity, timeline: List<TimelineEntry>, live: List<Subagent>): List<Subagent> {
        val current = timeline.drop(timeline.indexOfLast { it is UserMessage } + 1).filterIsInstance<ToolActivity>()
        val candidates = current.flatMap { it.delegatedTasks }.filter { !it.finished && !it.historical }
        return tool.delegatedTasks.map { task ->
            if (task.finished) task else {
                val exact = live.filter { child -> task.sourceChildId?.let { it == child.id }
                    ?: (task.delegationId != null && task.delegationId == child.delegationId && task.taskIndex == child.taskIndex) }
                val matches = if (exact.isNotEmpty()) exact else if (!task.historical && tool in current)
                    live.filter { it.goal == task.goal && candidates.count { row -> row.goal == task.goal } == 1 } else emptyList()
                if (matches.size == 1) matches.single() else task
            }
        }
    }

    /** Extract cards at their step positions, retaining ordinary tool groups around them. */
    fun display(entries: List<TraceDisplayEntry>): List<TraceDisplayEntry> = entries.flatMap { item ->
        if (item !is TraceDisplayEntry.BlockCard || item.block.steps.none { (it as? ToolBlockStep.Tool)?.activity?.delegatedTasks?.isNotEmpty() == true }) listOf(item)
        else buildList {
            val group = mutableListOf<ToolBlockStep>(); var part = 0
            fun flush() { if (group.isNotEmpty()) { add(TraceDisplayEntry.BlockCard(item.block.copy(id = "${item.block.id}-part-${part++}", steps = group.toList(), totalActionCount = group.count { it !is ToolBlockStep.Note }, aggregateTimeMillis = null, aggregateTimeText = null))); group.clear() } }
            item.block.steps.forEach { step ->
                if ((step as? ToolBlockStep.Tool)?.activity?.delegatedTasks?.isNotEmpty() == true) { flush(); add(TraceDisplayEntry.Raw(step.activity)) }
                else group += step
            }
            flush()
        }
    }
}
