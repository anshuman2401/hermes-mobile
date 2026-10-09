package com.hermes.bot.feature.settings

import com.hermes.bot.design.calculateContrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAtelierContrastTest {
    @Test fun settingsTextAndControlsKeepContrastInBothThemes() {
        for (palette in listOf(AtelierLight, AtelierDark)) {
            for (surface in listOf(palette.canvas, palette.panel, if (palette == AtelierLight) InsightsHeroLight else InsightsHeroDark)) {
                for (foreground in listOf(palette.ink, palette.muted, palette.accent, palette.connected, palette.destructive)) {
                    val ratio = calculateContrastRatio(foreground, surface)
                    assertTrue("Settings text contrast $ratio must be at least 4.5", ratio >= 4.5)
                }
            }
        }
    }
}
