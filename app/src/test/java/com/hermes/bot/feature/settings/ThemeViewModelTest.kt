package com.hermes.bot.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.hermes.bot.design.AppThemePreference
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeViewModelTest {

    @Test
    fun themeViewModel_defaultsToSystemPreference() {
        val viewModel = ThemeViewModel()
        assertEquals(AppThemePreference.SYSTEM, viewModel.currentThemePreference())
        assertEquals(AppThemePreference.SYSTEM.name, viewModel.themePreference.value)
    }

    @Test
    fun themeViewModel_updatesPreferenceToLight() {
        val viewModel = ThemeViewModel()
        viewModel.setThemePreference(AppThemePreference.LIGHT)
        assertEquals(AppThemePreference.LIGHT, viewModel.currentThemePreference())
        assertEquals(AppThemePreference.LIGHT.name, viewModel.themePreference.value)
    }

    @Test
    fun themeViewModel_updatesPreferenceToDark() {
        val viewModel = ThemeViewModel()
        viewModel.setThemePreference(AppThemePreference.DARK)
        assertEquals(AppThemePreference.DARK, viewModel.currentThemePreference())
        assertEquals(AppThemePreference.DARK.name, viewModel.themePreference.value)
    }

    @Test
    fun themeViewModel_survivesActivityRecreationViaSavedStateHandle() {
        // Initial activity lifecycle: user selects DARK
        val savedStateHandle = SavedStateHandle()
        val initialViewModel = ThemeViewModel(savedStateHandle)
        initialViewModel.setThemePreference(AppThemePreference.DARK)
        assertEquals(AppThemePreference.DARK, initialViewModel.currentThemePreference())

        // Simulated Activity recreation: new ViewModel created with the retained SavedStateHandle
        val recreatedViewModel = ThemeViewModel(savedStateHandle)
        assertEquals(AppThemePreference.DARK, recreatedViewModel.currentThemePreference())
        assertEquals(AppThemePreference.DARK.name, recreatedViewModel.themePreference.value)
    }
}
