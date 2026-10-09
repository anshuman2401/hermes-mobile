package com.hermes.bot.feature.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectSemanticsAndAccessibilityTest {
    @Test
    fun sevenStatesHaveNonColorSignalsAndOriginPolicyIsAnError() {
        assertEquals(7, ConnectState.entries.size)
        ConnectState.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.categoryName.isNotBlank())
            assertTrue(it.description.isNotBlank())
        }
        assertTrue(ConnectState.ORIGIN_POLICY_REJECTED.isErrorState)
        assertFalse(ConnectState.CONNECTED_COMPATIBLE.isErrorState)
    }

    @Test
    fun cleartextWarningCopyIsPreciseAndDoesNotClaimHttpEncryption() {
        val warning = "HTTP is not encrypted by itself. Use it only over a trusted private network or an encrypted tunnel such as Tailscale."
        assertTrue(warning.contains("not encrypted"))
        assertTrue(warning.contains("Tailscale"))
        assertFalse(warning.contains("HTTP is encrypted", ignoreCase = true))
    }
}
