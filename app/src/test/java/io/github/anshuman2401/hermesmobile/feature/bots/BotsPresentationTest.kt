package io.github.anshuman2401.hermesmobile.feature.bots

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BotsPresentationTest {
    @Test fun boardHidesOnlyCronAndPreservesServerOrderAndInput() {
        fun session(id: String, source: String?) = io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary(id, null, null, null, 0, source)
        val input = listOf(session("a", "cron"), session("b", null), session("c", " CRON "),
            session("d", "chat"), session("e", ""), session("f", "cron-other"))
        val visible = botBoardConversations(input)
        assertEquals(listOf("b", "d", "e", "f"), visible.map { it.id })
        assertEquals(6, input.size)
        assertEquals(4, visible.size)
        assertTrue(botBoardConversations(listOf(session("a", "cron"))).isEmpty())
        assertTrue(botBoardConversations(emptyList()).isEmpty())
    }

    @Test fun nativeInitialUsesFirstTrimmedCodePoint() {
        assertEquals("A", botInitial(" alice "))
        assertEquals("🤖", botInitial("🤖 bot"))
        assertEquals("?", botInitial(" "))
    }
    @Test fun injectableDecodeFallsBackWithoutFailingRoster() = runTest {
        var calls = 0
        assertNull(decodeBotAvatar<String>(null) { calls++; "image" })
        assertEquals(0, calls)
        assertNull(decodeBotAvatar<String>(byteArrayOf(1)) { error("synthetic decode failure") })
        assertEquals("image", decodeBotAvatar(byteArrayOf(1)) { "image" })
        try {
            decodeBotAvatar<String>(byteArrayOf(1)) { throw CancellationException("cancelled") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
