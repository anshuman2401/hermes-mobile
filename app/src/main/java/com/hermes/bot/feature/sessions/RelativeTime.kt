package com.hermes.bot.feature.sessions

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * HOME-S2 (frozen v1, specs/plans/HOME-001-S2-relative-timestamps-plan-v1.md):
 * relative activity label for a session row. Pure function; JVM-testable;
 * no Android framework date APIs (DateUtils.getRelativeTimeSpanString is
 * explicitly banned). Boundary table (elapsed d = Duration.between(startedAt, now)):
 *  - future (negative d) -> "just now" (clamped)
 *  - d < 2 min           -> "just now"
 *  - d < 60 min          -> "<m>m ago"
 *  - d < 24 h            -> "<h>h ago"
 *  - d < 48 h            -> "Yesterday"
 *  - d < 7 d             -> "<d>d ago"
 *  - d < 1 y             -> "MMM d" (ZoneId.systemDefault(), Locale.ENGLISH)
 *  - otherwise           -> "MMM d, yyyy" (Locale.ENGLISH)
 *  - startedAt == null   -> "" (no placeholder dash; consistent with S1 nulls-last)
 */
fun relativeActivityLabel(now: Instant, startedAt: Instant?): String {
    if (startedAt == null) return ""
    val elapsed = Duration.between(startedAt, now)
    if (elapsed.isNegative() || elapsed.seconds < 120) return "just now"
    if (elapsed.seconds < 3600) return "${elapsed.toMinutes()}m ago"
    if (elapsed.toHours() < 24) return "${elapsed.toHours()}h ago"
    if (elapsed.toHours() < 48) return "Yesterday"
    if (elapsed.toDays() < 7) return "${elapsed.toDays()}d ago"
    val formatter = if (elapsed.toDays() < 365) {
        DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    } else {
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    }
    return startedAt.atZone(ZoneId.systemDefault()).format(formatter)
}
