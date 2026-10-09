package com.hermes.bot.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source guard for the single session-row rendering and unchanged resume path. */
class SessionsResumeCardSourceContractTest {
    private val sourceFile = File("src/main/java/com/hermes/bot/feature/sessions/SessionsScreen.kt").let {
        if (it.exists()) it else File("app/src/main/java/com/hermes/bot/feature/sessions/SessionsScreen.kt")
    }
    private val source: String by lazy {
        assertTrue("SessionsScreen.kt must exist", sourceFile.exists())
        sourceFile.readText()
    }

    @Test
    fun sessionsScreen_hasOnlyFilteredKeyedRowSite_noPinnedContinueCard() {
        assertEquals(1, Regex("\\bitems\\(group\\.sessions, key = \\{ it.id \\}\\)").findAll(source).count())
        assertFalse(source.contains("SessionsResumeCardPolicy.resumeCardCandidate("))
        assertFalse(source.contains("val resumeCandidate ="))
        assertFalse(source.contains("onOpenChat(resumeCandidate.id)"))
        assertFalse(source.contains("text = \"Continue\""))
        assertFalse(source.contains("\"Continue: "))
        assertEquals("only the row has an explicit open tap", 1,
            Regex("onOpenChat\\(session\\.id\\)").findAll(source).count())
    }

    @Test
    fun sessionsScreen_groupedRowTapKeepsViewModelAndPreviewFallback() {
        val rowSource = source.substringAfter("items(group.sessions, key = { it.id }) { session ->")
            .substringBefore(".semantics {")
        assertTrue(rowSource.contains(".clickable(enabled = !isLoading && !isCreatingSession && sessionOpenAllowed())"))
        assertTrue(rowSource.contains("if (sessionOpenAllowed()) onOpenChat(session.id)"))
        val clickSource = rowSource.substringAfter(".clickable(")
        assertFalse(clickSource.contains("viewModel != null"))
        assertFalse(source.contains("selectAndResumeSession("))
        assertFalse(source.contains("listSessions("))
        assertFalse(source.contains("resumeSession("))
        assertFalse(source.contains("createDraftSession("))
    }

    @Test
    fun sessionsScreen_rowKeepsTitleTimeMetadataPreviewAndRunningState() {
        assertTrue(source.contains("SessionsRowPresentationPolicy.displayedTitle("))
        assertFalse(source.contains("titleInitial"))
        assertFalse(source.contains("SESSIONS_CARD_AVATAR_SIZE_DP"))
        assertFalse(source.contains("SESSIONS_CARD_AVATAR_CONTENT_SPACING_DP"))
        assertTrue(source.contains(".padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)"))
        assertTrue(source.contains("effectiveSessionTimestamp(session, zone)"))
        assertTrue(source.contains("SessionsRowMetaPolicy.messageLabel(session.messageCount)"))
        assertTrue(source.contains("OutlineChatBubbleGraphic("))
        assertFalse(source.contains("SessionsRowMetaPolicy.modelLabel("))
        assertFalse(source.contains("val preview = session.preview.orEmpty()"))
        assertTrue(source.contains("maxLines = sessionsTitleMaxLines(LocalDensity.current.fontScale)"))
        assertTrue(source.contains("TextOverflow.Ellipsis"))
        assertFalse(source.contains("contentDescription = \"Title initial ${'$'}titleInitial\""))
        assertTrue(source.contains("SessionsRunningRowPolicy.showRunning("))
        assertTrue(source.contains("rowId = session.id"))
        assertTrue(source.contains("streamingId = uiState?.activeTurnStreaming?.storedSessionId"))
    }

    @Test
    fun sessionsScreen_pinIsIndependentAction_withoutOpeningChat() {
        val pinSource = source.substringAfter("private fun SessionPinAffordance(")
            .substringBefore("private fun OutlineChatBubbleGraphic")
        assertTrue(pinSource.contains("IconButton(onClick = onClick, enabled = enabled"))
        assertTrue(pinSource.contains("size(48.dp)"))
        assertTrue(pinSource.contains("SessionsRowPresentationPolicy.pinAccessibilityLabel(pinned)"))
        assertTrue(pinSource.contains("if (!enabled) awaitPointerEventScope"))
        assertTrue(pinSource.contains("PointerEventPass.Initial"))
        assertFalse(pinSource.contains("onOpenChat"))
        assertTrue(source.contains("viewModel?.toggleSessionPin(session.id)"))
    }
}
