package com.hermes.bot.feature.bots

import com.hermes.bot.data.gateway.SessionSummary
import java.time.Instant

/** Only authoritative list fields; never infer user identity or a reply from the opening text. */
internal data class BotThreadPresentation(
    val title: String,
    val opening: String?,
    val startedAt: Instant?,
    val messageCount: Int?
) {
    override fun toString() = "BotThreadPresentation([REDACTED])"
}

internal fun SessionSummary.threadPresentation() = BotThreadPresentation(
    title = title?.trim()?.takeIf { it.isNotBlank() } ?: "Untitled conversation",
    opening = preview?.trim()?.takeIf { it.isNotBlank() },
    startedAt = startedAt?.takeIf { it.isFinite() && it >= 0 && it < 253402300800.0 }
        ?.let { Instant.ofEpochSecond(it.toLong()) },
    messageCount = messageCount.takeIf { it > 0 }
)
