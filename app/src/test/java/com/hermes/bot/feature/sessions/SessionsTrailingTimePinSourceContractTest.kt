package com.hermes.bot.feature.sessions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Approved title/activity layout replaces the historical stacked 80dp rail. */
class SessionsTrailingTimePinSourceContractTest {
    private val source by lazy {
        val local = File("src/main/java/com/hermes/bot/feature/sessions/SessionsScreen.kt")
        (if (local.exists()) local else File("app/src/main/java/com/hermes/bot/feature/sessions/SessionsScreen.kt")).readText()
    }

    @Test fun compactRowsKeepFlexibleTitleAndGrowingTimestamp_withoutPreviewRail() {
        assertTrue(source.contains("SESSIONS_ROW_MIN_HEIGHT_DP = 60"))
        assertTrue(source.contains("Modifier.weight(1f)"))
        assertTrue(source.contains("if (label.isNotEmpty() && !largeText)"))
        assertTrue(source.contains("if (label.isNotEmpty() && largeText)"))
        assertTrue(source.contains("textAlign = TextAlign.End, softWrap = true"))
        assertTrue(source.contains("maxLines = sessionsTitleMaxLines(LocalDensity.current.fontScale)"))
        assertFalse(source.contains("SESSIONS_CARD_TRAILING_RAIL_WIDTH_DP"))
        assertFalse(source.contains("session.preview.orEmpty()"))
        assertFalse(source.contains("titleInitial"))
    }

    @Test fun groupedRowsRemainIndividuallyLazy_andMetadataCanWrap() {
        assertTrue(source.contains("items(group.sessions, key = { it.id })"))
        assertTrue(source.contains("AtelierGroupRow(first, last,"))
        assertTrue(source.contains("if (!last) AtelierDivider()"))
        assertTrue(source.contains("FlowRow(horizontalArrangement"))
        assertTrue(source.contains("contentDescription = messageCountLabel"))
        assertTrue(source.contains("SessionPinAffordance("))
        assertFalse(source.contains(".requiredHeight("))
    }
}
