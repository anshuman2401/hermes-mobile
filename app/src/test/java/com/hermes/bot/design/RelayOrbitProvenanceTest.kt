package com.hermes.bot.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of the Relay Orbit adaptive icon provenance record.
 * Required by BLD-004 deliverable 4 and acceptance gates.
 */
class RelayOrbitProvenanceTest {

    @Test
    fun relayOrbit_hasDocumentedProvenance() {
        assertEquals("Relay Orbit", RelayOrbitProvenance.ICON_NAME)
        assertEquals("Hermes Bot Android Team (BLD-004)", RelayOrbitProvenance.CREATOR)
        assertTrue(
            "Concept must describe two unequal rounded message nodes joined by an orbital relay path",
            RelayOrbitProvenance.CONCEPT.contains("message nodes") &&
                RelayOrbitProvenance.CONCEPT.contains("orbital")
        )
        assertTrue(
            "Provenance note must confirm 0% copied Hermes Go or third-party assets",
            RelayOrbitProvenance.PROVENANCE_NOTE.contains("0% copied")
        )
    }

    @Test
    fun relayOrbit_doesNotReferenceForbiddenBrandAssets() {
        val note = RelayOrbitProvenance.PROVENANCE_NOTE.lowercase()
        assertFalse("Must not claim Hermes Go reuse", note.contains("hermes go reuse"))
        assertFalse("Must not claim third party mascot", note.contains("third-party mascot included"))
    }
}
