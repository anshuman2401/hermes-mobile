package io.github.anshuman2401.hermesmobile.feature.sessions

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import io.github.anshuman2401.hermesmobile.design.atelierTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsTypographyTest {
    @Test fun explicitAtelierRolesReplaceBlanketReduction() {
        val type = atelierTypography(Typography())
        assertEquals(14.sp, type.titleSmall.fontSize)
        assertEquals(20.sp, type.titleSmall.lineHeight)
        assertEquals(12.sp, type.bodySmall.fontSize)
        assertEquals(18.sp, type.bodySmall.lineHeight)
        assertEquals(11.sp, type.labelSmall.fontSize)
    }

    @Test fun systemFontScalingRemainsEffective_andTitleGetsAnotherLine() {
        val title = atelierTypography(Typography()).titleSmall
        val normal = with(Density(2f, 1f)) { title.fontSize.toPx() }
        val large = with(Density(2f, 1.5f)) { title.fontSize.toPx() }
        assertEquals(28f, normal, 0.001f)
        assertTrue(large > normal)
        assertEquals(1, sessionsTitleMaxLines(1f))
        assertEquals(1, sessionsTitleMaxLines(1.2f))
        assertEquals(2, sessionsTitleMaxLines(1.5f))
        assertEquals(2, sessionsTitleMaxLines(2f))
    }
}
