package com.hermes.bot

import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.navigation.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationBaselineTest {

    @Test
    fun sessionHandle_retainsStoredAndRuntimeIdentifiers() {
        val handle = SessionHandle(
            storedSessionId = "durable-123",
            runtimeSessionId = "runtime-456"
        )
        assertEquals("durable-123", handle.storedSessionId)
        assertEquals("runtime-456", handle.runtimeSessionId)
    }

    @Test
    fun sessionHandle_allowsNullRuntimeIdentifierBeforeAttachment() {
        val handle = SessionHandle(storedSessionId = "durable-123")
        assertEquals("durable-123", handle.storedSessionId)
        assertNull(handle.runtimeSessionId)
    }

    @Test
    fun navigationDestinations_haveDistinctExpectedRoutes() {
        assertEquals("connect", Destination.Connect.route)
        assertEquals("sessions", Destination.Sessions.route)
        assertEquals("settings", Destination.Settings.route)
        assertEquals("chat/sess-789", Destination.Chat("sess-789").route)
    }

    @Test
    fun connectionStatus_definesExpectedLifecycleStates() {
        val states = ConnectionStatus.entries
        assertTrue(states.contains(ConnectionStatus.OFFLINE))
        assertTrue(states.contains(ConnectionStatus.CONNECTING))
        assertTrue(states.contains(ConnectionStatus.AUTHENTICATION_REQUIRED))
        assertTrue(states.contains(ConnectionStatus.CONNECTED))
        assertTrue(states.contains(ConnectionStatus.FAILED))
    }
}
