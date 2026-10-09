package com.hermes.bot.feature.connect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.bot.design.RelayOrbitIcon

/** Local encrypted-state loading only; no credential or host form is composed. */
@Composable
fun StartupScreen(storageUnavailable: Boolean, onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
        ) {
            RelayOrbitIcon(size = 48.dp, contentDescriptionText = null)
            Text("Hermes Mobile", style = MaterialTheme.typography.titleLarge)
            if (storageUnavailable) {
                Text("Saved connection could not be loaded. Try again.")
                OutlinedButton(onClick = onRetry, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Retry") }
            } else {
                CircularProgressIndicator()
                Text("Loading saved connection…")
            }
        }
    }
}
