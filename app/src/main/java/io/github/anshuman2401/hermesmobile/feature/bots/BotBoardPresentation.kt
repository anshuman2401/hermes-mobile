package io.github.anshuman2401.hermesmobile.feature.bots

import androidx.compose.ui.text.TextStyle
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary

/** Bot board visibility only; the authoritative session list stays unchanged. */
internal fun botBoardConversations(sessions: List<SessionSummary>): List<SessionSummary> =
    sessions.filterNot { it.source?.trim().equals("cron", ignoreCase = true) }

internal fun TextStyle.boardReduced(): TextStyle =
    copy(fontSize = fontSize * 0.75f, lineHeight = lineHeight * 0.75f)
