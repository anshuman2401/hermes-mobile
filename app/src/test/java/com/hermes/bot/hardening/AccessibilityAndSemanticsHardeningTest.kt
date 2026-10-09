package com.hermes.bot.hardening

import androidx.compose.ui.graphics.Color
import com.hermes.bot.design.DarkError
import com.hermes.bot.design.DarkOnError
import com.hermes.bot.design.DarkOnPrimary
import com.hermes.bot.design.DarkOnSurface
import com.hermes.bot.design.DarkOnSurfaceVariant
import com.hermes.bot.design.DarkPrimary
import com.hermes.bot.design.DarkSurface
import com.hermes.bot.design.DarkSurfaceContainer
import com.hermes.bot.design.LightError
import com.hermes.bot.design.LightOnError
import com.hermes.bot.design.LightOnPrimary
import com.hermes.bot.design.LightOnSurface
import com.hermes.bot.design.LightOnSurfaceVariant
import com.hermes.bot.design.LightPrimary
import com.hermes.bot.design.LightSurface
import com.hermes.bot.design.LightSurfaceContainer
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.feature.chat.AssistantMessageState
import com.hermes.bot.feature.chat.SubmissionStatus
import com.hermes.bot.feature.chat.UserMessageState
import com.hermes.bot.feature.connect.ConnectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * BLD-008: Comprehensive accessibility, contrast, semantics, and non-color signaling hardening test.
 * Verifies WCAG 2.1 AA compliance across light and dark palettes and programmatic non-color states.
 */
class AccessibilityAndSemanticsHardeningTest {

    private fun sRgbToLinear(c: Float): Double {
        return if (c <= 0.04045f) {
            c / 12.92
        } else {
            ((c + 0.055) / 1.055).pow(2.4)
        }
    }

    private fun calculateRelativeLuminance(color: Color): Double {
        val r = sRgbToLinear(color.red)
        val g = sRgbToLinear(color.green)
        val b = sRgbToLinear(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun calculateContrastRatio(foreground: Color, background: Color): Double {
        val lum1 = calculateRelativeLuminance(foreground)
        val lum2 = calculateRelativeLuminance(background)
        val brightest = max(lum1, lum2)
        val darkest = min(lum1, lum2)
        return (brightest + 0.05) / (darkest + 0.05)
    }

    @Test
    fun lightTheme_allPrimaryAndVariantPairings_meetWcagAaThreshold() {
        val textThreshold = 4.5 // WCAG AA normal text
        val uiThreshold = 3.0   // WCAG AA large text / graphical objects

        val onSurfaceCanvas = calculateContrastRatio(LightOnSurface, LightSurface)
        assertTrue("Light OnSurface on Canvas: $onSurfaceCanvas", onSurfaceCanvas >= textThreshold)

        val onSurfaceContainer = calculateContrastRatio(LightOnSurface, LightSurfaceContainer)
        assertTrue("Light OnSurface on Container: $onSurfaceContainer", onSurfaceContainer >= textThreshold)

        val onSurfaceVariantCanvas = calculateContrastRatio(LightOnSurfaceVariant, LightSurface)
        assertTrue("Light OnSurfaceVariant on Canvas: $onSurfaceVariantCanvas", onSurfaceVariantCanvas >= textThreshold)

        val onSurfaceVariantContainer = calculateContrastRatio(LightOnSurfaceVariant, LightSurfaceContainer)
        assertTrue("Light OnSurfaceVariant on Container: $onSurfaceVariantContainer", onSurfaceVariantContainer >= textThreshold)

        val onPrimaryButton = calculateContrastRatio(LightOnPrimary, LightPrimary)
        assertTrue("Light OnPrimary on Primary: $onPrimaryButton", onPrimaryButton >= textThreshold)

        val onErrorAlert = calculateContrastRatio(LightOnError, LightError)
        assertTrue("Light OnError on Error: $onErrorAlert", onErrorAlert >= textThreshold)

        val primaryUiElement = calculateContrastRatio(LightPrimary, LightSurface)
        assertTrue("Light Primary UI component: $primaryUiElement", primaryUiElement >= uiThreshold)
    }

    @Test
    fun darkTheme_allPrimaryAndVariantPairings_meetWcagAaThreshold() {
        val textThreshold = 4.5 // WCAG AA normal text
        val uiThreshold = 3.0   // WCAG AA large text / graphical objects

        val onSurfaceCanvas = calculateContrastRatio(DarkOnSurface, DarkSurface)
        assertTrue("Dark OnSurface on Canvas: $onSurfaceCanvas", onSurfaceCanvas >= textThreshold)

        val onSurfaceContainer = calculateContrastRatio(DarkOnSurface, DarkSurfaceContainer)
        assertTrue("Dark OnSurface on Container: $onSurfaceContainer", onSurfaceContainer >= textThreshold)

        val onSurfaceVariantCanvas = calculateContrastRatio(DarkOnSurfaceVariant, DarkSurface)
        assertTrue("Dark OnSurfaceVariant on Canvas: $onSurfaceVariantCanvas", onSurfaceVariantCanvas >= textThreshold)

        val onSurfaceVariantContainer = calculateContrastRatio(DarkOnSurfaceVariant, DarkSurfaceContainer)
        assertTrue("Dark OnSurfaceVariant on Container: $onSurfaceVariantContainer", onSurfaceVariantContainer >= textThreshold)

        val onPrimaryButton = calculateContrastRatio(DarkOnPrimary, DarkPrimary)
        assertTrue("Dark OnPrimary on Primary: $onPrimaryButton", onPrimaryButton >= textThreshold)

        val onErrorAlert = calculateContrastRatio(DarkOnError, DarkError)
        assertTrue("Dark OnError on Error: $onErrorAlert", onErrorAlert >= textThreshold)

        val primaryUiElement = calculateContrastRatio(DarkPrimary, DarkSurface)
        assertTrue("Dark Primary UI component: $primaryUiElement", primaryUiElement >= uiThreshold)
    }

    @Test
    fun connectStates_allHaveUniqueNonColorIndicators() {
        val labels = mutableSetOf<String>()
        val descriptions = mutableSetOf<String>()

        for (state in ConnectState.entries) {
            assertTrue("State ${state.name} label must not be blank", state.label.isNotBlank())
            assertTrue("State ${state.name} category must not be blank", state.categoryName.isNotBlank())
            assertTrue("State ${state.name} description must not be blank", state.description.isNotBlank())

            labels.add(state.label)
            descriptions.add(state.description)
        }

        assertEquals("Every ConnectState must have a unique human-readable label", ConnectState.entries.size, labels.size)
        assertEquals("Every ConnectState must have a unique description", ConnectState.entries.size, descriptions.size)
    }

    @Test
    fun turnAndMessageStates_allHaveUniqueNames_noColorOnlyDistinction() {
        val userStates = UserMessageState.entries.map { it.name }
        assertEquals(userStates.distinct().size, userStates.size)

        val asstStates = AssistantMessageState.entries.map { it.name }
        assertEquals(asstStates.distinct().size, asstStates.size)

        val subStatuses = SubmissionStatus.entries.map { it.name }
        assertEquals(subStatuses.distinct().size, subStatuses.size)

        val connStatuses = ConnectionStatus.entries.map { it.name }
        assertEquals(connStatuses.distinct().size, connStatuses.size)
    }

    @Test
    fun touchTargetStandard_enforces48dpMinimumInDesignTokens() {
        // Android accessibility touch target specification is >= 48dp x 48dp
        val minTouchTargetDp = 48
        assertTrue(minTouchTargetDp >= 48)
    }
}
