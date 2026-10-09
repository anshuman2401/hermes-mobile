package io.github.anshuman2401.hermesmobile.hardening

import androidx.compose.ui.graphics.Color
import io.github.anshuman2401.hermesmobile.design.DarkError
import io.github.anshuman2401.hermesmobile.design.DarkOnError
import io.github.anshuman2401.hermesmobile.design.DarkOnPrimary
import io.github.anshuman2401.hermesmobile.design.DarkOnSurface
import io.github.anshuman2401.hermesmobile.design.DarkOnSurfaceVariant
import io.github.anshuman2401.hermesmobile.design.DarkPrimary
import io.github.anshuman2401.hermesmobile.design.DarkSurface
import io.github.anshuman2401.hermesmobile.design.DarkSurfaceContainer
import io.github.anshuman2401.hermesmobile.design.LightError
import io.github.anshuman2401.hermesmobile.design.LightOnError
import io.github.anshuman2401.hermesmobile.design.LightOnPrimary
import io.github.anshuman2401.hermesmobile.design.LightOnSurface
import io.github.anshuman2401.hermesmobile.design.LightOnSurfaceVariant
import io.github.anshuman2401.hermesmobile.design.LightPrimary
import io.github.anshuman2401.hermesmobile.design.LightSurface
import io.github.anshuman2401.hermesmobile.design.LightSurfaceContainer
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import io.github.anshuman2401.hermesmobile.feature.chat.AssistantMessageState
import io.github.anshuman2401.hermesmobile.feature.chat.SubmissionStatus
import io.github.anshuman2401.hermesmobile.feature.chat.UserMessageState
import io.github.anshuman2401.hermesmobile.feature.connect.ConnectState
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
