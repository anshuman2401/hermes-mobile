package com.hermes.bot.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Owner UI decision (2026-09-23): the workspace-profile trigger carries a
 * connection status indicator instead of a plain account glyph, and the
 * separate "Connected" text labels / Disconnect buttons are removed across
 * Connect, Sessions, and Settings surfaces. Provenance recorded in
 * specs/learnings/2026-09/2026-09-23-profile-status-dot-ui-change.md.
 */
object ProfileStatusBadgePolicy {
    const val ICON_NAME = "Profile Status Badge"
    const val CONNECTED_DESCRIPTION: String = "Host connected"
    const val DISCONNECTED_DESCRIPTION: String = "Host not connected"

    fun statusDescription(isConnected: Boolean): String =
        if (isConnected) CONNECTED_DESCRIPTION else DISCONNECTED_DESCRIPTION

    /** Pure dot-color decision: green family when connected, red family when not. */
    fun dotColor(isConnected: Boolean, connectedColor: Color, disconnectedColor: Color): Color =
        if (isConnected) connectedColor else disconnectedColor
}

/**
 * Compact connection status dot: green when the host is connected, red when
 * it is not. Non-color accessible via liveRegion-friendly contentDescription.
 */
@Composable
fun ConnectionStatusDot(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor = ProfileStatusBadgePolicy.dotColor(
        isConnected = isConnected,
        connectedColor = MaterialTheme.colorScheme.secondary,
        disconnectedColor = MaterialTheme.colorScheme.error
    )
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(dotColor)
            .semantics {
                contentDescription = ProfileStatusBadgePolicy.statusDescription(isConnected)
            }
    )
}

/**
 * Workspace-profile glyph with an overlaid green/red host-connection dot.
 * Original project-owned vector composition (person glyph + status dot);
 * contains 0% copied Hermes Go assets.
 */
@Composable
fun ProfileStatusBadge(
    isConnected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp
) {
    Box(
        modifier = modifier.defaultMinSize(minWidth = size, minHeight = size),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size)
        )
        ConnectionStatusDot(
            isConnected = isConnected,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 1.dp, bottom = 3.dp)
        )
    }
}