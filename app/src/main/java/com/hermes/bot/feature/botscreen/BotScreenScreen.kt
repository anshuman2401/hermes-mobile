package com.hermes.bot.feature.botscreen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.bot.data.bots.BotTarget
import com.hermes.bot.data.botscreen.ScreenLease
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.AtelierTheme
import kotlin.math.min

@Composable
fun BotScreenScreen(
    profile: String,
    displayName: String,
    target: BotTarget,
    viewModel: BotScreenViewModel?,
    theme: AppThemePreference,
    onBack: () -> Unit
) {
    AtelierTheme(theme) {
        val lifecycle = LocalLifecycleOwner.current
        var keyboardVisible by remember { mutableStateOf(false) }
        var typed by remember { mutableStateOf("") }
        DisposableEffect(viewModel, target, profile, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel?.enter(target, profile)
                    Lifecycle.Event.ON_STOP -> { typed = ""; keyboardVisible = false; viewModel?.leave() }
                    else -> Unit
                }
            }
            lifecycle.lifecycle.addObserver(observer)
            onDispose { lifecycle.lifecycle.removeObserver(observer); typed = ""; viewModel?.leave() }
        }
        val state = viewModel?.state?.collectAsStateWithLifecycle()?.value
        val status = state?.status
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoomChange, panChange, _ ->
            zoom = (zoom * zoomChange).coerceIn(1f, 4f)
            pan = if (zoom <= 1f) Offset.Zero else pan + panChange
        }
        BackHandler {
            if (keyboardVisible) { keyboardVisible = false; typed = "" }
            else onBack()
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Bot screen", style = MaterialTheme.typography.titleMedium)
                    Text(displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { viewModel?.reconnect() }, enabled = status?.running == true) { Text("Reconnect") }
            }
            if (state?.loading != false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            if (status == null) {
                ScreenMessage(state.error ?: "Bot Screen unavailable", "Retry", { viewModel?.reconnect() })
                return@Column
            }
            if (!status.supported) {
                ScreenMessage("Bot Screen needs a supported Linux gateway.", null, null)
                return@Column
            }
            if (!status.installed) {
                ScreenMessage("Screen packages are missing on the Hermes host. Install them there, then retry.", "Retry", { viewModel?.reconnect() })
                return@Column
            }
            if (!status.running) {
                ScreenMessage(status.blocker ?: "The bot's screen is stopped.", if (status.blocker == null) "Start screen" else "Retry",
                    { if (status.blocker == null) viewModel?.start() else viewModel?.reconnect() }, state.busy)
                return@Column
            }
            val holder = when {
                state.holding -> "You control"
                status.lease.holder == ScreenLease.Holder.HUMAN -> "Another viewer controls"
                else -> "Bot controls"
            }
            Text(holder, Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
            val frame = state.frame
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black)
                .transformable(transform)) {
                if (frame != null) {
                    val bitmap = remember(frame) {
                        android.graphics.Bitmap.createBitmap(frame.pixels, frame.width, frame.height,
                            android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
                    }
                    val density = LocalDensity.current
                    val w = with(density) { maxWidth.toPx() }
                    val h = with(density) { maxHeight.toPx() }
                    val fit = min(w / frame.width, h / frame.height)
                    val drawnW = frame.width * fit * zoom
                    val drawnH = frame.height * fit * zoom
                    val left = w / 2 + pan.x - drawnW / 2
                    val top = h / 2 + pan.y - drawnH / 2
                    Image(bitmap, contentDescription = "Live bot screen", modifier = Modifier.fillMaxSize()
                        .pointerInput(frame.width, frame.height, zoom, pan, state.holding) {
                            detectTapGestures(onTap = { point ->
                                if (state.holding) {
                                    val x = ((point.x - left) / drawnW * frame.width).toInt()
                                    val y = ((point.y - top) / drawnH * frame.height).toInt()
                                    if (x in 0 until frame.width && y in 0 until frame.height) {
                                        viewModel?.pointer(x, y, 1); viewModel?.pointer(x, y, 0)
                                    }
                                }
                            }, onLongPress = { point ->
                                if (state.holding) {
                                    val x = ((point.x - left) / drawnW * frame.width).toInt()
                                    val y = ((point.y - top) / drawnH * frame.height).toInt()
                                    if (x in 0 until frame.width && y in 0 until frame.height) {
                                        viewModel?.pointer(x, y, 4); viewModel?.pointer(x, y, 0)
                                    }
                                }
                            })
                        }
                        .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y),
                        contentScale = ContentScale.Fit)
                } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "Connecting to screen…", color = androidx.compose.ui.graphics.Color.White)
                }
            }
            if (state.error != null && frame != null) Text(state.error, Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (state.holding) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { keyboardVisible = !keyboardVisible }) { Text("Keyboard") }
                    TextButton(onClick = { viewModel?.scroll(false) }) { Text("Scroll ↑") }
                    TextButton(onClick = { viewModel?.scroll(true) }) { Text("Scroll ↓") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { viewModel?.key(0xff0d) }) { Text("Enter") }
                    TextButton(onClick = { viewModel?.key(0xff08) }) { Text("Backspace") }
                    TextButton(onClick = { viewModel?.key(0xff09) }) { Text("Tab") }
                }
                if (keyboardVisible) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = typed, onValueChange = { typed = it.take(1024) }, label = { Text("Type into screen") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel?.type(typed); typed = "" }),
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel?.type(typed); typed = "" }) { Text("Type") }
                }
            } else Text("Pinch to zoom · Drag to pan", Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { if (state.holding) viewModel?.handBack() else viewModel?.takeOver() },
                enabled = !state.busy && state.attached,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)) { Text(if (state.holding) "Hand back" else "Take over") }
        }
    }
}

@Composable
private fun ColumnScope.ScreenMessage(message: String, action: String?, onAction: (() -> Unit)?, busy: Boolean = false) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        if (action != null && onAction != null) Button(onClick = onAction, enabled = !busy, modifier = Modifier.padding(top = 16.dp)) {
            Text(action)
        }
    }
}
