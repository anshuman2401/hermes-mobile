package com.hermes.bot.feature.bots

import androidx.compose.ui.text.TextStyle
import com.hermes.bot.data.gateway.SessionSummary

/** Bot board visibility only; the authoritative session list stays unchanged. */
internal fun botBoardConversations(sessions: List<SessionSummary>): List<SessionSummary> =
    sessions.filterNot { it.source?.trim().equals("cron", ignoreCase = true) }

internal fun TextStyle.boardReduced(): TextStyle =
    copy(fontSize = fontSize * 0.75f, lineHeight = lineHeight * 0.75f)
