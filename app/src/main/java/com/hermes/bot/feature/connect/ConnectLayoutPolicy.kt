package com.hermes.bot.feature.connect

/** Layout only: available width excludes the screen's horizontal padding. */
internal object ConnectLayoutPolicy {
    fun stackSecondaryFields(availableWidthDp: Float, fontScale: Float): Boolean =
        availableWidthDp < 300f || fontScale > 1.2f
}
