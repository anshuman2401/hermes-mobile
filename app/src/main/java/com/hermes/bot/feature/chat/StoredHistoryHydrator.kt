package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.data.history.StoredHistoryException
import com.hermes.bot.data.history.StoredHistoryFailure
import com.hermes.bot.data.history.StoredHistoryRow

/** Whole-window source-order projection. Raw stored rows never become compact/cache messages. */
object StoredHistoryHydrator {
    private data class Call(val row: StoredHistoryRow, val position: Int, val index: Int,
        val context: Long?, val opaqueId: String?, val name: String?, val arguments: JsonValue?,
        var result: StoredHistoryRow? = null)

    fun hydrate(wireRows: List<StoredHistoryRow>): List<TimelineEntry> {
        if (wireRows.size > 4096) limit()
        // Result row redelivery must be removed before it can consume another call occurrence.
        val rows = wireRows.distinctBy { it.id }
        var bytes = rows.sumOf { it.canonicalBytes.toLong() + 128L }
        if (bytes > MAX_WINDOW_BYTES) limit()
        val contexts = ArrayList<Long?>(rows.size)
        val calls = ArrayList<Call>()
        var context: Long? = null
        rows.forEachIndexed { position, row ->
            if (row.role == "user") context = row.id
            contexts.add(context)
            if (row.role == "assistant") {
                val parts = (row.fields["tool_calls"] as? JsonArray)?.list.orEmpty()
                parts.forEachIndexed { index, part ->
                    val function = part["function"]
                    val call = Call(row, position, index, context,
                        part["id"]?.asStringOrNull()?.takeIf { it.isNotBlank() },
                        function?.get("name")?.asStringOrNull() ?: part["name"]?.asStringOrNull(),
                        function?.get("arguments") ?: part["args"])
                    calls.add(call)
                    bytes += 192L + (call.opaqueId?.let(BoundedPayloadMapper::utf8ByteCount) ?: 0)
                    if (bytes > MAX_WINDOW_BYTES) limit()
                }
            }
        }
        val orphans = HashSet<Long>()
        rows.forEachIndexed { position, row ->
            if (row.role != "tool") return@forEachIndexed
            val id = row.fields["tool_call_id"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
            val hasResult = row.hasProperty("result") || row.hasProperty("content")
            if (!hasResult) { orphans.add(row.id); return@forEachIndexed }
            val candidates = if (id == null) emptyList() else calls.filter {
                it.opaqueId == id && it.context == contexts[position] && it.result == null
            }
            // Desktop's newest prior assistant occurrence, then first unresolved ordinal.
            val prior = candidates.filter { it.position < position }
            val newest = prior.maxOfOrNull { it.position }
            val target = if (newest != null) prior.first { it.position == newest }
                else candidates.singleOrNull() // future reassociation requires unambiguous identity/context
            if (target == null) orphans.add(row.id) else target.result = row
        }
        val out = ArrayList<TimelineEntry>()
        fun add(entry: TimelineEntry) {
            val text = when (entry) {
                is UserMessage -> entry.text
                is AssistantMessage -> entry.text
                is ReasoningEntry -> entry.text
                is ToolActivity -> entry.name + entry.toolId.orEmpty() + entry.argsSummary.orEmpty() + entry.resultSummary.orEmpty()
                is GenericGatewayActivity -> entry.summary + entry.payloadPreview.orEmpty()
                else -> ""
            }
            bytes += 192L + BoundedPayloadMapper.utf8ByteCount(text) + (entry as? ToolActivity)?.delegatedTasks.orEmpty().sumOf(SubagentPresentation::projectionBytes)
            bytes += (entry as? ToolActivity)?.files.orEmpty().sumOf { it.key.length.toLong() * 2 + 128 }
            if (bytes > MAX_WINDOW_BYTES) limit()
            out.add(entry)
        }
        rows.forEach { row ->
            val text = row.fields["display_content"]?.asStringOrNull()
                ?: row.fields["content"]?.asStringOrNull().orEmpty()
            val hidden = row.fields["display_kind"]?.asStringOrNull() == "hidden"
            if (!hidden && text.isNotBlank()) when (row.role) {
                "user" -> add(UserMessage("stored-${row.id}", boundedText(text), timestamp = 0,
                    editable = row.editable?.takeIf { boundedText(text) == text }))
                "assistant" -> add(AssistantMessage("stored-${row.id}", boundedText(text),
                    sourceOverflow = row.truncated, timestamp = 0))
            }
            if (row.role == "assistant") {
                val reasoning = row.fields["reasoning"]?.asStringOrNull()
                    ?: row.fields["reasoning_content"]?.asStringOrNull()
                if (!reasoning.isNullOrBlank()) add(ReasoningEntry("stored-${row.id}-reasoning",
                    ReasoningDisplayKind.REASONING, ReasoningPolicy.accumulate("", reasoning), timestamp = 0))
                calls.filter { it.row.id == row.id }.forEach { call ->
                    val result = call.result
                    val arguments = call.arguments ?: result?.fields?.get("args")
                    val args = arguments?.let { payload(it, true, row.truncated || result?.truncated == true) }
                    if (result != null) {
                        add(ToolActivity("stored-${row.id}-call-${call.index}", call.opaqueId,
                            BoundedPayloadMapper.boundedNameType(call.name), ToolStatus.COMPLETED,
                            files = if (row.truncated || result.truncated) emptyList() else com.hermes.bot.data.files.ConversationFileReferences.tool((result.fields["result"] ?: result.fields["content"])?.toAny(), call.name.orEmpty()),
                            argsSummary = args, resultSummary = payload(result.fields["result"]
                                ?: result.fields.getValue("content"), false, result.truncated), timestamp = 0,
                            storedSource = StoredToolSource(row.id, call.index, call.context, result.id,
                                arguments != null, row.truncated || result.truncated),
                            delegatedTasks = if (row.truncated || result.truncated) emptyList() else
                                SubagentPresentation.tasks(call.name.orEmpty(), arguments?.toAny(),
                                    (result.fields["result"] ?: result.fields["content"])?.toAny(),
                                    "stored-${row.id}-call-${call.index}", historical = true)))
                    } else {
                        val tasks = if (row.truncated) emptyList() else SubagentPresentation.tasks(call.name.orEmpty(), arguments?.toAny(), null, "stored-${row.id}-call-${call.index}", historical = true)
                        // No result is evidence of neither running nor completion.
                        if (tasks.isNotEmpty()) add(ToolActivity("stored-${row.id}-call-${call.index}", call.opaqueId, "delegate_task", ToolStatus.PREPARING, argsSummary = args, timestamp = 0, delegatedTasks = tasks))
                        else add(GenericGatewayActivity("stored-${row.id}-call-${call.index}", "history.tool",
                            "${BoundedPayloadMapper.boundedNameType(call.name)} — outcome unknown",
                            BoundedPayloadMapper.boundedGenericPreview("Input: ${args ?: "Unknown"}"), timestamp = 0))
                    }
                }
            }
            if (row.id in orphans) {
                val args = row.fields["args"]?.let { payload(it, true, row.truncated) } ?: "Unknown"
                val result = (row.fields["result"] ?: row.fields["content"])?.let {
                    payload(it, false, row.truncated)
                } ?: "Unknown"
                add(GenericGatewayActivity("stored-${row.id}-orphan", "history.tool",
                    "${BoundedPayloadMapper.boundedNameType(row.fields["tool_name"]?.asStringOrNull())} — unpaired result",
                    BoundedPayloadMapper.boundedGenericPreview("Input: $args\nOutput: $result"), timestamp = 0))
            }
        }
        return out
    }

    internal fun hydrateForActive(wireRows: List<StoredHistoryRow>): List<ActiveFoldMessage> {
        val entries = hydrate(wireRows).map { part -> when (part) {
            is UserMessage -> part.copy(sourceKind = ActiveSourceKind.STORED)
            is AssistantMessage -> part.copy(sourceKind = ActiveSourceKind.STORED)
            else -> part
        } }
        val grouped = wireRows.distinctBy { it.id }.map { row ->
            val id = "stored-${row.id}"
            ActiveFoldMessage(id, row.role, entries.filter { it.id == id || it.id.startsWith("$id-") },
                ActiveSourceKind.STORED,
                (row.fields["timestamp"] as? JsonNumber)?.value?.takeIf { it.isFinite() && it >= 0 },
                (row.fields["pending"] as? JsonBoolean)?.value,
                (row.fields["interim"] as? JsonBoolean)?.value, row.fields, row.canonicalBytes.toLong() + 128L)
        }
        admit(wireRows, grouped)
        return grouped
    }

    /** Include canonical rows, occurrence indexes, all source groups and retained presentation before publication. */
    internal fun admit(rows: List<StoredHistoryRow>, groups: List<ActiveFoldMessage>, incrementalBytes: Long = 0,
        baselines: List<List<ActiveFoldMessage>> = emptyList(),
        sourceRoots: List<Pair<Any, Long>> = emptyList()) {
        if (rows.size > 4096 || groups.size > 4096) limit()
        var bytes = incrementalBytes
        val sources = java.util.IdentityHashMap<Any, Boolean>()
        val retained = java.util.IdentityHashMap<Any, Boolean>()
        sourceRoots.forEach { (source, size) -> if (sources.put(source, true) == null) bytes += size }
        rows.forEach { row ->
            if (sources.put(row.fields, true) == null) bytes += row.canonicalBytes.toLong() + 128L
        }
        fun text(value: String?) {
            if (value != null && retained.put(value, true) == null) bytes += BoundedPayloadMapper.utf8ByteCount(value)
        }
        (listOf(groups) + baselines).forEach { root ->
            if (retained.put(root, true) != null) return@forEach
            bytes += 24L + root.size * 8L
            root.forEach { group ->
                if (retained.put(group, true) == null) {
                    bytes += 256L; text(group.id)
                    if (group.retainedSource != null && sources.put(group.retainedSource, true) == null)
                        bytes += group.retainedSourceBytes
                }
                if (retained.put(group.parts, true) == null) bytes += 24L + group.parts.size * 8L
                group.parts.forEach { part ->
                    if (retained.put(part, true) == null) {
                        bytes += 192L; text(part.id)
                        when (part) {
                            is UserMessage -> text(part.text)
                            is AssistantMessage -> { text(part.text); text(part.errorMessage) }
                            is ReasoningEntry -> text(part.text)
                            is ToolActivity -> { text(part.name); text(part.toolId); text(part.argsSummary); text(part.resultSummary); text(part.context); bytes += part.delegatedTasks.sumOf(SubagentPresentation::projectionBytes) }
                            is GenericGatewayActivity -> { text(part.eventType); text(part.summary); text(part.payloadPreview) }
                            is AgentNoteEntry -> text(part.text)
                            is ModelChangeNoticeRow -> text(part.text)
                            else -> Unit
                        }
                    }
                    if (bytes > MAX_WINDOW_BYTES) limit()
                }
                if (bytes > MAX_WINDOW_BYTES) limit()
            }
        }
        if (bytes > MAX_WINDOW_BYTES) limit()
    }

    /** Ordinary backfill keeps existing rows and places new runs at shared durable anchors. */
    internal fun mergeOlder(previous: List<StoredHistoryRow>, older: List<StoredHistoryRow>): List<StoredHistoryRow> {
        if (previous.isEmpty() || older.isEmpty()) return previous
        val result = previous.toMutableList()
        val known = previous.map { it.id }.toMutableSet()
        var insertion = 0
        older.forEach { row ->
            val anchor = result.indexOfFirst { it.id == row.id }
            if (anchor >= 0) insertion = anchor + 1
            else if (known.add(row.id)) { result.add(insertion, row); insertion++ }
        }
        return result
    }

    /** Desktop's refresh graft: only its exceptional overlapping branch sorts durable slots. */
    internal fun graftRefresh(freshRows: List<StoredHistoryRow>, previous: List<StoredHistoryRow>): List<StoredHistoryRow> {
        val fresh = freshRows.distinctBy { it.id }
        if (fresh.isEmpty() || previous.isEmpty()) return fresh
        val anchor = previous.indexOfFirst { it.id == fresh.first().id }
        if (anchor == 0) return fresh
        if (anchor > 0 && previous.take(anchor).all { it.id < fresh.first().id })
            return (previous.take(anchor) + fresh).distinctBy { it.id }
        val ids = fresh.map { it.id }.toSet()
        if (previous.all { it.id in ids } || previous.none { it.id in ids }) return fresh
        return (previous.associateBy { it.id } + fresh.associateBy { it.id }).toSortedMap().values.toList()
    }

    /** Identity proof requires the current presentation's actual persisted user, not text equality. */
    internal fun representedTools(rows: List<StoredHistoryRow>, current: List<ActiveFoldMessage>): Set<String> {
        val user = rows.lastOrNull { it.role == "user" } ?: return emptySet()
        val currentUser = current.lastOrNull { it.role == "user" } ?: return emptySet()
        if (currentUser.sourceKind != ActiveSourceKind.STORED || currentUser.id != "stored-${user.id}") return emptySet()
        val suffix = rows.drop(rows.indexOf(user) + 1)
        val occurrences = suffix.filter { it.role == "assistant" }.flatMap { row ->
            (row.fields["tool_calls"] as? JsonArray)?.list.orEmpty().mapNotNull { it["id"]?.asStringOrNull()?.takeIf(String::isNotBlank) }
        }.groupingBy { it }.eachCount()
        return hydrate(rows).filterIsInstance<ToolActivity>().filter { tool ->
            tool.storedSource?.contextRowId == user.id && occurrences[tool.toolId] == 1
        }.mapNotNull { it.toolId }.toSet()
    }

    internal fun removeRepresentedTools(rows: List<StoredHistoryRow>, current: List<ActiveFoldMessage>): List<ActiveFoldMessage> {
        val ids = representedTools(rows, current)
        if (ids.isEmpty()) return current
        val user = current.indexOfLast { it.role == "user" }
        return current.mapIndexedNotNull { index, group ->
            if (index <= user) group else {
                // A live start always supplies argsSummary (including "(no arguments)"),
                // and completion preserves it. That presentation evidence distinguishes
                // an admitted live occurrence from a bare represented recovery completion.
                val parts = group.parts.filterNot {
                    it is ToolActivity && it.storedSource == null && it.toolId in ids &&
                        it.status == ToolStatus.COMPLETED && it.argsSummary == null
                }
                if (parts.isEmpty() && group.parts.isNotEmpty()) null else group.copy(parts = parts)
            }
        }
    }

    private fun boundedText(text: String) = BoundedPayloadMapper.truncateUtf8Bytes(text, 16384,
        "\n[Truncated]", redact = true)

    private fun payload(value: JsonValue, arguments: Boolean, truncated: Boolean): String {
        val text = if (value is JsonString && value.value.isNotBlank()) value.value else GatewayJson.serialize(value)
        val bounded = if (arguments) BoundedPayloadMapper.boundedExpandedArgs(text)
            else BoundedPayloadMapper.boundedExpandedResult(text)
        return bounded.orEmpty() + if (truncated) "\n[Source truncated]" else ""
    }

    private fun limit(): Nothing = throw StoredHistoryException(StoredHistoryFailure.BUDGET)
    private const val MAX_WINDOW_BYTES = 8L * 1024 * 1024
}
