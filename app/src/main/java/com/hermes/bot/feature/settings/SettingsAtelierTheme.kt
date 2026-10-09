package com.hermes.bot.feature.settings

import androidx.compose.runtime.Composable
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.AtelierTheme

// Compatibility names keep existing Settings/Brain presentation unchanged.
internal val AtelierLight = com.hermes.bot.design.AtelierLight
internal val AtelierDark = com.hermes.bot.design.AtelierDark
internal val SettingsEditorial = com.hermes.bot.design.AtelierEditorial

@Composable
internal fun SettingsAtelierTheme(preference: AppThemePreference, content: @Composable () -> Unit) {
    AtelierTheme(preference = preference, content = content)
}
