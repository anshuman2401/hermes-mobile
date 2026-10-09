package com.hermes.bot.navigation

import com.hermes.bot.feature.connect.ConnectState
import com.hermes.bot.feature.connect.StartupPresentation

internal object StartupLandingPolicy {
    fun initialScreen(startup: StartupPresentation) =
        if (startup == StartupPresentation.SESSIONS) ShellScreen.SESSIONS else ShellScreen.CONNECT

    fun requiresSignIn(startup: StartupPresentation, connection: ConnectState) =
        startup == StartupPresentation.SIGN_IN || (startup != StartupPresentation.SESSIONS && connection == ConnectState.AUTH_REQUIRED)

    fun remoteActionsAllowed(connection: ConnectState) = connection == ConnectState.CONNECTED_COMPATIBLE
}
