package io.github.anshuman2401.hermesmobile.navigation

import org.junit.Assert.*
import org.junit.Test

class BotChannelNavigationTest {
    @Test fun backAndCollectorDisposalWorkAtBothWidths() {
        for (expanded in listOf(false, true)) {
            assertEquals(BackPressAction.NavigateToChannel, BackPressPolicy.decide(ShellScreen.CHAT, expanded, true))
            assertEquals(BackPressAction.NavigateToBots, BackPressPolicy.decide(ShellScreen.BOT_CHANNEL, expanded))
            assertEquals(BackPressAction.NavigateToSessions, BackPressPolicy.decide(ShellScreen.CHAT, expanded))
            val decision = ChatShellCleanupPolicy.decide(ShellScreen.BOT_CHANNEL, expanded, "synthetic-session", "synthetic-session")
            assertEquals("synthetic-session", decision.clearKey); assertNull(decision.nextTrackedKey)
        }
    }
}
