package com.hermes.bot.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * CHAT-009 (frozen plan v1, FZ-4/6/7/8/11/13): pure JVM tests for the
 * [ChatDaySeparatorPolicy] display-list derivation. Fixed zone ("Asia/Kolkata")
 * and fixed `today` (2026-09-19) for determinism; no Compose, no Robolectric.
 * All instants are synthetic.
 */
class ChatDaySeparatorPolicyTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 9, 19)

    private fun userEntry(id: String, timestampMillis: Long): TraceDisplayEntry.Raw =
        TraceDisplayEntry.Raw(
            UserMessage(
                id = id,
                text = "body-$id",
                state = UserMessageState.SENT,
                timestamp = timestampMillis
            )
        )

    private fun assistantEntry(id: String, timestampMillis: Long): TraceDisplayEntry.Raw =
        TraceDisplayEntry.Raw(
            AssistantMessage(
                id = id,
                text = "body-$id",
                state = AssistantMessageState.COMPLETE,
                timestamp = timestampMillis
            )
        )

    private fun toolEntry(id: String, timestampMillis: Long): TraceDisplayEntry.Raw =
        TraceDisplayEntry.Raw(
            ToolActivity(
                id = id,
                toolId = "tool-$id",
                name = "tool",
                status = ToolStatus.COMPLETED,
                timestamp = timestampMillis
            )
        )

    private fun header(): TraceDisplayEntry.Header =
        TraceDisplayEntry.Header(
            TraceState(turnId = "turn-1", startedAtMillis = 1_000L)
        )

    // Synthetic instants (epoch millis), all in the fixed zone.
    // 2026-09-19T04:30:00Z == 2026-09-19 10:00 IST.
    private val todayMorning: Long = Instant.parse("2026-09-19T04:30:00Z").toEpochMilli()
    private val todayAfternoon: Long = Instant.parse("2026-09-19T09:45:00Z").toEpochMilli()
    // 2026-09-18T10:00:00Z == 2026-09-18 15:30 IST.
    private val yesterday: Long = Instant.parse("2026-09-18T10:00:00Z").toEpochMilli()
    // 2026-09-12T10:00:00Z == 2026-09-12 15:30 IST (same year as today).
    private val sameYearOld: Long = Instant.parse("2026-09-12T10:00:00Z").toEpochMilli()
    // 2025-09-12T10:00:00Z == 2025-09-12 15:30 IST (prior year).
    private val priorYearOld: Long = Instant.parse("2025-09-12T10:00:00Z").toEpochMilli()

    private fun epochDay(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    private fun separators(out: List<TraceDisplayEntry>): List<TraceDisplayEntry.DaySeparator> =
        out.filterIsInstance<TraceDisplayEntry.DaySeparator>()

    private fun rawIds(out: List<TraceDisplayEntry>): List<String> =
        out.filterIsInstance<TraceDisplayEntry.Raw>().map { it.entry.id }

    // --- FZ-6: the first timestamped entry always gets a separator ---

    @Test
    fun firstEntry_alwaysGetsSeparator_whenTimestampPresent() {
        val out = ChatDaySeparatorPolicy.withDaySeparators(
            listOf(userEntry("u1", todayMorning)),
            zone,
            today
        )
        assertEquals(2, out.size)
        val sep = out.first() as TraceDisplayEntry.DaySeparator
        assertEquals("Today", sep.label)
        assertEquals("day-sep-${epochDay(todayMorning)}-r0", sep.key)
        assertEquals(listOf("u1"), rawIds(out))
    }

    @Test
    fun separatorInserted_onCalendarDayChange_only() {
        val out = ChatDaySeparatorPolicy.withDaySeparators(
            listOf(
                userEntry("u1", todayMorning),
                userEntry("u2", todayAfternoon),
                userEntry("u3", yesterday)
            ),
            zone,
            today
        )
        // One separator before the first same-day pair, one before the
        // first next-day row; NO separator between the two same-day rows.
        assertEquals(5, out.size)
        assertEquals(2, separators(out).size)
        assertEquals(listOf("u1", "u2", "u3"), rawIds(out))
        // Separator positions: immediately before u1 and immediately before u3.
        assertTrue(out[0] is TraceDisplayEntry.DaySeparator)
        assertTrue(out[3] is TraceDisplayEntry.DaySeparator)
    }

    // --- FZ-4: frozen day labels ---

    @Test
    fun todayAndYesterdayLabels_frozen() {
        val out = ChatDaySeparatorPolicy.withDaySeparators(
            listOf(
                userEntry("u1", todayMorning),
                userEntry("u2", yesterday)
            ),
            zone,
            today
        )
        val labels = separators(out).map { it.label }
        assertEquals(listOf("Today", "Yesterday"), labels)
    }

    @Test
    fun olderLabels_monthDay_andYearVariants_frozen() {
        val out = ChatDaySeparatorPolicy.withDaySeparators(
            listOf(
                userEntry("u1", sameYearOld),
                userEntry("u2", priorYearOld)
            ),
            zone,
            today
        )
        val labels = separators(out).map { it.label }
        // HOME-S2 boundary parity: same year -> "MMM d", prior year -> "MMM d, yyyy".
        assertEquals(listOf("Sep 12", "Sep 12, 2025"), labels)
    }

    // --- FZ-7: timestamp <= 0 is hidden and transparent ---

    @Test
    fun absentTimestamp_zero_hiddenAndTransparent() {
        // A zero-ts entry never mints a caption candidate...
        assertNull(ChatDaySeparatorPolicy.timeCaption(0L, zone))
        // ...nor a separator FOR itself (bare when first)...
        val singleZero = ChatDaySeparatorPolicy.withDaySeparators(
            listOf(userEntry("u0", 0L)),
            zone,
            today
        )
        assertEquals(1, singleZero.size)
        assertTrue(singleZero[0] is TraceDisplayEntry.Raw)
        // ...and is invisible to day-change detection: it renders under the
        // previous separator without disturbing the day sequence.
        val out = ChatDaySeparatorPolicy.withDaySeparators(
            listOf(
                userEntry("u1", yesterday),
                userEntry("u0", 0L),
                userEntry("u2", todayMorning)
            ),
            zone,
            today
        )
        assertEquals(5, out.size)
        assertEquals(listOf("Yesterday", "Today"), separators(out).map { it.label })
        assertEquals(listOf("u1", "u0", "u2"), rawIds(out))
    }

    // --- FZ-8: keys stay unique across recurring day runs ---

    @Test
    fun separatorKeys_uniqueAcrossRecurringDayRuns() {
        val dayA: Long = Instant.parse("2026-09-15T10:00:00Z").toEpochMilli()
        val dayB: Long = Instant.parse("2026-09-16T10:00:00Z").toEpochMilli()
        val out = ChatDaySeparatorPolicy.withDaySeparators(
            listOf(
                userEntry("a1", dayA),
                userEntry("b1", dayB),
                userEntry("a2", dayA)
            ),
            zone,
            today
        )
        val keys = separators(out).map { it.key }
        assertEquals(3, keys.size)
        assertEquals(3, keys.toSet().size)
        assertEquals("day-sep-${epochDay(dayA)}-r0", keys[0])
        assertEquals("day-sep-${epochDay(dayB)}-r0", keys[1])
        // Same day recurring a second time gets the next run index.
        assertEquals("day-sep-${epochDay(dayA)}-r1", keys[2])
    }

    // --- FZ-11: load-earlier prepend keeps existing keys unchanged ---

    @Test
    fun prependOfOlderRows_existingKeysUnchanged() {
        val before = ChatDaySeparatorPolicy.withDaySeparators(
            listOf(
                userEntry("u1", sameYearOld),
                userEntry("u2", todayMorning)
            ),
            zone,
            today
        )
        // Older rows (a new older day) are prepended at the head.
        val after = ChatDaySeparatorPolicy.withDaySeparators(
            listOf(
                userEntry("p1", priorYearOld),
                userEntry("p2", sameYearOld),
                userEntry("u1", sameYearOld),
                userEntry("u2", todayMorning)
            ),
            zone,
            today
        )
        // Every pre-existing key survives unchanged (raw + separators).
        val beforeKeys = before.map { it.key }
        val afterKeys = after.map { it.key }
        assertTrue(beforeKeys.all { it in afterKeys })
        // Pre-existing raw order preserved; new head separators prepended only.
        val beforeRawKeys = before.filterIsInstance<TraceDisplayEntry.Raw>().map { it.key }
        val afterRawKeys = after.filterIsInstance<TraceDisplayEntry.Raw>().map { it.key }
        assertTrue(afterRawKeys.containsAll(beforeRawKeys))
        // Head of the new list: separators for the prepended older days.
        val afterSeparators = separators(after).map { it.key }
        assertTrue(afterSeparators.first().startsWith("day-sep-${epochDay(priorYearOld)}"))
    }

    // --- CHAT-T7 S-2: scroll targets read the separator-derived list ---

    @Test
    fun latestDisplayIndex_targetsSeparatorDerivedList_notRawDisplay() {
        // Multi-day input: withDaySeparators grows the list by K>0 separators.
        val raw = listOf(
            userEntry("u1", sameYearOld),
            userEntry("u2", yesterday),
            userEntry("u3", todayMorning)
        )
        val withDays = ChatDaySeparatorPolicy.withDaySeparators(raw, zone, today)
        val separatorCount = separators(withDays).size
        assertTrue(separatorCount > 0)
        assertEquals(raw.lastIndex + separatorCount, withDays.lastIndex)

        // The exact invariant the three ChatScreen scroll call sites rely on:
        // the target index derives from the separator-inclusive display list.
        assertEquals(withDays.lastIndex, ChatDaySeparatorPolicy.latestDisplayIndex(withDays))
        // Empty-list guard keeps the coerceAtLeast(0) behavior.
        assertEquals(0, ChatDaySeparatorPolicy.latestDisplayIndex(emptyList()))
    }

    // --- FZ-1: frozen "h:mm a" caption format ---

    @Test
    fun timeCaption_format_frozen() {
        // 2026-09-19T04:45:00Z == 10:15 AM IST.
        val ts = Instant.parse("2026-09-19T04:45:00Z").toEpochMilli()
        assertEquals("10:15 AM", ChatDaySeparatorPolicy.timeCaption(ts, zone))
        assertNull(ChatDaySeparatorPolicy.timeCaption(0L, zone))
    }

    // --- FZ-6/FZ-14: trace headers are transparent to day logic ---

    @Test
    fun traceHeaders_transparentToDayLogic() {
        val out = ChatDaySeparatorPolicy.withDaySeparators(
            listOf(
                userEntry("u1", yesterday),
                header(),
                userEntry("u2", todayMorning)
            ),
            zone,
            today
        )
        // Headers neither suppress nor duplicate separators: one per day,
        // positioned around the header, not before/after it.
        assertEquals(5, out.size)
        assertEquals(listOf("Yesterday", "Today"), separators(out).map { it.label })
        assertEquals(listOf("u1", "u2"), rawIds(out))
        assertTrue(out[0] is TraceDisplayEntry.DaySeparator)
        assertTrue(out[1] is TraceDisplayEntry.Raw)
        assertTrue(out[2] is TraceDisplayEntry.Header)
        assertTrue(out[3] is TraceDisplayEntry.DaySeparator)
        assertTrue(out[4] is TraceDisplayEntry.Raw)
    }
}
