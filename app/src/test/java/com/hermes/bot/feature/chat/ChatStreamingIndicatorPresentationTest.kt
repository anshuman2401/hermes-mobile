package com.hermes.bot.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CHAT-020 (D-01/D-02) presentation source contract. Compose is pinned here with
 * PendingInterruptWiringTest-style source assertions because this repo has no
 * compose UI-test infrastructure; visual/device verification is separate.
 *
 * - D-01 (AC-01..AC-04): the assistant bubble's bottom STREAMING status row is
 *   gone; the body's "Thinking..." row (empty text + STREAMING) remains as the
 *   single streaming indicator; terminal chips are untouched.
 * - D-02 (AC-05/AC-06): the new-activity affordance is icon-only — no rendered
 *   text label and no label const; semantics, 48dp tap target, and the
 *   scroll-to-latest action are unchanged, and TimelineFollowPolicy stays
 *   wired to the affordance.
 */
class ChatStreamingIndicatorPresentationTest {

    private fun chatFeatureDir(): File =
        sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .map { it.resolve("com/hermes/bot/feature/chat") }
            .first { it.resolve("ChatScreen.kt").exists() }

    private fun bubbleSrc(): String =
        chatFeatureDir().resolve("components/AssistantMessageBubble.kt").readText()

    private fun chatScreenSrc(): String =
        chatFeatureDir().resolve("ChatScreen.kt").readText()

    private fun policySrc(): String =
        chatFeatureDir().resolve("TimelineFollowPolicy.kt").readText()

    // ------------------------------------------------------------------ D-01

    @Test
    fun streamingBodyIndicator_thinkingRowRemains_singleIndicator() {
        val src = bubbleSrc()
        // AC-01: the body's "Thinking..." row (empty text + STREAMING) stays —
        // pinned via the real Text call, not a comment.
        assertTrue(
            "CHAT-020 D-01: AssistantMessageBody must keep the streaming \"Thinking...\" row",
            src.contains("else if (message.state == AssistantMessageState.STREAMING)") &&
                src.contains("text = \"Thinking...\"")
        )
        // AC-03: no "Streaming..." label anywhere in the assistant bubble.
        assertFalse(
            "CHAT-020 D-01: the bubble must never render a \"Streaming...\" label",
            src.contains("Streaming...")
        )
    }

    @Test
    fun streamingStatusBranch_removed_whenStaysExhaustive() {
        val src = bubbleSrc()
        // The removed branch (12dp spinner + "Streaming..." label row) is gone...
        assertFalse(
            "CHAT-020 D-01: STREAMING must not render a bottom status row",
            src.contains("AssistantMessageState.STREAMING -> {\n")
        )
        // ...while the when stays exhaustive via an explicit no-op branch.
        assertTrue(
            "CHAT-020 D-01: the state when must keep an explicit no-op STREAMING branch",
            src.contains("AssistantMessageState.STREAMING -> Unit")
        )
    }

    @Test
    fun terminalChips_unchanged_afterStreamingRowRemoval() {
        val src = bubbleSrc()
        // AC-04: terminal chips keep their existing rows and copy.
        assertTrue(
            "Interrupted chip must survive",
            src.contains("AssistantMessageState.INTERRUPTED -> {") && src.contains("text = \"Interrupted\"")
        )
        assertTrue(
            "Turn-failed chip must survive",
            src.contains("AssistantMessageState.FAILED -> {") && src.contains("text = \"Turn failed\"")
        )
        assertTrue(
            "Unknown-status chip must survive",
            src.contains("AssistantMessageState.UNKNOWN -> {") && src.contains("text = \"Unknown status\"")
        )
        assertTrue(
            "Complete branch must survive",
            src.contains("AssistantMessageState.COMPLETE -> {")
        )
    }

    // ------------------------------------------------------------------ D-02

    @Test
    fun newActivityAffordance_iconOnly_semanticsAndTapTargetKept() {
        val src = chatScreenSrc()
        // No rendered text label anywhere, and the label const is fully removed.
        assertFalse(
            "CHAT-020 D-02: NEW_ACTIVITY_LABEL must be removed (no const, no rendered text)",
            src.contains("NEW_ACTIVITY_LABEL")
        )
        // The affordance render block keeps the icon, the 48dp target, the
        // TalkBack description (AC-06), and the scroll-to-latest action.
        val blockStart = src.indexOf("if (showNewActivityAffordance)")
        assertTrue("the affordance render block must exist", blockStart >= 0)
        val block = src.substring(blockStart)
        assertTrue(
            "CHAT-020 D-02: affordance keeps the KeyboardArrowDown icon",
            block.contains("Icons.Default.KeyboardArrowDown")
        )
        assertTrue(
            "CHAT-020 D-02: affordance keeps the >=48dp tap target (AC-05)",
            block.contains("defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)")
        )
        assertTrue(
            "CHAT-020 D-02: TalkBack semantics stay on the affordance (AC-06)",
            block.contains("contentDescription = NEW_ACTIVITY_SEMANTICS")
        )
        assertTrue(
            "CHAT-020 D-02: affordance keeps the scroll-to-latest action (AC-05)",
            block.contains("followingLatest = true") && block.contains("explicitJump = true") &&
                src.contains("if (explicitJump)") &&
                src.contains("animateScrollToItem(TimelineFollowPolicy.bottomTargetIndex(")
        )
    }

    @Test
    fun timelineFollowPolicy_stillWiredToAffordance() {
        assertTrue(
            "CHAT-020 D-02: ChatScreen must keep deriving the affordance from TimelineFollowPolicy",
            chatScreenSrc().contains("TimelineFollowPolicy.shouldShowNewActivityAffordance(")
        )
        assertTrue(
            "CHAT-020 D-02: TimelineFollowPolicy must keep the affordance contract",
            policySrc().contains("fun shouldShowNewActivityAffordance(")
        )
    }
}
