package com.hermes.bot.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hermes.bot.data.local.SignOutCacheChoice

/**
 * PR-6 REQ-06: read-only, test-injected sign-out choice dialog policy —
 * exact literal copy per the plan's dialog contract (Interfaces section).
 * Nothing in production derives, truncates, or dynamically builds these
 * strings; TEST-04 pins them.
 *
 * Mirrors [ProfileRemoveConfirmationPolicy]: decision logic lives here so
 * the JVM suite can pin it without a Compose harness (repo has none).
 */
object SignOutCacheChoicePolicy {

    fun title(): String = "Sign out"

    fun message(): String =
        "Sign out of this connection? Remembered credentials stay encrypted on this device. Automatic login pauses until you connect again. Choose what to do with this device's saved offline cache."

    /** REQ-03: exact option label (plan literal). */
    fun keepLabel(): String = "Keep saved cache on this device"

    /** REQ-03: exact option label (plan literal). */
    fun clearLabel(): String = "Clear saved cache"

    /**
     * REQ-03: the dialog ALWAYS opens on Keep-cache — both the Settings and
     * the Connect/Sessions entries share this single default; choosing
     * CLEAR is always an active selection (never the default).
     */
    fun defaultSelection(): SignOutCacheChoice = SignOutCacheChoice.KEEP

    /** REQ-06: executes whatever is visibly selected (WYSIWYG confirm). */
    fun confirmLabel(): String = "Sign out"

    /** REQ-06: cancel/back/dismiss aborts sign-out entirely — IDLE, zero mutations. */
    fun cancelLabel(): String = "Cancel"

    /**
     * REQ-02/REQ-06: the final, explicitly designated choice handed from the
     * dialog's confirm button down the threading chain (no hidden default).
     */
    fun resolve(selected: SignOutCacheChoice): SignOutCacheChoice = when (selected) {
        SignOutCacheChoice.KEEP -> SignOutCacheChoice.KEEP
        SignOutCacheChoice.CLEAR -> SignOutCacheChoice.CLEAR
    }
}

/**
 * PR-6 REQ-03/REQ-06: the ONE shared confirmation dialog, rendered at the
 * shell's sign-out funnel. Opens on Keep-cache (default selection), every
 * label is an exact policy literal, cancel/back/dismiss aborts without any
 * choice escaping; only confirm emits a resolved choice downstream.
 */
@Composable
fun SignOutCacheChoiceDialog(
    onConfirm: (SignOutCacheChoice) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by rememberSaveable { mutableStateOf(SignOutCacheChoicePolicy.defaultSelection()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SignOutCacheChoicePolicy.title()) },
        text = {
            Column {
                Text(SignOutCacheChoicePolicy.message())
                Spacer(modifier = Modifier.height(8.dp))
                SignOutCacheChoice.entries.forEach { choice ->
                    val optionLabel = when (choice) {
                        SignOutCacheChoice.KEEP -> SignOutCacheChoicePolicy.keepLabel()
                        SignOutCacheChoice.CLEAR -> SignOutCacheChoicePolicy.clearLabel()
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == choice,
                                onClick = { selected = choice },
                                role = Role.RadioButton
                            )
                    ) {
                        RadioButton(
                            selected = selected == choice,
                            onClick = { selected = choice }
                        )
                        Text(text = optionLabel)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(SignOutCacheChoicePolicy.resolve(selected)) }) {
                Text(SignOutCacheChoicePolicy.confirmLabel())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(SignOutCacheChoicePolicy.cancelLabel())
            }
        }
    )
}
