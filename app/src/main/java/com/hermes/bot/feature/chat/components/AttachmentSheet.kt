package com.hermes.bot.feature.chat.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermes.bot.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentMenu(expanded: Boolean, onCamera: () -> Unit, onPhotos: () -> Unit, onDocuments: () -> Unit, onDismiss: () -> Unit) {
    if (!expanded) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Add attachment", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close attachment menu") }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AttachmentAction("Camera", "Take photo", R.drawable.ic_attachment_camera, onCamera, Modifier.fillMaxWidth(), highlighted = true, horizontal = true)
                        AttachmentAction("Photos", "Choose photo", R.drawable.ic_attachment_photos, onPhotos, Modifier.fillMaxWidth(), horizontal = true)
                        AttachmentAction("Documents", "Choose document", R.drawable.ic_attachment_document, onDocuments, Modifier.fillMaxWidth(), horizontal = true)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AttachmentAction("Camera", "Take photo", R.drawable.ic_attachment_camera, onCamera, Modifier.weight(1f), highlighted = true)
                        AttachmentAction("Photos", "Choose photo", R.drawable.ic_attachment_photos, onPhotos, Modifier.weight(1f))
                        AttachmentAction("Documents", "Choose document", R.drawable.ic_attachment_document, onDocuments, Modifier.weight(1f))
                    }
                }
            }
            Text("Take a photo or choose an existing file.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
        }
    }
}

@Composable
private fun AttachmentAction(label: String, description: String, @DrawableRes icon: Int,
    onClick: () -> Unit, modifier: Modifier, highlighted: Boolean = false, horizontal: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val actionModifier = modifier.clip(RoundedCornerShape(20.dp))
        .background(if (highlighted) colors.primaryContainer else colors.background)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .heightIn(min = if (horizontal) 72.dp else 100.dp)
        .padding(horizontal = if (horizontal) 18.dp else 4.dp, vertical = 16.dp)
    val content: @Composable () -> Unit = {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp),
            tint = if (highlighted) colors.primary else colors.onSurface)
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            color = if (highlighted) colors.primary else colors.onSurface)
    }
    if (horizontal) Row(actionModifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    else Column(actionModifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) { content() }
}
