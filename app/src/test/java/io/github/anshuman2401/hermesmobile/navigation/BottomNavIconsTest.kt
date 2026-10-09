package io.github.anshuman2401.hermesmobile.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * JVM unit test: each bottom-nav tab maps to a distinct ImageVector via
 * BottomNavIcons.iconFor(tab). Guards against a regression where the
 * hand-built path vectors (Chats/Work/Bots) collapse to empty/unrenderable
 * instances that compare equal or fail to construct.
 */
class BottomNavIconsTest {

    @Test
    fun iconFor_returnsExpectedVectorPerTab() {
        assertSame(BottomNavIcons.Chats, BottomNavIcons.iconFor(BottomNavTab.CHATS))
        assertSame(BottomNavIcons.Work, BottomNavIcons.iconFor(BottomNavTab.WORK))
        assertSame(BottomNavIcons.Bots, BottomNavIcons.iconFor(BottomNavTab.BOTS))
    }

    @Test
    fun iconFor_threeVectorsAreMutuallyDistinct() {
        val chats = BottomNavIcons.iconFor(BottomNavTab.CHATS)
        val work = BottomNavIcons.iconFor(BottomNavTab.WORK)
        val bots = BottomNavIcons.iconFor(BottomNavTab.BOTS)

        val vectors = listOf(chats, work, bots)
        assertEquals(3, vectors.toSet().size)

        assertNotEquals(chats, work)
        assertNotEquals(chats, bots)
        assertNotEquals(work, bots)
    }
}