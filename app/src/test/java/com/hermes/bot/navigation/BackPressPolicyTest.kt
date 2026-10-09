package com.hermes.bot.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CHG-006-S1 — decision-table tests for [BackPressPolicy], the pure policy
 * executed by the shell-level BackHandler in HermesNavShell.
 *
 * Contract (plan CHG-006-back-navigation-plan-v1, TASK-001/003):
 * - CHAT (compact)         -> NavigateToSessions (same as the in-app back arrow)
 * - SETTINGS               -> NavigateToPrevious (same as the in-app back arrow)
 * - DEBUG_LOGS             -> NavigateToSettings (same as the in-app back arrow)
 * - SESSIONS (any layout)  -> RootPrompt (root screen; no-op in S1)
 * - CONNECT                -> RootPrompt (root screen; no-op in S1)
 * The expanded dual-pane layout keeps the SESSIONS enum value, so
 * SESSIONS + isExpanded=true must still be root (ASSUMPTION-002).
 */
class BackPressPolicyTest {

    private val policy = BackPressPolicy

    @Test
    fun `chat compact maps to navigate to sessions`() {
        assertEquals(
            BackPressAction.NavigateToSessions,
            policy.decide(ShellScreen.CHAT, isExpanded = false)
        )
    }

    @Test
    fun `settings maps to navigate to previous`() {
        assertEquals(
            BackPressAction.NavigateToPrevious,
            policy.decide(ShellScreen.SETTINGS, isExpanded = false)
        )
    }

    @Test
    fun `debug logs maps to navigate to settings`() {
        assertEquals(
            BackPressAction.NavigateToSettings,
            policy.decide(ShellScreen.DEBUG_LOGS, isExpanded = false)
        )
    }

    @Test
    fun `sessions compact is root`() {
        assertEquals(
            BackPressAction.RootPrompt,
            policy.decide(ShellScreen.SESSIONS, isExpanded = false)
        )
    }

    @Test
    fun `sessions expanded dual pane is still root`() {
        // The expanded layout renders chat side-by-side inside SESSIONS; the
        // screen enum value does not change, so this must remain root.
        assertEquals(
            BackPressAction.RootPrompt,
            policy.decide(ShellScreen.SESSIONS, isExpanded = true)
        )
    }

    @Test
    fun `connect is root`() {
        assertEquals(
            BackPressAction.RootPrompt,
            policy.decide(ShellScreen.CONNECT, isExpanded = false)
        )
    }

    @Test
    fun `connect stays root regardless of layout`() {
        assertEquals(
            BackPressAction.RootPrompt,
            policy.decide(ShellScreen.CONNECT, isExpanded = true)
        )
    }

    @Test
    fun `policy is stateless across calls`() {
        // Repeated decisions must be pure: no hidden state may change the mapping.
        repeat(3) {
            assertEquals(
                BackPressAction.NavigateToSessions,
                policy.decide(ShellScreen.CHAT, isExpanded = false)
            )
            assertEquals(
                BackPressAction.RootPrompt,
                policy.decide(ShellScreen.SESSIONS, isExpanded = true)
            )
        }
    }
}

/**
 * CHG-006-S2 — fake-clock sequence tests for the stateful root-exit state
 * machine ([RootExitPolicy], plan TASK-005/008):
 * - first press -> SHOW_EXIT_PROMPT (caller shows the toast, records now)
 * - second press while now - lastPromptAt <= windowMs -> EXIT (caller finishes)
 * - press after the window expired -> SHOW_EXIT_PROMPT again (cycle restarts)
 * - the boundary is inclusive: exactly windowMs since the prompt exits
 * - the policy stays reusable after an EXIT decision
 */
class RootExitPolicyTest {

    /** Fake monotonic clock: advance it explicitly between presses. */
    private class FakeClock {
        var now: Long = 0L
        operator fun invoke(): Long = now
    }

    private val windowMs = 2000L

    private fun policyAt(clock: FakeClock) = RootExitPolicy(
        clock = { clock() },
        windowMs = windowMs
    )

    @Test
    fun `first press shows exit prompt`() {
        val clock = FakeClock()
        val policy = policyAt(clock)
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
    }

    @Test
    fun `second press within window exits`() {
        val clock = FakeClock()
        val policy = policyAt(clock)
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
        clock.now = 1500L
        assertEquals(RootExitDecision.EXIT, policy.onRootBackPress())
    }

    @Test
    fun `press after window expired re-prompts`() {
        val clock = FakeClock()
        val policy = policyAt(clock)
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
        clock.now = 2500L
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
    }

    @Test
    fun `inclusive boundary at exactly windowMs exits`() {
        val clock = FakeClock()
        val policy = policyAt(clock)
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
        clock.now = windowMs // now - last == 2000 -> still inside the window
        assertEquals(RootExitDecision.EXIT, policy.onRootBackPress())
    }

    @Test
    fun `one millisecond past boundary re-prompts`() {
        val clock = FakeClock()
        val policy = policyAt(clock)
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
        clock.now = windowMs + 1L
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
    }

    @Test
    fun `prompt then exit then expiry restarts the warning cycle`() {
        val clock = FakeClock()
        val policy = policyAt(clock)
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
        clock.now = 1000L
        assertEquals(RootExitDecision.EXIT, policy.onRootBackPress())
        clock.now = 3000L // window measured from the new prompt, not the old one
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
        clock.now = 4500L
        assertEquals(RootExitDecision.EXIT, policy.onRootBackPress())
    }

    @Test
    fun `policy remains reusable after exit`() {
        // After each EXIT the recorded prompt stays in place, so the next
        // cycle must be separated by more than windowMs to re-prompt; the
        // policy must keep yielding decisions without crashing.
        val clock = FakeClock()
        val policy = policyAt(clock)
        for (cycle in 1..3) {
            assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
            clock.now = (cycle - 1) * 5000L + 1000L
            assertEquals(RootExitDecision.EXIT, policy.onRootBackPress())
            clock.now = cycle * 5000L // well past the window -> fresh prompt next
        }
    }

    @Test
    fun `repeated prompts do not shrink the window`() {
        // A second press that only re-prompts (window expired) must reset the
        // reference timestamp — the next exit window is measured from the NEW
        // prompt, so an immediate next press still exits.
        val clock = FakeClock()
        val policy = policyAt(clock)
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
        clock.now = 5000L // expired -> re-prompt (not EXIT)
        assertEquals(RootExitDecision.SHOW_EXIT_PROMPT, policy.onRootBackPress())
        clock.now = 5000L + windowMs
        assertEquals(RootExitDecision.EXIT, policy.onRootBackPress())
    }
}
