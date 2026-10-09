package io.github.anshuman2401.hermesmobile.design

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAtelierContrastTest {
    @Test fun chatPaletteReplacesOldContainerRolesAndRetainsReadableTextInBothModes() {
        for (dark in listOf(false, true)) {
            val c = chatAtelierColors(if (dark) darkColorScheme() else lightColorScheme(), dark)
            val p = if (dark) AtelierDark else AtelierLight
            assertEquals(p.canvas, c.background)
            assertEquals(p.panel, c.surfaceContainerLow)
            assertEquals(Color.Transparent, c.surfaceTint)
            assertEquals(c.primaryContainer, c.secondaryContainer)
            assertEquals(c.primaryContainer, c.tertiaryContainer)
            for ((foreground, background) in listOf(
                c.onSurface to c.surface, c.onSurfaceVariant to c.background,
                c.onSurfaceVariant to c.surfaceContainerLow,
                c.onPrimaryContainer to c.primaryContainer,
                c.onPrimary to c.primary, c.onError to c.error,
                c.onErrorContainer to c.errorContainer,
                c.primary to c.primaryContainer, c.primary to c.surfaceContainerHighest,
                c.onSurfaceVariant to c.surfaceContainerHighest, c.error to c.surfaceContainerHighest
            )) {
                val a = foreground.luminance(); val b = background.luminance()
                val contrast = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
                assertTrue("dark=$dark contrast=$contrast must meet AA", contrast >= 4.5f)
            }
        }
    }
}
