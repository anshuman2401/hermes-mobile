package io.github.anshuman2401.hermesmobile.feature.brain

import io.github.anshuman2401.hermesmobile.data.brain.BrainModelCapability
import io.github.anshuman2401.hermesmobile.data.brain.BrainModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainMainModelDialogPolicyTest {
    @Test
    fun providerAndModelSearchPreservesHostOrderAndProviderMatchesIncludeAllModels() {
        val providers = listOf(
            provider("zeta", listOf("plain")),
            provider("Alpha Cloud", listOf("vision-v1", "text-v2")),
            provider("omega", listOf("other"))
        )

        val providerMatch = filterBrainModelProviders(providers, "alpha")
        assertEquals(listOf("Alpha Cloud"), providerMatch.map { it.provider.slug })
        assertEquals(listOf("vision-v1", "text-v2"), providerMatch.single().models)

        val modelMatch = filterBrainModelProviders(providers, "V2")
        assertEquals(listOf("Alpha Cloud"), modelMatch.map { it.provider.slug })
        assertEquals(listOf("text-v2"), modelMatch.single().models)
    }

    @Test
    fun clearingSearchRestoresPreSearchSingleExpansion() {
        val expanded = BrainDialogExpansionPolicy.toggle(BrainExpansionState(), "provider-a")
        val searching = BrainDialogExpansionPolicy.beginSearch(expanded)
        assertTrue(searching.searching)
        assertEquals("provider-a", searching.beforeSearch)

        val cleared = BrainDialogExpansionPolicy.clearSearch(searching)
        assertFalse(cleared.searching)
        assertEquals("provider-a", cleared.expandedProvider)
        assertEquals(null, cleared.beforeSearch)
    }

    @Test
    fun reasoningFallbackHonorsExplicitRestrictionsWithoutMetadataGate() {
        val unknown = brainReasoningPolicy(
            providers = listOf(provider("provider-a", listOf("model-a"), capability = null)),
            provider = "provider-a",
            model = "model-a",
            savedEffort = "vendor-custom"
        )
        assertTrue(unknown.enabled)
        assertEquals(BRAIN_REASONING_EFFORTS, unknown.options)

        val cannotDisable = brainReasoningPolicy(
            providers = listOf(
                provider("provider-a", listOf("model-a"), BrainModelCapability(true, false))
            ),
            provider = "provider-a",
            model = "model-a",
            savedEffort = "medium"
        )
        assertFalse(cannotDisable.options.contains("none"))

        val unsupported = brainReasoningPolicy(
            providers = listOf(
                provider("provider-a", listOf("model-a"), BrainModelCapability(false, true))
            ),
            provider = "provider-a",
            model = "model-a",
            savedEffort = "medium"
        )
        assertFalse(unsupported.enabled)
        assertTrue(unsupported.options.isEmpty())
        assertEquals("Off", brainReasoningLabel("none"))
    }

    @Test
    fun dialogUsesSixtyPercentThenOnlyAvailableSpaceClamp() {
        assertEquals(600, brainDialogHeightPx(1000, 0, 0, 0))
        assertEquals(500, brainDialogHeightPx(1000, 0, 0, 500))
        assertEquals(600, brainDialogHeightPx(1000, 40, 20, 0))
        assertEquals(360, brainDialogHeightPx(1000, 40, 20, 600))
    }

    private fun provider(
        slug: String,
        models: List<String>,
        capability: BrainModelCapability? = BrainModelCapability(true, true)
    ) = BrainModelProvider(
        slug = slug,
        models = models,
        authenticated = true,
        capabilities = capability?.let { models.associateWith { _ -> it } } ?: emptyMap()
    )
}
