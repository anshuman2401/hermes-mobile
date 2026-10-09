package com.hermes.bot.feature.chat

import com.hermes.bot.domain.ConnectionStatus

/** JVM-pure presentation policy for the chat header. */
object ChatScreenTopBarPolicy {
    const val REFRESH_LABEL = "Refresh chat"

    fun actionsOrder(hasRefresh: Boolean = false): List<String> = buildList {
        if (hasRefresh) add(REFRESH_LABEL)
    }

    fun title(serverTitle: String?, isNew: Boolean): String = serverTitle?.takeUnless { it.isBlank() }
        ?: if (isNew) NEW_CONVERSATION_HEADER_LABEL else UNTITLED_CONVERSATION_HEADER_LABEL

    fun isConnected(state: ChatUiState?): Boolean =
        state?.headerConnectionStatus == ConnectionStatus.CONNECTED && state.savedSnapshotCapturedAtMillis == null

    fun connectionLabel(state: ChatUiState?): String =
        if (state?.savedSnapshotCapturedAtMillis != null) "Saved snapshot · Offline"
        else when (state?.headerConnectionStatus) {
            ConnectionStatus.CONNECTED -> "Connected"
            ConnectionStatus.CONNECTING -> "Connecting…"
            ConnectionStatus.AUTHENTICATION_REQUIRED -> "Sign in required"
            ConnectionStatus.FAILED -> "Disconnected"
            ConnectionStatus.OFFLINE, null -> "Offline"
        }
}
