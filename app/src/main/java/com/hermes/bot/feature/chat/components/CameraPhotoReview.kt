package com.hermes.bot.feature.chat.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.bot.feature.chat.CameraPhase
import com.hermes.bot.feature.chat.CameraUiState

@Composable
fun CameraPhotoReview(state: CameraUiState, onCancel: () -> Unit, onRetake: () -> Unit, onUse: () -> Unit) {
    if (state.phase != CameraPhase.REVIEW && state.phase != CameraPhase.PREPARING) return
    // The store already bounded and oriented this thumbnail; no file access belongs in Compose.
    val bitmap = remember(state.preview) { state.preview?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel photo") }
                    Text("Review photo", style = MaterialTheme.typography.titleMedium)
                }
                Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Captured photo",
                            modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                        else CircularProgressIndicator()
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onRetake, enabled = state.phase == CameraPhase.REVIEW,
                        modifier = Modifier.weight(1f)) { Text("Retake") }
                    Button(onClick = onUse, enabled = state.phase == CameraPhase.REVIEW && bitmap != null,
                        modifier = Modifier.weight(1f)) { Text("Use photo") }
                }
            }
        }
    }
}
