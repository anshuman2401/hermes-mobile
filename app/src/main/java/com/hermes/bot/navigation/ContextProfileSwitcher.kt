package com.hermes.bot.navigation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.AtelierTheme
import com.hermes.bot.design.AtelierGroupRow
import com.hermes.bot.design.AtelierDivider
import com.hermes.bot.design.AtelierEditorial
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Stateless trigger IconButton for opening the Context Profiles (workspaces) bottom sheet.
 * Complies with the 48dp minimum touch target requirement and accessibility guidelines.
 */
@Composable
fun ContextProfileSwitcherTrigger(
    isHostConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dropdownOnly: Boolean = false
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(
                minWidth = ContextProfileSwitcherPolicy.MIN_TOUCH_TARGET_DP.dp,
                minHeight = ContextProfileSwitcherPolicy.MIN_TOUCH_TARGET_DP.dp
            )
            .semantics {
                contentDescription = ContextProfileSwitcherPolicy.TRIGGER_CONTENT_DESCRIPTION
            }
    ) {
        // Owner UI decision (2026-09-23): profile trigger shows the
        // green/red host-connection dot (SVG-style vector composition)
        // instead of the plain account glyph.
        if (dropdownOnly) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        } else {
            com.hermes.bot.design.ProfileStatusBadge(isConnected = isHostConnected)
        }
    }
}

/**
 * Modal bottom sheet listing authenticated Hermes Context Profiles (workspaces).
 * Supports explicit confirmation dialog before optimistic workspace activation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextProfileBottomSheet(
    state: ContextProfileSheetState,
    onDismissRequest: () -> Unit,
    onProfileClicked: (ContextProfileRowState) -> Unit = {},
    onConfirmSwitch: (String) -> Boolean = { false },
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    currentTheme: AppThemePreference = AppThemePreference.SYSTEM
) {
    AtelierTheme(currentTheme, updateSystemBars = false) {
        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var pendingConfirmProfileName by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current

        if (pendingConfirmProfileName != null) {
            val targetName = pendingConfirmProfileName!!
            AlertDialog(
                onDismissRequest = { pendingConfirmProfileName = null },
                title = { Text(ContextProfileSwitcherPolicy.CONFIRM_DIALOG_TITLE,
                    style = MaterialTheme.typography.headlineSmall) },
                text = { Text(ContextProfileSwitcherPolicy.confirmDialogMessage(targetName)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            ContextProfileSwitcherPolicy.confirmSwitch(
                                dismissDialog = { pendingConfirmProfileName = null },
                                dispatchSwitch = { onConfirmSwitch(targetName) },
                                closeSheet = onDismissRequest
                            )
                        }
                    ) {
                        Text(ContextProfileSwitcherPolicy.CONFIRM_BUTTON_LABEL)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingConfirmProfileName = null }
                    ) {
                        Text(ContextProfileSwitcherPolicy.DISMISS_BUTTON_LABEL)
                    }
                }
            )
        }

        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            // Keep the modal's window constraints intact so its expanded anchor
            // reaches the bottom. Bound only the scrollable content below.
            modifier = modifier,
            sheetGesturesEnabled = false,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth().heightIn(max = maxHeight)
                    .padding(horizontal = 22.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = ContextProfileSwitcherPolicy.TITLE,
                    fontFamily = AtelierEditorial, fontSize = 28.sp, lineHeight = 34.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                when (state) {
                    is ContextProfileSheetState.Loading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                text = ContextProfileSwitcherPolicy.LOADING_MESSAGE,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                    is ContextProfileSheetState.Error -> {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp)
                        )
                    }
                    is ContextProfileSheetState.Empty -> {
                        Text(
                            text = ContextProfileSwitcherPolicy.ERROR_EMPTY,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp)
                        )
                    }
                    is ContextProfileSheetState.Loaded -> {
                        // PR-2 REQ-05: provenance caption directly under the title —
                        // policy-owned copy, rendered only for a cached roster.
                        ContextProfileSwitcherPolicy
                            .offlineRosterCaption(
                                source = state.source,
                                capturedAtMillis = state.capturedAtMillis,
                                nowMillis = System.currentTimeMillis()
                            )
                            ?.let { caption ->
                                Text(
                                    text = caption,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)
                                )
                            }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            items(state.profiles, key = { it.name }) { profile ->
                                ContextProfileRowItem(
                                    profile = profile,
                                    first = profile.name == state.profiles.first().name,
                                    last = profile.name == state.profiles.last().name,
                                    onClick = {
                                        onProfileClicked(profile)
                                        when (val action = ContextProfileSwitcherPolicy.handleRowClick(profile, isStreaming)) {
                                            is ContextProfileClickAction.RequestConfirm -> {
                                                pendingConfirmProfileName = action.profileName
                                            }
                                            is ContextProfileClickAction.Disabled -> {
                                                Toast.makeText(context, action.message, Toast.LENGTH_SHORT).show()
                                            }
                                            ContextProfileClickAction.NoOp -> {}
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextProfileRowItem(
    profile: ContextProfileRowState,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AtelierGroupRow(first, last, modifier.fillMaxWidth()
        .defaultMinSize(minHeight = ContextProfileSwitcherPolicy.MIN_TOUCH_TARGET_DP.dp)
        .clickable(onClick = onClick)
        .semantics { contentDescription = ContextProfileSwitcherPolicy.rowContentDescription(profile) }) {
        Column {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val stackBadges = LocalDensity.current.fontScale > 1.2f
            Column(Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (profile.isActive) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth()
            )
            if (stackBadges) WorkspaceBadges(profile)
            }
            if (!stackBadges) WorkspaceBadges(profile)
        }
        if (!last) AtelierDivider()
        }
    }
}

@Composable
private fun WorkspaceBadges(profile: ContextProfileRowState) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (profile.isRunning) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Text(
                            text = "Running",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (profile.isActive) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
}
