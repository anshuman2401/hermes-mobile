package com.hermes.bot.feature.chat.files

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.bot.data.files.*
import kotlinx.coroutines.*

@Composable
fun ConversationFileCard(reference: ConversationFile, onOpen: () -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(reference.name, style = MaterialTheme.typography.titleSmall)
            Text(if (reference.created) "Created in this conversation" else "Shared in this conversation",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ConversationFilesScreen(state: ConversationFilesState, controller: ConversationFilesController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportContent by remember { mutableStateOf<FileContent?>(null) }
    var actionMessage by remember(state.content) { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val content = exportContent
        exportContent = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && content != null && uri != null) scope.launch {
            actionBusy = true
            try {
                actionMessage = if (controller.export(content) { AndroidFileActions.save(context, uri, it) }) "File saved." else FileFailure.STALE.message
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionMessage = "Could not save the file. Try again." }
            finally { actionBusy = false }
        }
    }
    Dialog(onDismissRequest = controller::back, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = controller::back) { Text("Back") }
                    Text(if (state.selected == null) "Conversation files" else state.selected.name,
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
                if (state.selected == null) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                        item { Text("Files referenced in loaded history", style = MaterialTheme.typography.labelLarge)
                            Text("Load earlier messages in chat to include older files. Up to 100 files are shown.", style = MaterialTheme.typography.bodySmall)
                            if (!state.online) Text("Reconnect to open files from Hermes.")
                            if (state.limited) Text("Some references exceed the display limit.")
                            state.message?.let { Text(it) }
                        }
                        if (state.items.isEmpty()) item { Text("No files shared in the loaded conversation yet.", Modifier.padding(vertical = 32.dp)) }
                        items(state.items, key = { it.key }) { file -> ConversationFileCard(file) { controller.open(file) } }
                    }
                } else {
                    val content = state.content
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(if (state.selected.created) "Created in this conversation" else "Shared in this conversation", style = MaterialTheme.typography.labelMedium)
                        Text("Current file on Hermes · not a versioned snapshot", style = MaterialTheme.typography.bodySmall)
                        if (state.loading) CircularProgressIndicator(Modifier.padding(24.dp))
                        state.message?.let { Text(it, Modifier.padding(vertical = 16.dp)); TextButton(onClick = { controller.open(state.selected) }) { Text("Retry") } }
                        if (content != null) NativeFilePreview(content)
                        actionMessage?.let { Text(it, Modifier.padding(vertical = 8.dp)) }
                    }
                    if (content != null) {
                        FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !actionBusy && exportContent == null, onClick = {
                                exportContent = content
                                try { save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType(FilePreviewPolicy.mime(content.reference.name)).putExtra(Intent.EXTRA_TITLE, content.reference.name)) }
                                catch (_: Exception) { exportContent = null; actionMessage = "No app is available to save this file." }
                            }) { Text("Save") }
                            OutlinedButton(enabled = !actionBusy && exportContent == null, onClick = {
                                scope.launch {
                                    actionBusy = true
                                    try { if (!controller.export(content) { AndroidFileActions.share(context, it, false) }) actionMessage = FileFailure.STALE.message }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { actionMessage = "Could not share this file." }
                                    finally { actionBusy = false }
                                }
                            }) { Text("Share") }
                            OutlinedButton(enabled = !actionBusy && exportContent == null, onClick = {
                                scope.launch {
                                    actionBusy = true
                                    try { if (!controller.export(content) { AndroidFileActions.share(context, it, true) }) actionMessage = FileFailure.STALE.message }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { actionMessage = "No compatible app could open this file." }
                                    finally { actionBusy = false }
                                }
                            }) { Text("Open in app") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeFilePreview(content: FileContent) {
    val context = LocalContext.current
    var page by remember(content) { mutableIntStateOf(0) }
    var preview by remember(content) { mutableStateOf<FilePreview?>(null) }
    LaunchedEffect(content, page) {
        preview = null
        val result = AndroidFileActions.preview(context, content, page)
        preview = result
    }
    // Bitmap reclamation is left to Compose/GC: do not recycle a frame still queued for drawing.
    val shown = preview
    if (shown == null) CircularProgressIndicator(Modifier.padding(24.dp))
    else {
        shown.bitmap?.let { Image(it.asImageBitmap(), "File preview", Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
        shown.text?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp)) } }
        shown.notice?.let { Text(it, Modifier.padding(vertical = 12.dp)) }
        if (shown.pages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }
            Text("${page + 1} / ${shown.pages}")
            TextButton(enabled = page + 1 < shown.pages, onClick = { page++ }) { Text("Next") }
        }
        Text("${content.size / 1024} KB", style = MaterialTheme.typography.bodySmall)
    }
}
