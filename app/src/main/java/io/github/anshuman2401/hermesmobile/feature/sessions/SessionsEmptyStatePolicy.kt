package io.github.anshuman2401.hermesmobile.feature.sessions

/**
 * HOME-S10 pure policy for the Sessions designed empty state (frozen plan
 * `specs/plans/HOME-001-S10-designed-empty-state-plan-v1.md`, FZ-1).
 *
 * Renders the ux-navigation.md §2 empty-state sentence —
 * "No saved conversations yet. Start a chat." — as body text + CTA label.
 * The wording deliberately says "saved conversations" and makes no claim that
 * a blank remote draft is saved before its first prompt. The CTA label
 * avoids "New chat" so the S5 single-affordance guard
 * (`SessionsNewChatProminenceSourceContractTest`) stays green.
 *
 * Pure and JVM-deterministic: no time, network, or randomness inputs.
 * Same shape as `SessionsOfflineSectionPolicy`.
 */
object SessionsEmptyStatePolicy {
    const val EMPTY_TITLE = "No saved conversations yet."
    const val EMPTY_CTA = "Start a chat"

    fun isEmptyStateVisible(sessionCount: Int, isLoading: Boolean): Boolean =
        sessionCount == 0 && !isLoading
}
