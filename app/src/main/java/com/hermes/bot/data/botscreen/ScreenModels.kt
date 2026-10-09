package com.hermes.bot.data.botscreen

/** No ticket, viewer capability, framebuffer, or host path belongs in presentation state. */
data class ScreenLease(val holder: Holder, val viewerHash: String?, val epoch: Long) {
    enum class Holder { BOT, HUMAN }
}

data class ScreenStatus(
    val supported: Boolean,
    val installed: Boolean,
    val running: Boolean,
    val blocker: String?,
    val geometry: String,
    val lease: ScreenLease
)

data class ScreenObservation(
    val status: ScreenStatus,
    val ticket: String,
    val viewerId: String
)

data class ScreenFrame(val width: Int, val height: Int, val pixels: IntArray)
