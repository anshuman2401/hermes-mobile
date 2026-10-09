package com.hermes.bot.data.gateway

import java.util.Locale

/** Only host-offered, authenticated and token-safe identities may reach config.set. */
data class SessionModelOption(val providerSlug: String, val model: String)

data class SessionPickerSetResult(val deferred: Boolean, val confirmRequired: Boolean)

internal fun safeModelToken(value: String): Boolean =
    value.isNotEmpty() && !value.startsWith("--") && value.none { it.isWhitespace() || it.isISOControl() }

val SESSION_REASONING_LEVELS: List<String> =
    listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/**
 * CHAT-016 (TASK-001): one provider's slice of the picker catalog, in host
 * response order. Providers appear in first-appearance (host) order; models
 * keep the original order within each provider — no deduplication, no sorting.
 */
data class PickerProviderGroup(val providerSlug: String, val models: List<SessionModelOption>)

/**
 * CHAT-016 (TASK-003): header counts over the currently displayed groups —
 * [providers] is the number of groups, [models] the total model rows (the
 * filtered view while a search query is active, totals otherwise).
 */
data class PickerCounts(val providers: Int, val models: Int)

/**
 * CHAT-016 Phase 1: the single grouping/search/count/highlight authority for
 * the redesigned model picker sheet (GUD-001 — pure policy, zero Compose/Android
 * dependencies; the UI renders and never derives).
 *
 * ASSUMPTION-001: host order means first-appearance order of providers in
 * [SessionModelOption] lists (the adapter builds rows in host response order);
 * no reordering is ever performed.
 */
object SessionModelPickerGroupingPolicy {

    /** A radio selection is safe only when the host model name identifies one catalog row. */
    fun uniqueCurrentOption(options: List<SessionModelOption>, currentModelName: String?): SessionModelOption? {
        val current = currentModelName?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return options.filter { it.model.trim().lowercase(Locale.ROOT) == current }.singleOrNull()
    }

    /**
     * Group options by [SessionModelOption.providerSlug], preserving the
     * first-appearance (host) order of providers and the original order of
     * models within each provider. Repeated provider slugs merge into one
     * group at the first-appearance position. No deduplication, no sorting.
     */
    fun group(options: List<SessionModelOption>): List<PickerProviderGroup> {
        val ordered = LinkedHashSet<String>()
        val byProvider = LinkedHashMap<String, MutableList<SessionModelOption>>()
        for (option in options) {
            if (byProvider.putIfAbsent(option.providerSlug, mutableListOf()) == null) {
                ordered.add(option.providerSlug)
            }
            byProvider.getValue(option.providerSlug).add(option)
        }
        return ordered.map { slug -> PickerProviderGroup(slug, byProvider.getValue(slug)) }
    }

    /**
     * CHAT-016 (TASK-002, OQ-003): filter the grouped list by provider or model name. A blank/whitespace-only [query] returns
     * the input groups unchanged. Otherwise keep only providers with >= 1 model
     * whose name matches by trimmed, lowercased-via-[Locale.ROOT] substring
     * (`contains`), preserving group and model order, dropping empty groups.
     */
    fun filter(groups: List<PickerProviderGroup>, query: String): List<PickerProviderGroup> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isEmpty()) return groups
        return groups.mapNotNull { group ->
            val matching = group.models.filter { option ->
                group.providerSlug.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    option.model.trim().lowercase(Locale.ROOT).contains(normalizedQuery)
            }
            if (matching.isEmpty()) null else PickerProviderGroup(group.providerSlug, matching)
        }
    }

    /**
     * CHAT-016 (TASK-003): header counts over the currently displayed groups —
     * P = number of provider groups, M = total model rows across them.
     */
    fun counts(groups: List<PickerProviderGroup>): PickerCounts =
        PickerCounts(providers = groups.size, models = groups.sumOf { it.models.size })

    /**
     * CHAT-016 (TASK-003, REQ-005): providers whose models contain an exact
     * (trimmed, lowercased-via-[Locale.ROOT]) match of the host-reported session
     * model name. A null/blank current model or an unknown model name yields an
     * empty set (all dots gray).
     */
    fun highlightedProviderSlugs(
        groups: List<PickerProviderGroup>,
        currentModelName: String?
    ): Set<String> {
        val normalizedCurrent = currentModelName?.trim()?.lowercase(Locale.ROOT)
        if (normalizedCurrent.isNullOrEmpty()) return emptySet()
        return groups.filter { group ->
            group.models.any { option ->
                option.model.trim().lowercase(Locale.ROOT) == normalizedCurrent
            }
        }.map { it.providerSlug }.toSet().takeIf { it.size == 1 } ?: emptySet()
    }
}
