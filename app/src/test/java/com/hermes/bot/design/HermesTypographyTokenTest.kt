package com.hermes.bot.design

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused S1 contract tests for the shared typography API and M3 compatibility map. */
class HermesTypographyTokenTest {

    @Test
    fun semanticScale_usesAlbertSansAndMeasuredMetrics() {
        assertStyle(HermesTextStyles.xs, 11.sp, 16.sp, FontWeight.Normal, HermesAlbertSans)
        assertStyle(HermesTextStyles.sm, 13.sp, 20.sp, FontWeight.Normal, HermesAlbertSans)
        assertStyle(HermesTextStyles.md, 15.sp, 22.sp, FontWeight.Normal, HermesAlbertSans)
        assertStyle(HermesTextStyles.lg, 17.sp, 24.sp, FontWeight.Medium, HermesAlbertSans)
        assertStyle(HermesTextStyles.xl, 20.sp, 28.sp, FontWeight.SemiBold, HermesAlbertSans)
        assertStyle(HermesTextStyles.xxl, 24.sp, 32.sp, FontWeight.SemiBold, HermesAlbertSans)
        assertStyle(HermesTextStyles.display, 32.sp, 40.sp, FontWeight.Bold, HermesAlbertSans)
    }

    @Test
    fun semanticRoles_pinReferenceStyles() {
        assertStyle(HermesTextStyles.assistantBody, 15.5.sp, 25.5.sp, FontWeight.Normal, HermesAlbertSans)
        assertStyle(HermesTextStyles.sessionTitle, 15.sp, 22.sp, FontWeight.SemiBold, HermesAlbertSans)
        assertSame(HermesTextStyles.xs, HermesTextStyles.sessionSecondary)
        assertStyle(
            HermesTextStyles.userBody,
            15.sp,
            21.5.sp,
            FontWeight.Normal,
            FontFamily.Default
        )
        assertEquals((-0.1).sp, HermesTextStyles.userBody.letterSpacing)
        assertStyle(
            HermesTextStyles.userLongTextAffordance,
            12.sp,
            16.sp,
            FontWeight.SemiBold,
            HermesAlbertSans
        )
    }

    @Test
    fun markdownRoles_pinReferenceStyles() {
        assertStyle(HermesTextStyles.markdownHeading1, 22.5.sp, 27.5.sp, FontWeight.Bold, HermesAlbertSans)
        assertStyle(HermesTextStyles.markdownHeading2, 19.5.sp, 24.5.sp, FontWeight.Bold, HermesAlbertSans)
        assertStyle(HermesTextStyles.markdownHeading3, 17.sp, 22.sp, FontWeight.Bold, HermesAlbertSans)
        assertStyle(HermesTextStyles.markdownHeading4, 15.5.sp, 21.sp, FontWeight.SemiBold, HermesAlbertSans)
        assertStyle(HermesTextStyles.markdownHeading5, 14.5.sp, 19.5.sp, FontWeight.SemiBold, HermesAlbertSans)
        assertStyle(HermesTextStyles.markdownHeading6, 13.5.sp, 18.5.sp, FontWeight.SemiBold, HermesAlbertSans)
    }

    @Test
    fun material3Roles_keepExistingNamesAndMetricsWithSharedFamily() {
        assertStyle(HermesTypography.titleLarge, 22.sp, 28.sp, FontWeight.SemiBold, FontFamily.Default)
        assertStyle(HermesTypography.bodyLarge, 16.sp, 24.sp, FontWeight.Normal, FontFamily.Default)
        assertStyle(HermesTypography.bodyMedium, 14.sp, 20.sp, FontWeight.Normal, FontFamily.Default)
        assertStyle(HermesTypography.labelSmall, 11.sp, 16.sp, FontWeight.Medium, FontFamily.Default)
    }

    @Test
    fun bundledAlbertSans_resources_areTrueTypeAndNonEmpty() {
        val resourceRoot = locate("app/src/main/res")
        val expected = mapOf(
            "font/albert_sans_regular.ttf" to "c7b3fe29e67c8f14234f5aa302e4fc608b8a02d44e3ffa7c95ace39333acee5f",
            "font/albert_sans_medium.ttf" to "eb14b0946caee7e89b394aefc552d0cdd046b3aa2b13a431d547402ecb08f8cd",
            "font/albert_sans_semibold.ttf" to "3125add821f294671f0be6fb026002fd3293503d96849f35ff0ae2d39c2ab250",
            "font/albert_sans_bold.ttf" to "581779d370937c2a6c08ea98c4685e028382d4d575346c307f4a73fb0be61d4a"
        )

        expected.forEach { (relativePath, expectedSha256) ->
            val font = File(resourceRoot, relativePath)
            assertTrue("missing font resource $font", font.isFile)
            assertTrue("font resource $font is empty", font.length() > 0)
            assertEquals("$font is not a TrueType file", "00010000", font.readBytes().take(4).toByteArray().toHex())
            assertEquals("unexpected checksum for $font", expectedSha256, sha256(font))
        }
    }

    @Test
    fun bundledNotice_isTheOfficialAlbertSansOflText() {
        val notice = locate("app/src/main/assets/licenses/AlbertSans-OFL.txt")
        assertTrue("missing Albert Sans OFL notice $notice", notice.isFile)
        val text = notice.readText()
        assertTrue(text.contains("SIL OPEN FONT LICENSE"))
        assertTrue(text.contains("Copyright 2023 The Albert Sans Project Authors"))
        assertEquals("7cb6865d2de1bcefcbc98659949228dc04f7dd900b857a579cfe849e1d6f8fc7", sha256(notice))
    }

    private fun locate(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = File(directory, path)
            if (candidate.exists()) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("could not locate $path from ${System.getProperty("user.dir")}")
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun assertStyle(
        actual: androidx.compose.ui.text.TextStyle,
        fontSize: androidx.compose.ui.unit.TextUnit,
        lineHeight: androidx.compose.ui.unit.TextUnit,
        weight: FontWeight,
        family: FontFamily
    ) {
        assertEquals(fontSize, actual.fontSize)
        assertEquals(lineHeight, actual.lineHeight)
        assertEquals(weight, actual.fontWeight)
        assertEquals(family, actual.fontFamily)
        assertTrue("font size must be specified", actual.fontSize.isSp)
        assertTrue("line height must be specified", actual.lineHeight.isSp)
    }
}
