package io.github.anshuman2401.hermesmobile.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for bottom navigation bar items and state machine.
 *
 * Covers requirements:
 * - exactly 3 tabs in order Chat/Work/Bot
 * - Chats selected by default
 * - tab selection switches destination
 */
class BottomNavigationTest {

    @Test
    fun exactlyThreeTabsInOrderChatWorkBot() {
        assertEquals(listOf(BottomNavTab.CHATS, BottomNavTab.WORK, BottomNavTab.BOTS), BottomNavTab.entries)
    }

    @Test
    fun chatsSelectedByDefault() {
        assertEquals(BottomNavTab.CHATS, BottomNavTab.DEFAULT)
        val state = BottomNavState()
        assertEquals(BottomNavTab.CHATS, state.currentTab)
        assertEquals(Destination.Sessions, state.currentDestination)
    }

    @Test
    fun tabSelectionSwitchesDestination() {
        val state = BottomNavState()
        assertEquals(BottomNavTab.CHATS, state.currentTab)
        assertEquals(Destination.Sessions, state.currentDestination)

        // Select Work tab
        state.selectTab(BottomNavTab.WORK)
        assertEquals(BottomNavTab.WORK, state.currentTab)
        assertEquals(Destination.Work, state.currentDestination)

        // Select Bots tab
        state.selectTab(BottomNavTab.BOTS)
        assertEquals(BottomNavTab.BOTS, state.currentTab)
        assertEquals(Destination.Bots, state.currentDestination)

        // Switch back to Chats tab
        state.selectTab(BottomNavTab.CHATS)
        assertEquals(BottomNavTab.CHATS, state.currentTab)
        assertEquals(Destination.Sessions, state.currentDestination)
    }

    @Test
    fun tabRoutesAndDestinationsMatchContract() {
        assertEquals("chats", BottomNavTab.CHATS.route)
        assertEquals("work", BottomNavTab.WORK.route)
        assertEquals("bots", BottomNavTab.BOTS.route)

        assertEquals("sessions", Destination.Sessions.route)
        assertEquals("work", Destination.Work.route)
        assertEquals("bots", Destination.Bots.route)

        assertEquals(Destination.Sessions, BottomNavTab.CHATS.toDestination())
        assertEquals(Destination.Work, BottomNavTab.WORK.toDestination())
        assertEquals(Destination.Bots, BottomNavTab.BOTS.toDestination())
    }

    @Test
    fun initialStateCustomTabIsRespected() {

        val workState = BottomNavState(initialTab = BottomNavTab.WORK)
        assertEquals(BottomNavTab.WORK, workState.currentTab)
        assertEquals(Destination.Work, workState.currentDestination)
    }
}
