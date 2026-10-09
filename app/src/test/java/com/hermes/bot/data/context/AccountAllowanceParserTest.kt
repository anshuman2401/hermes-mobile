package com.hermes.bot.data.context

import org.junit.Assert.*
import org.junit.Test

class AccountAllowanceParserTest {
    @Test fun hostWindowsAndResetBalancePreserveTimezoneAndProvider() {
        val account = AccountAllowanceParser.parse(listOf("📈 Account limits", "Provider: openai-codex (Prolite)",
            "Session: 80% remaining (20% used) • resets in 2h 15m (2030-01-10 03:00 IST)",
            "Weekly: 36% remaining (64% used) • resets in 4d 4h (2030-01-10 05:37 IST)",
            "You have 2 resets banked - use /usage reset to activate"))
        assertEquals("openai-codex (Prolite)", account.provider)
        assertEquals(listOf("Session", "Weekly"), account.windows.map { it.label })
        assertEquals(listOf(80, 36), account.windows.map { it.remainingPercent })
        assertEquals("in 4d 4h (2030-01-10 05:37 IST)", account.windows[1].resetText)
        assertEquals(2, account.bankedResets)
        assertTrue(account.lines.isEmpty())
    }
    @Test fun omissionNullAndZeroNeverBecomeFabricatedQuotaOrResets() {
        val empty = AccountAllowanceParser.parse(emptyList())
        assertNull(empty.bankedResets)
        val a = AccountAllowanceParser.parse(listOf("Weekly: unavailable", "Session: 0% remaining (100% used)"))
        assertNull(a.windows[0].remainingPercent)
        assertEquals(0, a.windows[1].remainingPercent)
        assertNull(a.bankedResets)
    }
    @Test fun unknownFormatsRemainPlainTextAndBadPercentNeverDrivesMeter() {
        val a = AccountAllowanceParser.parse(listOf("Weekly: 101% remaining (0% used)", "Credit balance: 12 units"))
        assertTrue(a.windows.isEmpty())
        assertEquals(2, a.lines.size)
        assertEquals("Credit balance: 12 units", a.lines.last())
    }
    @Test fun boundedInputAndSecretsAreRedacted() {
        assertTrue(runCatching { AccountAllowanceParser.parse(List(33) { "Synthetic" }) }.isFailure)
        assertTrue(runCatching { AccountAllowanceParser.parse(listOf("x".repeat(1025))) }.isFailure)
        val a = AccountAllowanceParser.parse(listOf("Unavailable: password=synthetic-secret"))
        assertFalse(a.lines.single().contains("synthetic-secret"))
        assertFalse(a.toString().contains("password"))
    }
}
