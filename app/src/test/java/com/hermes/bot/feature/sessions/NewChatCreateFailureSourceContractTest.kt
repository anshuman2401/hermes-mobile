package com.hermes.bot.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CHAT-015 B2 source-contract guard (frozen plan v1, test plan 10–13).
 *
 * Supplementary to the behavioral SessionsNewChatCreateStateTest — freezes the
 * entry-point rendering contracts:
 * - SessionsScreen renders the create-failure notice at the New-chat FAB with a
 *   dismiss handler.
 * - ChatScreen declares the create-failure/degraded notice params and renders
 *   the shared notices after session creation from Sessions.
 * - Create progress never feeds the pull-to-refresh indicator.
 * - The global connection-status banner suppression is preserved
 *   (owner decision 2026-09-24): SessionsScreen keeps filtering
 *   SessionError.NetworkFailure from SessionErrorBanner and no new global
 *   banner is introduced.
 */
class NewChatCreateFailureSourceContractTest {

    private val sessionsFile = File("src/main/java/com/hermes/bot/feature/sessions/SessionsScreen.kt").let {
        if (it.exists()) it else File("app/src/main/java/com/hermes/bot/feature/sessions/SessionsScreen.kt")
    }

    private val chatFile = File("src/main/java/com/hermes/bot/feature/chat/ChatScreen.kt").let {
        if (it.exists()) it else File("app/src/main/java/com/hermes/bot/feature/chat/ChatScreen.kt")
    }

    private val sessionsSource: String by lazy {
        assertTrue("SessionsScreen.kt must exist", sessionsFile.exists())
        sessionsFile.readText()
    }

    private val chatSource: String by lazy {
        assertTrue("ChatScreen.kt must exist", chatFile.exists())
        chatFile.readText()
    }

    // 10.
    @Test
    fun sessionsScreen_rendersCreateFailureAtFixedNewChatAction_withDismiss() {
        assertTrue(
            "SessionsScreen.kt must read the dedicated create-failure state",
            sessionsSource.contains("newChatFailure")
        )
        assertTrue(
            "SessionsScreen.kt must wire the dismiss handler",
            sessionsSource.contains("dismissNewChatFailure()")
        )
        // The `internal fun NewChatInlineNotice(` DEFINITION at the bottom of
        // SessionsScreen.kt must not count as a rendered call site — the same
        // definition-site exclusion test 13 applies to `fun SessionErrorBanner(`.
        val inlineNoticeCallSites =
            Regex.escape("NewChatInlineNotice(").toRegex().findAll(sessionsSource).count() -
                Regex.escape("fun NewChatInlineNotice(").toRegex().findAll(sessionsSource).count()
        assertEquals(
            "SessionsScreen.kt must render exactly one NewChatInlineNotice call site",
            1,
            inlineNoticeCallSites
        )
        // The notice remains beside the fixed action, outside the scrolling list.
        val fixedActionIdx = sessionsSource.indexOf("FilledIconButton(")
        val firstInlineNoticeCallIdx = Regex.escape("NewChatInlineNotice(").toRegex()
            .findAll(sessionsSource)
            .map { it.range.first }
            .first { idx -> idx < 4 || !sessionsSource.startsWith("fun ", idx - 4) }
        assertTrue("fixed New chat action must be rendered", fixedActionIdx >= 0)
        assertTrue("creation notice must precede the fixed action",
            firstInlineNoticeCallIdx in 0 until fixedActionIdx)
        assertTrue("creation notice must be outside the list",
            firstInlineNoticeCallIdx > sessionsSource.lastIndexOf("items(group.sessions"))
    }

    // 11.
    @Test
    fun chatScreen_preservesSharedCreationNotices_withDismiss() {
        assertTrue(
            "ChatScreen.kt must declare the create-failure notice param",
            chatSource.contains("newChatFailure: NewChatCreateFailure? = null")
        )
        assertTrue(
            "ChatScreen.kt must declare the degraded-default notice param",
            chatSource.contains("newChatDegradedNotice: NewChatCreateNotice? = null")
        )
        assertTrue(
            "ChatScreen.kt must declare the create-failure dismiss handler",
            chatSource.contains("onDismissNewChatFailure: () -> Unit = {}")
        )
        assertTrue(
            "ChatScreen.kt must declare the degraded-notice dismiss handler",
            chatSource.contains("onDismissNewChatDegradedNotice: () -> Unit = {}")
        )
        assertTrue(
            "ChatScreen.kt must render the shared inline notice",
            chatSource.contains("NewChatInlineNotice(")
        )

    }

    // 12.
    @Test
    fun createProgress_neverFedToPullToRefresh() {
        assertTrue(
            "PullToRefreshBox isRefreshing must stay bound to the refresh-only isLoading",
            sessionsSource.contains("isRefreshing = isLoading")
        )
        assertFalse(
            "create progress must never drive the pull-to-refresh indicator",
            sessionsSource.contains("isRefreshing = isCreatingSession")
        )
        assertTrue(
            "create progress renders at the entry point via isCreatingSession",
            sessionsSource.contains("isCreatingSession")
        )
    }

    // 13.
    @Test
    fun globalNetworkBannerSuppression_preserved() {
        assertTrue(
            "SessionsScreen.kt must keep filtering SessionError.NetworkFailure from the banner",
            sessionsSource.contains("currentError !is SessionError.NetworkFailure")
        )
        val bannerCallSites = Regex.escape("SessionErrorBanner(").toRegex().findAll(sessionsSource).count() -
            Regex.escape("fun SessionErrorBanner(").toRegex().findAll(sessionsSource).count()
        assertEquals(
            "no new global banner may be introduced (owner decision 2026-09-24)",
            1,
            bannerCallSites
        )
    }
}
