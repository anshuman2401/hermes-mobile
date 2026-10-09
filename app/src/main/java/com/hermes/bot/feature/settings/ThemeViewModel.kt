package com.hermes.bot.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.hermes.bot.design.AppThemePreference
import kotlinx.coroutines.flow.StateFlow

/**
 * Local presentation preference holder that survives ordinary Activity recreation
 * via SavedStateHandle. Strictly memory/local state; no credentials or network persistence.
 */
class ThemeViewModel(
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    companion object {
        const val KEY_THEME_PREFERENCE = "hermes_local_theme_preference"
    }

    private val _themePreference = savedStateHandle.getStateFlow(
        key = KEY_THEME_PREFERENCE,
        initialValue = AppThemePreference.SYSTEM.name
    )

    val themePreference: StateFlow<String> = _themePreference

    fun currentThemePreference(): AppThemePreference {
        return try {
            AppThemePreference.valueOf(_themePreference.value)
        } catch (_: Exception) {
            AppThemePreference.SYSTEM
        }
    }

    fun setThemePreference(preference: AppThemePreference) {
        savedStateHandle[KEY_THEME_PREFERENCE] = preference.name
    }
}
