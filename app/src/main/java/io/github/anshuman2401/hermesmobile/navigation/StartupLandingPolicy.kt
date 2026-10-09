package io.github.anshuman2401.hermesmobile.navigation

import io.github.anshuman2401.hermesmobile.feature.connect.ConnectState
import io.github.anshuman2401.hermesmobile.feature.connect.StartupPresentation

internal object StartupLandingPolicy {
    fun initialScreen(startup: StartupPresentation) =
        if (startup == StartupPresentation.SESSIONS) ShellScreen.SESSIONS else ShellScreen.CONNECT

    fun requiresSignIn(startup: StartupPresentation, connection: ConnectState) =
        startup == StartupPresentation.SIGN_IN || (startup != StartupPresentation.SESSIONS && connection == ConnectState.AUTH_REQUIRED)

    fun remoteActionsAllowed(connection: ConnectState) = connection == ConnectState.CONNECTED_COMPATIBLE
}
