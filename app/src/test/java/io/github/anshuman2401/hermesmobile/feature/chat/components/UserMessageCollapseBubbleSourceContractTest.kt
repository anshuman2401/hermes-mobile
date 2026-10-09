package io.github.anshuman2401.hermesmobile.feature.chat.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for the user-bubble collapse wiring. This JVM suite has no
 * Compose UI-test infrastructure. Asserts
 * UserMessageBubble renders the collapsed text via the policy and shows the
 * "Show more" expand control ONLY for collapsible (collapsed) messages, with
 * per-message persisted state. Fails at base (bubble rendered message.text
 * unconditionally).
 */
class UserMessageCollapseBubbleSourceContractTest {

    private fun bubbleSrc(): String {
        val dir = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .map { it.resolve("io/github/anshuman2401/hermesmobile/feature/chat/components") }
            .first { it.resolve("UserMessageBubble.kt").exists() }
        return dir.resolve("UserMessageBubble.kt").readText()
    }

    @Test
    fun bubble_rendersTextThroughCollapsePolicy_notRawMessageText() {
        val src = bubbleSrc()
        // The collapsed/expanded decision comes from the policy...
        assertTrue(
            "UserMessageBubble must gate display through UserMessageCollapsePolicy",
            src.contains("UserMessageCollapsePolicy.shouldCollapse(invocationText)") &&
                src.contains("UserMessageCollapsePolicy.collapsedText(") &&
                src.contains("SkillInvocationDisplay.text(message.text)")
        )
        // ...and the Text render must use the policy-derived display text
        // (the Text( call takes `text = displayText`).
        assertTrue(
            "UserMessageBubble Text must render the policy-derived display text, not raw message.text",
            src.contains("Text(\n                    text = displayText,")
        )
    }

    @Test
    fun bubble_showsExpandControlOnlyWhenCollapsed_bottomRight() {
        val src = bubbleSrc()
        // The control is gated on the collapsed state...
        assertTrue(
            "Show-more control must be gated on the collapsed decision",
            src.contains("if (collapsed) {")
        )
        // ...renders the frozen label...
        assertTrue(
            "Expand control must render UserMessageCollapsePolicy.SHOW_MORE_LABEL",
            src.contains("UserMessageCollapsePolicy.SHOW_MORE_LABEL")
        )
        // ...and is right-aligned within the bubble (bottom-right placement
        // under the truncated text, before the status row).
        val controlWindow = src.substringAfter("if (collapsed) {")
            .substringBefore("// Status indication")
        assertTrue(
            "Expand control must be right-aligned (Arrangement.End) inside the bubble",
            controlWindow.contains("Arrangement.End")
        )
    }

    @Test
    fun bubble_expansionStateIsPerMessage_andSurvivesScrollAway() {
        val src = bubbleSrc()
        // rememberSaveable keyed by the message id — stable list keys make
        // this survive the item leaving composition while scrolled.
        assertTrue(
            "Expanded state must be rememberSaveable keyed by message.id",
            src.contains("rememberSaveable(message.id)")
        )
    }
}
