package com.hermes.bot.feature.chat

/**
 * Pure stateful edge gate for scroll-to-top load-earlier triggering.
 * TASK-006 (frozen plan CHAT-004): fires ONLY on a false->true edge of atTop
 * while no fetch is in flight, the history beginning has not been reached,
 * initial loading is not running, and the timeline is non-empty. A held top
 * position never re-fires; the user must leave index 0 and return.
 */
class LoadEarlierTriggerPolicy(
    initialAtTop: Boolean = false
) {
    private var lastAtTop: Boolean = initialAtTop

    fun onAtTopChanged(
        atTop: Boolean,
        fetchInFlight: Boolean,
        historyBeginningReached: Boolean,
        isInitialLoading: Boolean,
        timelineNonEmpty: Boolean
    ): Boolean {
        val wasAtTop = lastAtTop
        lastAtTop = atTop
        return !wasAtTop && atTop &&
            !fetchInFlight &&
            !historyBeginningReached &&
            !isInitialLoading &&
            timelineNonEmpty
    }
}
