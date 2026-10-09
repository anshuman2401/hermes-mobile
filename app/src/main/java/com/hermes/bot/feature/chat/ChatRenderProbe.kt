package com.hermes.bot.feature.chat

/** Optional in-process test observer. No content, identifiers, logging or storage. */
internal object ChatRenderProbe {
    @Volatile var beforePreparation: (suspend () -> Unit)? = null
    @Volatile var preparation: ((durationNanos: Long, completed: Boolean) -> Unit)? = null
    @Volatile var layout: ((canScrollForward: Boolean) -> Unit)? = null
}
