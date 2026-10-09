package io.github.anshuman2401.hermesmobile.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHAT-016 (TASK-004): pure grouping/search/count/highlight policy tests for
 * the redesigned model picker sheet. Synthetic slugs/models only (SEC-003).
 */
class SessionModelPickerGroupingPolicyTest {

    private fun option(provider: String, model: String) = SessionModelOption(provider, model)

    @Test
    fun uniqueCurrentOption_requiresOneExactCatalogRow() {
        val alpha = option("alpha-provider", "model-one")
        val beta = option("beta-provider", "model-two")
        val options = listOf(alpha, beta)
        assertTrue(SessionModelPickerGroupingPolicy.uniqueCurrentOption(options, " MODEL-ONE ") === alpha)
        assertEquals(null, SessionModelPickerGroupingPolicy.uniqueCurrentOption(options, "model"))
        assertEquals(null, SessionModelPickerGroupingPolicy.uniqueCurrentOption(options, null))
        assertEquals(null, SessionModelPickerGroupingPolicy.uniqueCurrentOption(options, "  "))
        assertEquals(null, SessionModelPickerGroupingPolicy.uniqueCurrentOption(
            options + option("other-provider", "model-one"), "model-one"))
        assertEquals(null, SessionModelPickerGroupingPolicy.uniqueCurrentOption(
            options + alpha.copy(), "model-one"))
    }

