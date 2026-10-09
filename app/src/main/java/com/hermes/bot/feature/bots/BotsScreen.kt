package com.hermes.bot.feature.bots

import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hermes.bot.data.botscreen.ScreenLease
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.bot.R
import com.hermes.bot.data.bots.BotRow
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.AtelierAppChrome
import com.hermes.bot.design.AtelierTheme
import com.hermes.bot.design.atelierSelectionColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private val botScreenIcon: ImageVector by lazy {
    ImageVector.Builder("Bot screen", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 4f)
            lineTo(21f, 4f)
            lineTo(21f, 18f)
            lineTo(3f, 18f)
            close()
            moveTo(12f, 18f)
            lineTo(12f, 21f)
            moveTo(8f, 21f)
            lineTo(16f, 21f)
        }
    }.build()
}

internal fun botInitial(name: String): String {
    val text = name.trim()
    return if (text.isEmpty()) "?" else String(Character.toChars(text.codePointAt(0))).uppercase(java.util.Locale.ROOT)
}
internal suspend fun <T> decodeBotAvatar(bytes: ByteArray?, decoder: suspend (ByteArray) -> T?): T? {
    if (bytes == null) return null
    return try { decoder(bytes) } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }
}

internal suspend fun decodeThumbnail(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 160) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(
    viewModel: BotsViewModel? = null,
    localId: String? = null,
    normalizedOrigin: String? = null,
    connected: Boolean = false,
    modifier: Modifier = Modifier,
    decoder: suspend (ByteArray) -> ImageBitmap? = ::decodeThumbnail,
    onSelect: (BotRow) -> Unit = {},
    onOpenScreen: (BotRow) -> Unit = {},
    selecting: Boolean = false,
    onNavigateToSettings: () -> Unit = {},
    currentTheme: AppThemePreference = AppThemePreference.SYSTEM,
    channel: String? = null,
    conversationCount: Int? = null,
    onRefreshChannel: () -> Unit = {},
    channelContent: @Composable () -> Unit = {}
) {
    val fallback = remember { MutableStateFlow<BotsUiState>(BotsUiState.Disconnected) }
    val state by (viewModel?.uiState ?: fallback).collectAsStateWithLifecycle()
    val favouriteFallback = remember { MutableStateFlow(BotsViewModel.FavouritesState()) }
    val favourites by (viewModel?.favourites ?: favouriteFallback).collectAsStateWithLifecycle()
    val screenFallback = remember { MutableStateFlow(BotScreenOverviewState()) }
    val screen by (viewModel?.screenState ?: screenFallback).collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showPicker by androidx.compose.runtime.saveable.rememberSaveable(localId, normalizedOrigin) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    DisposableEffect(viewModel, localId, normalizedOrigin) {
        viewModel?.enter(localId, normalizedOrigin)
        onDispose { viewModel?.leave() }
    }
    val admitted = viewModel?.presentation(state, localId, normalizedOrigin, connected) ?: BotsUiState.Disconnected
    val rows = (admitted as? BotsUiState.Content)?.rows.orEmpty()
    val selectedRow = rows.firstOrNull { it.profile.name == channel }
    var showStopConfirmation by androidx.compose.runtime.saveable.rememberSaveable(localId, normalizedOrigin, channel) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    LaunchedEffect(viewModel, selectedRow?.profile?.name, (admitted as? BotsUiState.Content)?.scope, lifecycleOwner) {
        viewModel?.selectScreen(selectedRow?.profile?.name)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                viewModel?.refreshScreenStatus()
                delay(15_000)
            }
        }
    }
    val actionsAllowed = !selecting && admitted is BotsUiState.Content
    val favouritesAllowed = actionsAllowed && favourites.available && !favourites.loading && !favourites.saving
    val selectBot by androidx.compose.runtime.rememberUpdatedState(onSelect)
    androidx.compose.runtime.LaunchedEffect(viewModel, admitted, favourites, localId, normalizedOrigin, connected, channel, selecting) {
        viewModel?.takeInitialFavourite(admitted, localId, normalizedOrigin, connected, channel, actionsAllowed)
            ?.let { selectBot(it) }
    }
    AtelierTheme(currentTheme) {
        Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                AtelierAppChrome(title = stringResource(R.string.nav_bots)) {
                    IconButton(onClick = onNavigateToSettings, enabled = !selecting, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Open settings")
                    }
                }
            }
            if (admitted is BotsUiState.Content) {
                Text("Favourites", Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(favourites.names.mapNotNull { name -> rows.firstOrNull { it.profile.name == name } }, key = { it.profile.name }) { row ->
                        val active = row.profile.name == channel
                        androidx.compose.material3.Surface(onClick = { onSelect(row) }, enabled = actionsAllowed,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            color = if (active) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.background,
                            border = if (active) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.width(76.dp).semantics { selected = active }) {
                            Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BotRowAvatar(row, decoder, compact = true)
                                Text(row.profile.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium.boardReduced(),
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    item {
                        TextButton(onClick = { showPicker = true }, enabled = actionsAllowed, modifier = Modifier.defaultMinSize(minWidth = 76.dp, minHeight = 80.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(com.hermes.bot.navigation.BottomNavIcons.Bots, contentDescription = null)
                                Text("All bots", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                favourites.error?.let {
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { viewModel?.retry() }, enabled = !selecting && !favourites.saving) { Text("Retry") }
                    }
                }
                if (favourites.loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                if (selectedRow != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BotRowAvatar(selectedRow, decoder)
                        Column(Modifier.weight(1f)) {
                            Text(selectedRow.profile.displayName, style = MaterialTheme.typography.titleMedium.boardReduced(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(conversationCount?.let { "$it conversations" } ?: "Conversations", style = MaterialTheme.typography.bodySmall.boardReduced(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onOpenScreen(selectedRow) }, enabled = actionsAllowed,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(atelierSelectionColor())) {
                            Icon(botScreenIcon, contentDescription = "Open screen",
                                tint = if (actionsAllowed) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                        }
                        IconButton(onClick = onRefreshChannel, enabled = actionsAllowed && conversationCount != null, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh conversations")
                        }
                        FavouriteButton(selectedRow.profile.displayName, selectedRow.profile.name in favourites.names, favouritesAllowed) {
                            viewModel?.setFavourite(selectedRow.profile.name, selectedRow.profile.name !in favourites.names)
                        }
                    }
                    val selectedScreen = screen.takeIf { it.profile == selectedRow.profile.name }
                    val screenStatus = selectedScreen?.status
                    val screenLabel = when {
                        selectedScreen == null || selectedScreen.loading -> "Checking screen…"
                        screenStatus == null -> "Screen status unknown"
                        !screenStatus.supported || !screenStatus.installed -> "Screen unavailable"
                        !screenStatus.running -> "Screen stopped"
                        screenStatus.lease.holder == ScreenLease.Holder.HUMAN -> "Screen running · Human controls"
                        else -> "Screen running"
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(screenLabel, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            selectedScreen?.message?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error) }
                        }
                        if (screenStatus?.supported == true && screenStatus.installed && screenStatus.running &&
                            screenStatus.lease.holder == ScreenLease.Holder.BOT && selectedScreen.loading == false) {
                            TextButton(onClick = { showStopConfirmation = true },
                                enabled = actionsAllowed && !selectedScreen.stopping) {
                                Text(if (selectedScreen.stopping) "Stopping…" else "Stop screen")
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { channelContent() }
                } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (channel == null && favourites.loading) CircularProgressIndicator()
                        else Text(when {
                            channel != null -> "Selected bot is unavailable. Choose another bot."
                            favourites.names.isEmpty() -> "Choose a bot to see its conversations"
                            else -> "Select a favourite to see its conversations"
                        })
                        TextButton(onClick = { showPicker = true }, enabled = actionsAllowed) { Text("All bots") }
                    }
                }
            } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (admitted) {
                    BotsUiState.Loading -> CircularProgressIndicator()
                    BotsUiState.Empty -> Text(stringResource(R.string.bots_empty))
                    BotsUiState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.bots_error))
                        TextButton(onClick = { viewModel?.retry() }) { Text(stringResource(R.string.bots_retry)) }
                    }
                    else -> Text(stringResource(R.string.bots_disconnected))
                }
            }
        }
        if (showStopConfirmation) AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text("Stop bot screen?") },
            text = { Text("This closes the desktop and browser on the Hermes host. Ongoing bot screen work may be interrupted.") },
            confirmButton = { TextButton(onClick = { showStopConfirmation = false; viewModel?.stopScreen() }) { Text("Stop screen") } },
            dismissButton = { TextButton(onClick = { showStopConfirmation = false }) { Text("Cancel") } }
        )
        if (showPicker) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showPicker = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Text("All bots", Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
            Text("Star a bot to keep it in your favourites.", Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            favourites.error?.let { Text(it, Modifier.padding(horizontal = 22.dp), color = MaterialTheme.colorScheme.error) }
            if (rows.isEmpty()) Text("Bots are unavailable. Close this picker and retry.", Modifier.padding(22.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding()) {
                items(rows, key = { it.profile.name }) { row ->
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { BotListRow(row, decoder, actionsAllowed) { showPicker = false; onSelect(row) } }
                        FavouriteButton(row.profile.displayName, row.profile.name in favourites.names, favouritesAllowed) {
                            viewModel?.setFavourite(row.profile.name, row.profile.name !in favourites.names)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavouriteButton(name: String, favourite: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(if (favourite) Icons.Default.Star else Icons.Outlined.Star,
            contentDescription = if (favourite) "Remove $name from favourites" else "Add $name to favourites",
            tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BotRowAvatar(row: BotRow, decoder: suspend (ByteArray) -> ImageBitmap?, compact: Boolean = false) {
    val image by produceState<ImageBitmap?>(null, row.avatar, decoder) { value = decodeBotAvatar(row.avatar?.bytes, decoder) }
    Box((if (compact) Modifier.size(30.dp) else Modifier.size(40.dp)).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(botInitial(row.profile.displayName), color = MaterialTheme.colorScheme.onSurface,
            style = if (compact) MaterialTheme.typography.bodyLarge.boardReduced() else MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BotListRow(row: BotRow, decoder: suspend (ByteArray) -> ImageBitmap?, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BotRowAvatar(row, decoder)
        Text(row.profile.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
    }
}
