package com.hermes.bot.feature.bots

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.AtelierTheme
import com.hermes.bot.design.AtelierShapes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.getValue
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.hermes.bot.data.gateway.BotAvatar as BotAvatarAsset
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import com.hermes.bot.navigation.BottomNavIcons
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.bot.feature.sessions.SessionsUiState
import com.hermes.bot.feature.sessions.relativeActivityLabel
import java.time.Instant

@Composable
private fun BotAvatar(avatar: BotAvatarAsset?, size: Dp = 40.dp,
    hashFallback: Boolean = false) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            val bitmap by produceState<ImageBitmap?>(null, avatar) { value = decodeBotAvatar(avatar?.bytes, ::decodeThumbnail) }
            val decoded = bitmap
            if (decoded != null) Image(decoded, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (hashFallback) Text("#", style = MaterialTheme.typography.titleLarge)
            else Icon(BottomNavIcons.Bots, contentDescription = null, modifier = Modifier.size(size * 0.7f))
        }
    }
}

@Composable
fun BotChannelComposer(state: BotChannelUiState, ready: Boolean, onDraft: (String) -> Unit,
    onSend: () -> Unit, onOpenCreated: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
        if (state.createdSessionId != null) TextButton(onClick = onOpenCreated, enabled = !state.transferring) {
            Text("Open created conversation")
        }
        OutlinedTextField(value = state.draft, onValueChange = onDraft,
            enabled = !state.transferring && state.createdSessionId == null,
            modifier = Modifier.fillMaxWidth(), maxLines = 6, shape = AtelierShapes.field,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
            placeholder = { Text("Start a thread with ${state.displayName ?: state.channel.orEmpty()}…") },
            trailingIcon = { IconButton(onClick = onSend,
                enabled = ready && !state.transferring && state.createdSessionId == null && state.draft.isNotBlank()) {
                if (state.transferring) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Start conversation")
            } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotChannelScreen(state: BotChannelUiState, sessions: SessionsUiState, onBack: () -> Unit,
    onRefresh: () -> Unit, onOpen: (String) -> Unit,
    locked: Boolean = false, onVisibleThreads: (List<String>) -> Unit = {},
    currentTheme: AppThemePreference = AppThemePreference.SYSTEM, composer: @Composable () -> Unit = {},
    embedded: Boolean = false, onScrollPosition: (String, Int, Int, String?, String?) -> Unit = { _, _, _, _, _ -> }) {
    AtelierTheme(currentTheme) {
        val ready = isChannelListReady(state.channel, sessions)
        val conversations = androidx.compose.runtime.remember(sessions.sessions) { botBoardConversations(sessions.sessions) }
        val listState = androidx.compose.runtime.remember(state.channel, state.connectionId, state.normalizedOrigin) {
            androidx.compose.foundation.lazy.LazyListState(state.scrollIndex, state.scrollOffset)
        }
        DisposableEffect(listState) {
            val name = state.channel
            val connectionId = state.connectionId
            val origin = state.normalizedOrigin
            onDispose { if (name != null) onScrollPosition(name, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, connectionId, origin) }
        }
        LaunchedEffect(listState) {
            val name = state.channel
            if (name != null) snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> onScrollPosition(name, index, offset, state.connectionId, state.normalizedOrigin) }
        }
        LaunchedEffect(state.channel, conversations, ready, sessions.isOfflineCache, sessions.error) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
                .collect { onVisibleThreads(if (ready && !sessions.isOfflineCache && sessions.error == null) it.filter { id -> conversations.any { thread -> thread.id == id } } else emptyList()) }
        }
        DisposableEffect(state.channel) { onDispose { onVisibleThreads(emptyList()) } }
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            if (!embedded) TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotAvatar(state.avatar, 32.dp, hashFallback = true)
                Column(Modifier.weight(1f)) {
                    Text(state.channel.orEmpty(), style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (ready) "${conversations.size} conversations" else "Conversations",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }, navigationIcon = { IconButton(onClick = onBack, enabled = !locked) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to bots")
            } }, actions = { IconButton(onClick = onRefresh, enabled = !locked && !sessions.isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh conversations")
            } }, colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background))
        }, bottomBar = { Box((if (embedded) Modifier else Modifier.navigationBarsPadding()).imePadding()) { composer() } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (sessions.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (sessions.isOfflineCache && ready) Text("Offline cache", Modifier.padding(16.dp))
                if (!ready && !sessions.isLoading) Text("Bot selection is unavailable. Choose a bot from All bots.", Modifier.padding(16.dp))
                if (sessions.error != null) {
                    Text("Couldn't load conversations. Please retry.", Modifier.padding(16.dp))
                    TextButton(onClick = onRefresh, enabled = !locked) { Text("Retry") }
                }
                if (ready) {
                    if (conversations.isEmpty()) Text("No conversations yet. Send a message to start one.", Modifier.padding(16.dp))
                    // The authoritative list is newest-first; index zero starts beside the composer.
                    LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.Bottom)) {
                        items(conversations, key = { it.id }) { thread ->
                            BotThreadPost(thread.threadPresentation(), state.displayName ?: state.channel.orEmpty(), state.avatar,
                                state.threadReplies[thread.id]?.text, locked) { onOpen(thread.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BotThreadPost(thread: BotThreadPresentation, bot: String, avatar: BotAvatarAsset?,
    reply: String?, locked: Boolean, onOpen: () -> Unit) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.width(34.dp).fillMaxHeight()) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Canvas(Modifier.width(34.dp).weight(1f).padding(top = 6.dp)) {
                    drawLine(lineColor, androidx.compose.ui.geometry.Offset(17.dp.toPx(), 0f),
                        androidx.compose.ui.geometry.Offset(17.dp.toPx(), size.height), strokeWidth = 2.dp.toPx())
                }
            }
            Column(Modifier.weight(1f)) {
                Text(if (thread.opening != null) "User" else "Conversation", style = MaterialTheme.typography.titleSmall)
                thread.startedAt?.let { Text("Started ${relativeActivityLabel(Instant.now(), it)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(thread.opening ?: "Opening message unavailable", Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    color = if (thread.opening != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Canvas(Modifier.width(44.dp).fillMaxHeight()) {
                val x = 17.dp.toPx()
                val y = size.height * 0.55f
                val radius = 12.dp.toPx().coerceAtMost(y)
                drawPath(Path().apply {
                    moveTo(x, 0f); lineTo(x, y - radius)
                    quadraticTo(x, y, x + radius, y); lineTo(size.width, y)
                }, lineColor, style = Stroke(width = 2.dp.toPx()))
            }
            Surface(onClick = onOpen, enabled = !locked, shape = AtelierShapes.panel,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f).padding(top = 18.dp)
                    .semantics { contentDescription = "Open thread: ${thread.title}" }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(thread.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        thread.messageCount?.let { Text("$it", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { contentDescription = "$it messages" }) }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    if (reply != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BotAvatar(avatar, 20.dp)
                            Text(bot, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(reply, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    } else Text("Open conversation", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
