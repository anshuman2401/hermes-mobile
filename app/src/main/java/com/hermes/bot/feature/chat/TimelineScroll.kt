package com.hermes.bot.feature.chat

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** User scroll cancellation ends one correction, not the layout observer. */
internal suspend fun runTimelineScroll(onJob: (Job?) -> Unit, scroll: suspend () -> Unit) {
    coroutineScope {
        val correction = launch(start = CoroutineStart.LAZY) { scroll() }
        onJob(correction)
        try {
            correction.start()
            correction.join()
        } finally {
            onJob(null)
        }
    }
}
