package com.hermes.bot.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hermes.bot.R

/** The bundled, deterministic Albert Sans family used by semantic Hermes styles. */
val HermesAlbertSans = FontFamily(
    Font(R.font.albert_sans_regular, FontWeight.Normal),
    Font(R.font.albert_sans_medium, FontWeight.Medium),
    Font(R.font.albert_sans_semibold, FontWeight.SemiBold),
    Font(R.font.albert_sans_bold, FontWeight.Bold)
)

/**
 * Shared semantic typography tokens for the measured Hermes scale.
 *
 * Feature slices select these tokens explicitly. The Material 3 mapping below
 * keeps the existing role names available while consumers migrate.
 */
object HermesTextStyles {
    val xs = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
    val sm = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp
    )
    val md = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    )
    val lg = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp
    )
    val xl = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    )
    val xxl = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    )
    val display = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    )

    val assistantBody = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp,
        lineHeight = 25.5.sp
    )
    val sessionTitle = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp
    )
    val sessionSecondary = xs

    /** The reference call site omitted a family; preserve that distinction. */
    val userBody = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.5.sp,
        letterSpacing = (-0.1).sp
    )
    val userLongTextAffordance = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )

    val markdownHeading1 = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.Bold,
        fontSize = 22.5.sp,
        lineHeight = 27.5.sp
    )
    val markdownHeading2 = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.Bold,
        fontSize = 19.5.sp,
        lineHeight = 24.5.sp
    )
    val markdownHeading3 = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp
    )
    val markdownHeading4 = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        lineHeight = 21.sp
    )
    val markdownHeading5 = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.5.sp,
        lineHeight = 19.5.sp
    )
    val markdownHeading6 = TextStyle(
        fontFamily = HermesAlbertSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 18.5.sp
    )
}

/**
 * Material 3 compatibility mapping. Existing role names, family, and metrics
 * remain stable in S1; feature slices opt into [HermesTextStyles] one surface
 * at a time.
 */
val HermesTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)