    // (a) grouping preserves host provider order and within-provider model order.
    @Test
    fun group_preservesHostProviderOrder_andModelOrder() {
        val groups = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("gamma-provider", "gamma-model-1"),
                option("alpha-provider", "alpha-model-1"),
                option("alpha-provider", "alpha-model-2")
            )
        )
        assertEquals(listOf("gamma-provider", "alpha-provider"), groups.map { it.providerSlug })
        assertEquals(
            listOf("gamma-model-1"),
            groups[0].models.map { it.model }
        )
        assertEquals(
            listOf("alpha-model-1", "alpha-model-2"),
            groups[1].models.map { it.model }
        )
    }

    // (b) repeated provider slugs merge into one group at first-appearance position.
    @Test
    fun group_repeatedSlugs_mergeAtFirstAppearance() {
        val groups = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("beta-provider", "beta-model-1"),
                option("alpha-provider", "alpha-model-1"),
                option("beta-provider", "beta-model-2"),
                option("beta-provider", "beta-model-3")
            )
        )
        assertEquals(listOf("beta-provider", "alpha-provider"), groups.map { it.providerSlug })
        assertEquals(2, groups.size)
        assertEquals(
            listOf("beta-model-1", "beta-model-2", "beta-model-3"),
            groups[0].models.map { it.model }
        )
        assertEquals(
            listOf("alpha-model-1"),
            groups[1].models.map { it.model }
        )
    }

    // (c) filter drops providers with zero matching models and preserves order.
    @Test
    fun filter_dropsProvidersWithoutMatchingModels_andPreservesOrder() {
        val groups = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("alpha-provider", "alpha-model-1"),
                option("beta-provider", "beta-model-1"),
                option("gamma-provider", "gamma-model-1")
            )
        )
        val filtered = SessionModelPickerGroupingPolicy.filter(groups, "alpha")
        assertEquals(listOf("alpha-provider"), filtered.map { it.providerSlug })
        // Non-adjacent survivors keep host order (middle provider dropped).
        val spread = SessionModelPickerGroupingPolicy.filter(
            SessionModelPickerGroupingPolicy.group(
                listOf(
                    option("alpha-provider", "alpha-model-1"),
                    option("beta-provider", "beta-model-x"),
                    option("gamma-provider", "gamma-model-1")
                )
            ),
            "model-1"
        )
        assertEquals(listOf("alpha-provider", "gamma-provider"), spread.map { it.providerSlug })
    }

    @Test fun providerSearchReturnsAllModelsInHostOrder() {
        val groups = SessionModelPickerGroupingPolicy.group(listOf(option("alpha", "first"), option("beta", "second"), option("alpha", "third")))
        val filtered = SessionModelPickerGroupingPolicy.filter(groups, " ALPHA ")
        assertEquals(listOf("first", "third"), filtered.single().models.map { it.model })
    }

    // (d) blank/whitespace query returns the groups unchanged (identity).
    @Test
    fun filter_blankQuery_returnsGroupsUnchanged() {
        val groups = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("alpha-provider", "alpha-model-1"),
                option("beta-provider", "beta-model-1")
            )
        )
        assertEquals(groups, SessionModelPickerGroupingPolicy.filter(groups, ""))
        assertEquals(groups, SessionModelPickerGroupingPolicy.filter(groups, "   "))
        assertEquals(groups, SessionModelPickerGroupingPolicy.filter(groups, "\t\n"))
    }

    // (e) matching is case-insensitive, trimmed, substring.
    @Test
    fun filter_matchesCaseInsensitivelyTrimmed_asSubstring() {
        val groups = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("alpha-provider", "Alpha-Model-1"),
                option("beta-provider", "beta-model-2")
            )
        )
        // Case-insensitive substring across the case boundary.
        assertEquals(
            listOf("alpha-provider"),
            SessionModelPickerGroupingPolicy.filter(groups, "MODEL-1").map { it.providerSlug }
        )
        assertEquals(
            listOf("alpha-provider", "beta-provider"),
            SessionModelPickerGroupingPolicy.filter(groups, "Model").map { it.providerSlug }
        )
        // Query whitespace is trimmed; within-name whitespace still matters.
        assertEquals(
            listOf("alpha-provider"),
            SessionModelPickerGroupingPolicy.filter(groups, "  alpha-model-1  ").map { it.providerSlug }
        )
        assertEquals(
            emptyList<String>(),
            SessionModelPickerGroupingPolicy.filter(groups, "alpha model").map { it.providerSlug }
        )
    }

    // (f) header counts equal group/model totals.
    @Test
    fun counts_equalGroupAndModelTotals() {
        val groups = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("alpha-provider", "alpha-model-1"),
                option("alpha-provider", "alpha-model-2"),
                option("beta-provider", "beta-model-1"),
                option("gamma-provider", "gamma-model-1")
            )
        )
        assertEquals(PickerCounts(providers = 3, models = 4), SessionModelPickerGroupingPolicy.counts(groups))
        assertEquals(PickerCounts(0, 0), SessionModelPickerGroupingPolicy.counts(emptyList()))
    }

    // (g) filtered-view counts shrink while searching.
    @Test
    fun counts_shrinkWhileSearching() {
        val groups = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("alpha-provider", "alpha-model-1"),
                option("alpha-provider", "alpha-model-2"),
                option("beta-provider", "beta-model-1")
            )
        )
        val filtered = SessionModelPickerGroupingPolicy.filter(groups, "alpha")
        assertEquals(PickerCounts(providers = 1, models = 2), SessionModelPickerGroupingPolicy.counts(filtered))
        // Empty result set: zero providers, zero models.
        assertEquals(
            PickerCounts(0, 0),
            SessionModelPickerGroupingPolicy.counts(SessionModelPickerGroupingPolicy.filter(groups, "delta"))
        )
    }

    // (h) highlight returns exactly the provider(s) holding the current model name, case-insensitively.
    @Test
    fun highlight_exactProvidersHoldingCurrentModel_caseInsensitive() {
        val groups = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("alpha-provider", "Alpha-Model-1"),
                option("beta-provider", "beta-model-1"),
                option("gamma-provider", "Alpha-Model-2")
            )
        )
        assertEquals(
            setOf("alpha-provider"),
            SessionModelPickerGroupingPolicy.highlightedProviderSlugs(groups, " alpha-model-1 ")
        )
        // Same model name offered by two providers highlights both (RISK-004 rule).
        val shared = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("alpha-provider", "shared-model"),
                option("beta-provider", "shared-model"),
                option("gamma-provider", "gamma-model")
            )
        )
        assertEquals(
            emptySet<String>(),
            SessionModelPickerGroupingPolicy.highlightedProviderSlugs(shared, "Shared-Model")
        )
    }

    // (i) null/blank current model yields an empty highlight set (all dots gray).
    @Test
    fun highlight_nullOrBlankCurrentModel_yieldsEmptySet() {
        val groups = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("alpha-provider", "alpha-model-1"),
                option("beta-provider", "beta-model-1")
            )
        )
        assertEquals(emptySet<String>(), SessionModelPickerGroupingPolicy.highlightedProviderSlugs(groups, null))
        assertEquals(emptySet<String>(), SessionModelPickerGroupingPolicy.highlightedProviderSlugs(groups, ""))
        assertEquals(emptySet<String>(), SessionModelPickerGroupingPolicy.highlightedProviderSlugs(groups, "   "))
    }

    // (j) unknown current model yields an empty highlight set.
    @Test
    fun highlight_unknownCurrentModel_yieldsEmptySet() {
        val groups = SessionModelPickerGroupingPolicy.group(
            listOf(
                option("alpha-provider", "alpha-model-1"),
                option("beta-provider", "beta-model-1")
            )
        )
        assertEquals(
            emptySet<String>(),
            SessionModelPickerGroupingPolicy.highlightedProviderSlugs(groups, "delta-model")
        )
        // Substring overlap is NOT an exact match: highlight stays empty.
        assertEquals(
            emptySet<String>(),
            SessionModelPickerGroupingPolicy.highlightedProviderSlugs(groups, "alpha-model")
        )
        assertTrue(groups.isNotEmpty())
    }
}
