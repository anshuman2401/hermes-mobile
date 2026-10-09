package io.github.anshuman2401.hermesmobile.feature.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CHAT-009 (frozen plan v1, FZ-13): pure, stateless decision policy for
 * per-message time captions (FZ-1) and day-separator insertion (FZ-4/6/7/8)
 * on the chat timeline display list. The Composable is an executor of these
 * decisions (ExecutionTracePolicy / BackPressPolicy precedent); separators
 * ride the display list, never the domain [TimelineEntry] contract.
 *
 * All functions are pure and JVM-testable. `java.time` only — no
 * `Duration` arithmetic (API-31+ method surface), no
 * `android.text.format.DateUtils` / `SimpleDateFormat` / `java.util.Date`
 * (HOME-S2 rule). `today` is an injected parameter so derivation is
 * JVM-deterministic; ChatScreen passes `LocalDate.now(zone)` at derivation
 * time (FZ-5: the zone is captured once per displayEntries derivation).
 */
object ChatDaySeparatorPolicy {

    /** Frozen FZ-1 per-message caption format (owner device is IST). */
    private const val TIME_CAPTION_PATTERN = "h:mm a"

    /** FZ-8 key prefix — collides with NO existing LazyColumn key. */
    private const val DAY_SEPARATOR_KEY_PREFIX = "day-sep-"

    /**
     * FZ-6: inserts a [TraceDisplayEntry.DaySeparator] immediately before the
     * first `Raw` entry whose entry timestamp falls in a different calendar
     * day than the previous `Raw` entry's timestamp (the first timestamped
     * entry always gets one). `Header` display entries are transparent:
     * they never trigger or receive separators. An entry with
     * `timestamp <= 0` (FZ-7) is invisible to day-change detection — no
     * separator is minted FOR it; it renders under the most recent
     * separator (or bare when it is first).
     *
     * Separator keys (FZ-8): `day-sep-<epochDay>-r<runIndex>` where `runIndex`
     * counts separate runs of the same calendar day (0-based), keeping keys
     * unique under non-monotonic timestamps and stable across load-earlier
     * prepends whose day set is unchanged (FZ-11).
     */
    fun withDaySeparators(
        display: List<TraceDisplayEntry>,
        zone: ZoneId,
        today: LocalDate
    ): List<TraceDisplayEntry> {
        if (display.isEmpty()) return display
        val out = ArrayList<TraceDisplayEntry>(display.size * 2)
        var lastRawEpochDay: Long? = null
        // run counts: epochDay -> next run index for that day.
        val runCounts = HashMap<Long, Int>()
        for (item in display) {
            when (item) {
                is TraceDisplayEntry.Header -> {
                    // Transparent: never triggers or receives a separator.
                    out.add(item)
                }

                is TraceDisplayEntry.BlockCard -> {
                    // Transparent: never triggers or receives a separator.
                    out.add(item)
                }

                is TraceDisplayEntry.Raw -> {
                    val millis = item.entry.timestamp
                    if (millis <= 0L) {
                        // FZ-7: absent timestamp — no caption candidate, no
                        // separator minted FOR it, transparent to day logic.
                        out.add(item)
                        continue
                    }
                    val epochDay = Instant.ofEpochMilli(millis)
                        .atZone(zone)
                        .toLocalDate()
                        .toEpochDay()
                    val isNewDay = lastRawEpochDay == null || epochDay != lastRawEpochDay
                    if (isNewDay) {
                        val run = runCounts.getOrDefault(epochDay, 0)
                        runCounts[epochDay] = run + 1
                        out.add(
                            TraceDisplayEntry.DaySeparator(
                                label = dayLabel(epochDay, zone, today),
                                key = "$DAY_SEPARATOR_KEY_PREFIX${epochDay}-r$run"
                            )
                        )
                    }
                    out.add(item)
                    lastRawEpochDay = epochDay
                }

                // Defensive: a pre-minted separator in the input is preserved
                // as-is (keeps the `when` exhaustive over the sealed type).
                is TraceDisplayEntry.DaySeparator -> out.add(item)

                // PR-4: the phone-captured trace group is transparent here
                // too — no timestamp, never a day-separator candidate, and it
                // keeps its position after the timeline.
                is TraceDisplayEntry.CachedTrace -> out.add(item)
            }
        }
        return out
    }

    /**
     * CHAT-T7 (S-1): the latest scrollable index of the separator-inclusive
     * display list — the exact target the follow/jump/affordance scroll
     * sites must use, since [withDaySeparators] grows the list by K
     * separator rows and raw `display.lastIndex` undershoots by K.
     */
    fun latestDisplayIndex(display: List<TraceDisplayEntry>): Int =
        display.lastIndex.coerceAtLeast(0)

    /**
     * FZ-1/FZ-7: "h:mm a" caption in [zone] (Locale.ENGLISH, HOME-S2
     * precedent), or `null` when `timestampMillis <= 0` — never a fabricated
     * time.
     */
    fun timeCaption(timestampMillis: Long, zone: ZoneId): String? {
        if (timestampMillis <= 0L) return null
        val formatter = DateTimeFormatter.ofPattern(TIME_CAPTION_PATTERN, Locale.ENGLISH)
        return Instant.ofEpochMilli(timestampMillis).atZone(zone).format(formatter)
    }

    /**
     * FZ-4: frozen day labels — "Today" / "Yesterday" for the current device
     * day and the one before; otherwise "MMM d" (same calendar year) /
     * "MMM d, yyyy" (older), Locale.ENGLISH (HOME-S2 boundary parity).
     */
    private fun dayLabel(epochDay: Long, zone: ZoneId, today: LocalDate): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> {
                val formatter = if (date.year == today.year) {
                    DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
                } else {
                    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
                }
                date.format(formatter)
            }
        }
    }
}
