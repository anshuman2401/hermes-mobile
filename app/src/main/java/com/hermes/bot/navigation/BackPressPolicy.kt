package com.hermes.bot.navigation

/**
 * What the shell should do on one system-back press (CHG-006-S1).
 */
sealed interface BackPressAction {
    data object NavigateToChannel : BackPressAction
    data object NavigateToBots : BackPressAction
    /**
     * Leave the chat route back to the Sessions list (compact layout).
     */
    data object NavigateToSessions : BackPressAction

    /**
     * Return to the screen Settings was opened from
     * (the shell's existing `previousScreen` mechanism).
     */
    data object NavigateToPrevious : BackPressAction

    /**
     * Return from Debug logs to Settings (its only in-app parent).
     */
    data object NavigateToSettings : BackPressAction

    /**
     * The current screen is a root screen; the back press is consumed and
     * root-exit handling belongs to CHG-006-S2 (no-op in this slice).
     */
    data object RootPrompt : BackPressAction
}

/**
 * What the shell should do with a back press that lands on a root screen
 * (CHG-006-S2): warn once, exit on the second press inside the window.
 *
 * TOP LEVEL (not nested) so tests and call sites can reference it unqualified
 * (specs/learnings/2026-09/2026-09-18-chg006-s1-nested-policy-type-in-test.md).
 */
enum class RootExitDecision {
    /**
     * First press, or the previous warning window has expired: the caller
     * shows the "Press back again to exit" toast and the policy records the
     * prompt time.
     */
    SHOW_EXIT_PROMPT,

    /**
     * Second press while the warning window is still open: the caller
     * finishes the activity.
     */
    EXIT
}

/**
 * Stateful root-exit state machine for the double-back-to-exit pattern
 * (CHG-006-S2, plan TASK-005).
 *
 * The S1 [BackPressPolicy] stays additive and unchanged; this class owns the
 * root-screen timing state only. The clock is injected so JVM tests drive a
 * fake monotonic source; production wires `SystemClock.elapsedRealtime()`.
 *
 * Contract (plan TASK-005):
 * - every call yields exactly one decision (SHOW_EXIT_PROMPT or EXIT);
 * - the prompt timestamp is recorded ONLY on SHOW_EXIT_PROMPT;
 * - EXIT leaves the recorded prompt in place — the next press after an exit
 *   decision starts a fresh cycle, so the policy is reusable for the whole
 *   activity lifetime.
 */
internal class RootExitPolicy(
    /** Monotonic millis source; production injects SystemClock.elapsedRealtime. */
    private val clock: () -> Long,
    /** Warning window in milliseconds (plan ASSUMPTION-003: 2000 ms). */
    private val windowMs: Long = 2000L
) {

    /** Time of the last SHOW_EXIT_PROMPT decision; null before the first press. */
    private var lastPromptAt: Long? = null

    /**
     * Decides one system-back press on a root screen.
     *
     * SHOW_EXIT_PROMPT when no prompt is outstanding or when
     * `now - lastPromptAt > windowMs` (window expired); EXIT when
     * `now - lastPromptAt <= windowMs` (inclusive boundary, plan TASK-008).
     */
    fun onRootBackPress(): RootExitDecision {
        val now = clock()
        val promptAt = lastPromptAt
        return if (promptAt != null && now - promptAt <= windowMs) {
            RootExitDecision.EXIT
        } else {
            lastPromptAt = now
            RootExitDecision.SHOW_EXIT_PROMPT
        }
    }
}

/**
 * Pure, stateless decision table for the shell-level system-back handler
 * (CHG-006-S1, plan specs/plans/CHG-006-back-navigation-plan-v1.md TASK-001).
 *
 * The Compose layer (HermesNavShell's BackHandler) executes the returned
 * action by mutating shell screen state exactly as the existing in-app back
 * arrows do; the policy itself performs no state changes and holds no state.
 *
 * S1 contract:
 * - CHAT (compact)        -> NavigateToSessions
 * - SETTINGS              -> NavigateToPrevious
 * - DEBUG_LOGS            -> NavigateToSettings
 * - SESSIONS / CONNECT    -> RootPrompt (root screens; no-op in S1 — consumed
 *   by the double-back-to-exit slice, CHG-006-S2)
 *
 * The expanded dual-pane layout renders chat side-by-side inside the SESSIONS
 * screen (the enum value does not change), so SESSIONS stays root regardless
 * of [isExpanded] (plan ASSUMPTION-002).
 */
internal object BackPressPolicy {

    /**
     * Decides the action for one system-back press on a shell screen.
     *
     * @param screen the screen currently displayed by the shell
     * @param isExpanded whether the 600dp+ dual-pane layout is active; the
     *   SESSIONS screen keeps its enum value there, so this parameter does not
     *   change any mapping today but is part of the seam so expanded-specific
     *   behavior has a single decision point (ChatShellCleanupPolicy precedent)
     */
    fun decide(screen: ShellScreen, isExpanded: Boolean, botChat: Boolean = false): BackPressAction = when (screen) {
        ShellScreen.CHAT -> if (botChat) BackPressAction.NavigateToChannel else BackPressAction.NavigateToSessions
        ShellScreen.BOT_CHANNEL -> BackPressAction.NavigateToBots
        ShellScreen.BOT_SCREEN -> BackPressAction.NavigateToPrevious
        ShellScreen.SETTINGS -> BackPressAction.NavigateToPrevious
        ShellScreen.DEBUG_LOGS -> BackPressAction.NavigateToSettings
        // Root screens in both layouts (plan REQ-002/ASSUMPTION-002).
        ShellScreen.SESSIONS, ShellScreen.CONNECT -> BackPressAction.RootPrompt
    }
}
