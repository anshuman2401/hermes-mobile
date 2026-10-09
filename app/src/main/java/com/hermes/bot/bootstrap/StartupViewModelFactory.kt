package com.hermes.bot.bootstrap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.bot.feature.connect.ConnectViewModel
import com.hermes.bot.feature.sessions.SessionsViewModel

/** Activity-retained startup owners: configuration changes must not start another socket attempt. */
internal fun startupViewModelFactory(
    container: AppContainer?,
    savedStateHandle: CreationExtras.() -> SavedStateHandle = { createSavedStateHandle() }
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        container?.createConnectViewModel(savedStateHandle()) ?: ConnectViewModel(savedStateHandle())
    }
    initializer {
        container?.createSessionsViewModel(savedStateHandle()) ?: SessionsViewModel()
    }
}
