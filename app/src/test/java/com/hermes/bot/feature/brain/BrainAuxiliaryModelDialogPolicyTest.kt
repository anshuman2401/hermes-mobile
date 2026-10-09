package com.hermes.bot.feature.brain

import com.hermes.bot.data.brain.BrainAuxiliaryAssignment
import com.hermes.bot.data.brain.BrainAuxiliaryDraft
import com.hermes.bot.data.brain.BrainAuxiliaryTask
import com.hermes.bot.data.brain.BrainAuxiliaryTaskRow
import com.hermes.bot.data.brain.BrainModelCapability
import com.hermes.bot.data.brain.BrainModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainAuxiliaryModelDialogPolicyTest {
    @Test
    fun sharedProviderModelSearchAndExpansionPolicyAreUsedForAuxiliaryModels() {
        val providers = listOf(
            provider("zeta", listOf("plain")),
            provider("Alpha Cloud", listOf("vision-v1", "text-v2")),
            provider("omega", listOf("other"))
        )

        val providerMatch = filterBrainModelProviders(providers, "alpha")
        assertEquals(listOf("Alpha Cloud"), providerMatch.map { it.provider.slug })
        assertEquals(listOf("vision-v1", "text-v2"), providerMatch.single().models)

        val modelMatch = filterBrainModelProviders(providers, "V2")
        assertEquals(listOf("text-v2"), modelMatch.single().models)

        val expanded = BrainDialogExpansionPolicy.toggle(BrainExpansionState(), "Alpha Cloud")
        val searching = BrainDialogExpansionPolicy.beginSearch(expanded)
        assertTrue(searching.searching)
        assertEquals("Alpha Cloud", searching.beforeSearch)
        assertEquals("Alpha Cloud", BrainDialogExpansionPolicy.clearSearch(searching).expandedProvider)
    }

    @Test
    fun auxiliaryDraftNeverPinsAutoToMainAndKeepsUnknownLegacyValue() {
        val auto = BrainAuxiliaryAssignment(BrainAuxiliaryTask.Vision, "auto", "")
        assertEquals(BrainAuxiliaryDraft.Auto, auto.toDraft())

        val legacy = BrainAuxiliaryAssignment(BrainAuxiliaryTask.Vision, "auto", "legacy-model")
        assertEquals(BrainAuxiliaryDraft.Concrete("auto", "legacy-model"), legacy.toDraft())
        assertEquals("auto/legacy-model", BrainAuxiliaryTaskRow(BrainAuxiliaryTask.Vision, legacy).currentDisplay())

        val main = BrainAuxiliaryAssignment(BrainAuxiliaryTask.Vision, "main", "main-model")
        assertEquals(BrainAuxiliaryDraft.Concrete("main", "main-model"), main.toDraft())
    }

    @Test
    fun endpointBackedProviderIsDistinguishableFromOrdinaryAuthenticatedProvider() {
        val endpoint = provider("custom", listOf("endpoint-model"), requiresEndpointPassthrough = true)
        val ordinary = provider("ordinary", listOf("ordinary-model"))
        assertTrue(endpoint.authenticated)
        assertTrue(endpoint.requiresEndpointPassthrough)
        assertTrue(ordinary.authenticated)
        assertFalse(ordinary.requiresEndpointPassthrough)
    }

    @Test
    fun geometryIsSixtyPercentAndClampsOnlyToInsetImeAvailableSpace() {
        assertEquals(600, brainDialogHeightPx(1000, 0, 0, 0))
        assertEquals(500, brainDialogHeightPx(1000, 0, 0, 500))
        assertEquals(600, brainDialogHeightPx(1000, 40, 20, 0))
        assertEquals(360, brainDialogHeightPx(1000, 40, 20, 600))
    }

    private fun provider(
        slug: String,
        models: List<String>,
        requiresEndpointPassthrough: Boolean = false
    ) = BrainModelProvider(
        slug = slug,
        models = models,
        authenticated = true,
        capabilities = models.associateWith { BrainModelCapability(true, true) },
        requiresEndpointPassthrough = requiresEndpointPassthrough
    )
}
