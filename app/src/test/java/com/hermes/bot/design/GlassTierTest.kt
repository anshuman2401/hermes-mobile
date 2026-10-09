package com.hermes.bot.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CHG-008 S2: the D6 tier seam is a plain constructor-injected JUnit class
 * (no Robolectric, no Android framework). Pins the V1 contract:
 * a single TRANSLUCENT_FILL tier for the entire supported API range, a blur
 * seam that exists only for the future blur change, and the skeptic-verified
 * 0.85 fill alpha used by the dark glass blend (#181A22 over #0F1015).
 */
class GlassTierTest {

    @Test
    fun tierSelection_minSdk26_isTranslucentFill() {
        assertEquals(GlassTier.TRANSLUCENT_FILL, GlassTier.fromApiLevel(26))
    }

    @Test
    fun tierSelection_api30_isTranslucentFill() {
        assertEquals(GlassTier.TRANSLUCENT_FILL, GlassTier.fromApiLevel(30))
    }

    @Test
    fun tierSelection_api31_blurTierExists_butV1NeverSelectsItInRendering() {
        // D5: the tier hook exists for the future blur change; V1 rendering
        // consumes only TRANSLUCENT_FILL regardless of device tier. This test
        // pins the mapping; the V1 rendering contract is pinned by
        // glassDefaults_v1_alwaysUsesTranslucentTier below.
        assertEquals(GlassTier.BLUR, GlassTier.fromApiLevel(31))
        assertEquals(GlassTier.BLUR, GlassTier.fromApiLevel(34))
    }

    @Test
    fun glassDefaults_v1_alwaysUsesTranslucentTier() {
        // Guards the D5 restructure: nobody may reintroduce RenderEffect /
        // blur into the V1 modifier. If this fails, the blur stretch goal has
        // leaked into V1 — revert; blur ships in its own change with its own
        // validation (captured-background-layer strategy).
        assertEquals(GlassTier.TRANSLUCENT_FILL, GlassDefaults.v1Tier)
    }

    @Test
    fun glassDefaults_fillAlpha_matchesSkepticBlendContract() {
        // The skeptic's #181A22 blend assumed exactly 85% #1A1C24 over #0F1015.
        assertEquals(0.85f, GlassDefaults.FILL_ALPHA, 0.0001f)
    }

    @Test
    fun glassDefaults_lightTheme_rendersOpaqueFill() {
        // CHG-008 S2 boundary: light theme renders EXACTLY as before — opaque
        // fill, no decorative outline. Dark theme keeps the 0.85 frost.
        assertEquals(1f, GlassDefaults.fillAlphaFor(isDark = false), 0.0001f)
        assertEquals(0.85f, GlassDefaults.fillAlphaFor(isDark = true), 0.0001f)
    }
}
