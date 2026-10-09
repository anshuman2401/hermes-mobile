package com.hermes.bot.navigation

sealed interface Destination {
    val route: String

    data object Connect : Destination {
        override val route: String = "connect"
    }

    data object Sessions : Destination {
        override val route: String = "sessions"
    }

    data class Chat(val sessionId: String) : Destination {
        override val route: String = "chat/$sessionId"
    }

    data object Settings : Destination {
        override val route: String = "settings"
    }

    data object Home : Destination {
        override val route: String = "home"
    }

    data object Work : Destination {
        override val route: String = "work"
    }

    data object Bots : Destination {
        override val route: String = "bots"
    }
}
