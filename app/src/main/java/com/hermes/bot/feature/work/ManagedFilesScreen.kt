package com.hermes.bot.feature.work

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.hermes.bot.data.files.*
import com.hermes.bot.design.RelayOrbitIcon
import com.hermes.bot.feature.chat.files.AndroidFileActions
import com.hermes.bot.feature.chat.files.FilePreview
import kotlinx.coroutines.awaitCancellation
import java.util.Locale

@Composable
fun ManagedFilesScreen(viewModel: ManagedFilesViewModel?, connectionKey: String?, online: Boolean,
    onSettings: () -> Unit, onReconnect: () -> Unit, onRootBack: () -> Unit,
    sections: @Composable () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = viewModel?.state?.collectAsStateWithLifecycle()?.value ?: ManagedFilesState()
    var saveEntry by remember { mutableStateOf<ManagedFileEntry?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = saveEntry
        saveEntry = null
        if (uri != null && entry != null) viewModel?.downloadAndSave(entry) {
            context.contentResolver.openOutputStream(uri, "wt")
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val source = selectedUploadSource(context, uri)
            if (source != null) viewModel?.upload(source) else viewModel?.selectionFailed()
        }
    }
    LaunchedEffect(viewModel, connectionKey, online) { viewModel?.bind(connectionKey, online) }
    LaunchedEffect(viewModel, lifecycle, connectionKey, online) {
        if (viewModel == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refresh()
            try { awaitCancellation() } finally { viewModel.pageStopped() }
        }
    }
    BackHandler { if (viewModel?.back() != true) onRootBack() }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkSectionLabel("Work", Modifier.weight(1f))
            RelayOrbitIcon(size = 18.dp)
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Settings, "Open settings") }
        }
        sections()
        Spacer(Modifier.height(17.dp))
        Text("Hermes files", style = MaterialTheme.typography.titleLarge)
        Text("Folders on this Hermes host", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val listing = state.listing
        if (listing != null && state.preview == null) {
            var pathDraft by rememberSaveable(listing.path, connectionKey) { mutableStateOf(listing.path) }
            val canNavigate = state.online && !state.loading && !state.busy
            val canChangePath = canNavigate && listing.canChangePath
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = pathDraft, onValueChange = {
                    pathDraft = it
                    viewModel?.clearPathError()
                }, label = { Text("Go to folder") }, singleLine = true,
                    enabled = canChangePath, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        viewModel?.openPath(pathDraft)
                        keyboard?.hide()
                    }))
                Button(onClick = { viewModel?.openPath(pathDraft); keyboard?.hide() },
                    enabled = canChangePath, modifier = Modifier.heightIn(min = 48.dp)) { Text("Go") }
            }
            state.pathError?.let { Text(it, Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (!listing.canChangePath) Text("Direct path entry is unavailable on this host.",
                Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                listing.parent?.let { parent ->
                    TextButton(onClick = { viewModel?.openParent(parent) }, enabled = canNavigate,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text("Up")
                    }
                } ?: Spacer(Modifier.width(1.dp))
                Row(Modifier.clickable { viewModel?.setHideHiddenFiles(!state.hideHiddenFiles) }
                    .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.hideHiddenFiles, onCheckedChange = null)
                    Text("Hide hidden files", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Names beginning with .", Modifier.align(Alignment.End),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!state.online) TextButton(onClick = onReconnect) { Text("Reconnect") }
        else if (state.message != null && !state.loading && !state.busy)
            TextButton(onClick = { viewModel?.refresh() }) { Text("Refresh") }
        if (state.preview != null) {
            ManagedPreviewPane(state.preview, onBack = { viewModel?.back() })
        } else if (listing == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
                else Text(if (viewModel == null) "Files are unavailable in this preview."
                    else if (!state.online) "Reconnect to browse files." else "Could not load Hermes files.")
            }
        } else {
            val entries = state.visibleEntries
            if (listing.limited) Text("Showing the first 1,000 entries.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (entries.isEmpty()) item { Text(if (state.hideHiddenFiles && listing.entries.isNotEmpty())
                    "No visible files in this folder." else "This folder is empty.", Modifier.padding(vertical = 32.dp)) }
                items(entries, key = { it.path }) { entry ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                        val canOpen = !state.busy && !state.loading && !entry.brokenLink
                        Row(Modifier.weight(1f).clickable(enabled = canOpen) {
                            if (entry.directory) viewModel?.openFolder(entry) else viewModel?.openPreview(entry)
                        }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (entry.directory) "▱" else "▤", Modifier.width(34.dp),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                            Column {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(if (entry.brokenLink) "Unavailable link" else if (entry.directory) "Folder"
                                    else formatSize(entry.size), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!entry.directory && !entry.brokenLink) TextButton(enabled = canOpen,
                            onClick = { saveEntry = entry; save.launch(entry.name) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Download")
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${entries.count { it.directory }} folders · ${entries.count { !it.directory }} files",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = state.online && !state.busy && !state.loading,
                    onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Upload here")
                }
            }
        }
    }
    state.conflictName?.let { name ->
        AlertDialog(onDismissRequest = { viewModel?.dismissConflict() },
            title = { Text("Replace existing file?") },
            text = { Text("A file named $name already exists in this folder.") },
            confirmButton = { TextButton(onClick = { viewModel?.replace() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { viewModel?.dismissConflict() }) { Text("Cancel") } })
    }
}

private fun selectedUploadSource(context: Context, uri: android.net.Uri): ManagedUploadSource? = runCatching {
    var name: String? = null
    var size: Long? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            name = cursor.getString(0)
            if (!cursor.isNull(1)) size = cursor.getLong(1)
        }
    }
    val accepted = ManagedFilePaths.uploadName(name ?: uri.lastPathSegment ?: return@runCatching null)
        ?: return@runCatching null
    ManagedUploadSource(accepted, size) {
        context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Selected document unavailable")
    }
}.getOrNull()

private fun formatSize(size: Long?): String = when {
    size == null -> "File"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", size / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024))
}

@Composable
private fun ColumnScope.ManagedPreviewPane(preview: ManagedFilePreview, onBack: () -> Unit) {
    val context = LocalContext.current
    var page by remember(preview) { mutableIntStateOf(0) }
    var rendered by remember(preview, page) { mutableStateOf<FilePreview?>(null) }
    LaunchedEffect(preview, page) { rendered = AndroidFileActions.preview(context, preview.bytes, preview.name, page) }
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to folder") }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(preview.name, style = MaterialTheme.typography.titleMedium)
        val shown = rendered
        if (shown == null) CircularProgressIndicator(Modifier.padding(20.dp))
        else {
            shown.bitmap?.let { Image(it.asImageBitmap(), "File preview", Modifier.fillMaxWidth()) }
            shown.text?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace) } }
            shown.notice?.let { Text(it, Modifier.padding(vertical = 12.dp)) }
            if (shown.pages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }
                Text("${page + 1} / ${shown.pages}")
                TextButton(enabled = page + 1 < shown.pages, onClick = { page++ }) { Text("Next") }
            }
        }
    }
}
