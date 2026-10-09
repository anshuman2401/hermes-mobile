package com.hermes.bot.feature.chat

/**
 * Pure decision policy for user-message collapse (owner directive 2026-09-27):
 * a user bubble longer than [COLLAPSE_THRESHOLD_CHARS] characters renders only
 * the first [COLLAPSE_THRESHOLD_CHARS] characters with a "Show more" control at
 * the bubble's bottom-right; tapping expands ONLY that message (state is kept
 * per message id by the Compose layer). Scrollability is the goal: collapsed by
 * default so one long prompt doesn't push the conversation out of view.
 *
 * Assumption (stated in the requirements note, PR body): the owner's 300 means
 * 300 CHARACTERS — the displayed prefix is exactly 300 chars when collapsing.
 */
object UserMessageCollapsePolicy {

    /** Owner-specified collapse threshold. */
    const val COLLAPSE_THRESHOLD_CHARS: Int = 300

    /** Exact label of the expand control (frozen copy). */
    const val SHOW_MORE_LABEL: String = "Show more"

    /**
     * True when the message should render collapsed on first display.
     * Exactly-long (300-char) messages render normally — collapse is strictly
     * for >300.
     */
    fun shouldCollapse(text: String): Boolean = text.length > COLLAPSE_THRESHOLD_CHARS

    /**
     * The displayed text while collapsed: exactly the first 300 characters.
     * Returns the full text unchanged when the message is short or already
     * expanded. Never returns an over-threshold string while collapsed.
     */
    fun collapsedText(text: String, expanded: Boolean): String =
        if (expanded || text.length <= COLLAPSE_THRESHOLD_CHARS) text
        else text.take(COLLAPSE_THRESHOLD_CHARS)
}