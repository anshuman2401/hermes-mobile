package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun AttachmentMenu(expanded: Boolean, onPhotos: () -> Unit, onDocuments: () -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            text = "Add attachment",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        DropdownMenuItem(
            text = { Text("Photos") },
            onClick = onPhotos,
            leadingIcon = { Icon(Icons.Default.AccountBox, contentDescription = null) },
            modifier = Modifier.semantics { contentDescription = "Choose photo" }
        )
        DropdownMenuItem(
            text = { Text("Documents") },
            onClick = onDocuments,
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            modifier = Modifier.semantics { contentDescription = "Choose document" }
        )
    }
}
