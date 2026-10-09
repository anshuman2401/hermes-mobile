package com.hermes.bot.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionsRowPresentationPolicyTest {
    @Test
    fun titleInitial_usesDisplayedTitleAndSafeFallbacks() {
        assertEquals("S", SessionsRowPresentationPolicy.titleInitial(null, "alpha"))
        assertEquals("S", SessionsRowPresentationPolicy.titleInitial("   ", "alpha"))
        assertEquals("S", SessionsRowPresentationPolicy.titleInitial("", "   "))
        assertEquals("Λ", SessionsRowPresentationPolicy.titleInitial("λambda", "alpha"))
        assertEquals("中", SessionsRowPresentationPolicy.titleInitial("中文", "alpha"))
        assertEquals("?", SessionsRowPresentationPolicy.titleInitial("😀 hello", "alpha"))
        assertEquals("?", SessionsRowPresentationPolicy.initialForDisplayedTitle("   "))
    }

    @Test
    fun displayedTitle_trimsRealTitleAndUsesStableFallback() {
        assertEquals("Hermes Bot", SessionsRowPresentationPolicy.displayedTitle(" Hermes Bot ", "alpha"))
        assertEquals("Session alpha", SessionsRowPresentationPolicy.displayedTitle(null, " alpha "))
        assertEquals("Session conversation", SessionsRowPresentationPolicy.displayedTitle(" ", " "))
    }

    @Test
    fun pinLabel_isFutureFeatureWithoutActionClaim() {
        assertEquals("Pin conversation", SessionsRowPresentationPolicy.pinAccessibilityLabel(false))
        assertEquals("Unpin conversation", SessionsRowPresentationPolicy.pinAccessibilityLabel(true))
    }
}
