package com.hermes.bot.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDestinationsTest {

    @Test
    fun shellScreen_definesExpectedShellDestinations() {
        val screens = ShellScreen.entries
        assertEquals(7, screens.size)
        assertTrue(screens.contains(ShellScreen.CONNECT))
        assertTrue(screens.contains(ShellScreen.SESSIONS))
        assertTrue(screens.contains(ShellScreen.CHAT))
        assertTrue(screens.contains(ShellScreen.BOT_CHANNEL))
        assertTrue(screens.contains(ShellScreen.BOT_SCREEN))
        assertTrue(screens.contains(ShellScreen.SETTINGS))
        assertTrue(screens.contains(ShellScreen.DEBUG_LOGS))
    }

    @Test
    fun shellScreen_firstLaunchIsConnect() {
        val initialScreen = ShellScreen.CONNECT
        assertEquals(ShellScreen.CONNECT, initialScreen)
    }

    @Test
    fun destinations_matchContractRoutes() {
        assertEquals("connect", Destination.Connect.route)
        assertEquals("sessions", Destination.Sessions.route)
        assertEquals("settings", Destination.Settings.route)
        assertEquals("chat/test-1", Destination.Chat("test-1").route)
        assertEquals("home", Destination.Home.route)
        assertEquals("work", Destination.Work.route)
        assertEquals("bots", Destination.Bots.route)
    }
}
