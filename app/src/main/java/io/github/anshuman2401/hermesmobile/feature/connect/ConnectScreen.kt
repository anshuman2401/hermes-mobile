package io.github.anshuman2401.hermesmobile.feature.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error as semanticError
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anshuman2401.hermesmobile.design.HermesShapeTokens
import io.github.anshuman2401.hermesmobile.design.HermesTheme
import io.github.anshuman2401.hermesmobile.design.RelayOrbitIcon

/**
 * CHG-001: Connect screen.
 * Implements the structured HTTP-first host connection form.
 * No customer-visible debug controls, state chips, or simulation labels.
 * Seven-state coverage is maintained through ConnectUiState injection in tests.
 */
@Composable
fun ConnectScreen(
    uiState: ConnectUiState,
    onHostChanged: (String) -> Unit,
    onServerNameChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onTestConnection: () -> Unit,
    onAuthenticate: () -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onOpenSessions: () -> Unit,
    onRetry: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .semantics {
                // First launch keeps its non-color state description without
                // repeating the introduction in a second setup card.
                stateDescription = "Current state: ${uiState.state.label}. ${uiState.state.description}"
            },
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // The connected shell controls occupy the upper-right 48dp row.
        // Reserve that row so the brand never sits beneath those controls.
        if (uiState.state == ConnectState.CONNECTED_COMPATIBLE) {
            Spacer(Modifier.height(40.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RelayOrbitIcon(size = 38.dp, contentDescriptionText = null)
            Text(
                text = "Hermes Mobile",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Your host.\nYour Hermes.",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = "Connect to your private Hermes host.\nAgent execution stays on your host.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (uiState.state != ConnectState.FIRST_LAUNCH) {
            StateSignalBanner(
                state = uiState.state,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Show form only when in form-entry states
        val showForm = uiState.state == ConnectState.FIRST_LAUNCH ||
            uiState.state == ConnectState.HOST_UNREACHABLE ||
            uiState.state == ConnectState.ORIGIN_POLICY_REJECTED ||
            uiState.state == ConnectState.PROTOCOL_INCOMPATIBLE

        if (showForm) {
            ConnectFormSection(
                uiState = uiState,
                onServerNameChanged = onServerNameChanged,
                onHostChanged = onHostChanged,
                onPortChanged = onPortChanged,
                onTestConnection = onTestConnection
            )
        }

        // State-specific content
        when (uiState.state) {
            ConnectState.FIRST_LAUNCH -> {
                // Form is shown above; no additional card needed
            }

            ConnectState.PREFLIGHT_LOADING -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp).semantics { contentDescription = "Connecting in progress" },
                            // CHG-008 repair round 1 (P1-A): foreground indicator,
                            // not a fill. #5352ED is only 3.07:1 on this card —
                            // it clears the 3:1 floor but with no margin.
                            color = HermesTheme.extendedColors.primaryText
                        )
                        Text(
                            text = "Testing host reachability and compatibility...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            ConnectState.AUTH_REQUIRED -> {
                LoginFormCard(
                    uiState = uiState,
                    onUsernameChanged = onUsernameChanged,
                    onPasswordChanged = onPasswordChanged,
                    onLogin = onLogin
                )
            }

            ConnectState.CONNECTED_COMPATIBLE -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (uiState.capabilitySummary.isNotBlank()) {
                            Text(
                                text = uiState.capabilitySummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // HTTP signal: non-color, accessible
                        Text(
                            text = "HTTP · Not encrypted by HTTP",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onOpenSessions,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("Open Sessions")
                        }
                    }
                }
            }

            ConnectState.HOST_UNREACHABLE -> {
                // Form is shown above; show error card + retry
                if (uiState.errorMessage != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Host Unreachable",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Check that your Hermes host is running and reachable over your private network or Tailscale.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onRetry,
                                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
            }

            ConnectState.ORIGIN_POLICY_REJECTED -> {
                if (uiState.errorMessage != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Connection Policy Rejected",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "The connection was rejected. Check the address, or that the Android network policy permits this request. No trust bypass is available.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Security Policy: No bypass option is provided.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            OutlinedButton(
                                onClick = { /* form fields remain editable */ },
                                // P-8: M3 1.4.0's OutlinedButton defaults its label to
                                // onSurfaceVariant; on this (unwired, M3-baseline)
                                // errorContainer card that is 2.95:1 dark — the same
                                // class of failure as PR #62's banner Dismiss.
                                // onErrorContainer is the matching on* role:
                                // 7.17:1 in BOTH themes.
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Edit address")
                            }
                        }
                    }
                }
            }

            ConnectState.PROTOCOL_INCOMPATIBLE -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Protocol Incompatible",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Host protocol version is incompatible. Required: hermes-gateway-v1.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onToggleDiagnostics,
                                // P-8: same surfaceContainer default-label defect —
                                // onSurfaceVariant 5.50:1 dark. onSurface:
                                // 17.00:1 dark / 14.63:1 light.
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text(if (uiState.showDiagnostics) "Hide diagnostics" else "Diagnostics")
                            }
                        }
                        if (uiState.showDiagnostics) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(color = HermesTheme.extendedColors.codeSurface, shape = RoundedCornerShape(6.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = uiState.diagnosticDetails,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = HermesTheme.extendedColors.codeOnSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Structured connection form with fixed HTTP transport, local server name, host, port, warning, and submit.
 */
@Composable
private fun ConnectFormSection(
    uiState: ConnectUiState,
    onServerNameChanged: (String) -> Unit,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onTestConnection: () -> Unit
) {
    val isLoading = uiState.state == ConnectState.PREFLIGHT_LOADING
    val fontScale = LocalDensity.current.fontScale

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ConnectTextField(
            value = uiState.hostDraft,
            onValueChange = onHostChanged,
            label = "Hostname or IP address",
            description = "Hostname or IP address field",
            placeholder = "hermes.example",
            helper = "Enter a hostname or IP address without http://, a port, or a path.",
            error = uiState.hostError,
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None
            )
        )

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stack = ConnectLayoutPolicy.stackSecondaryFields(maxWidth.value, fontScale)
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ConnectPortField(uiState, onPortChanged, !isLoading)
                    ConnectServerNameField(uiState, onServerNameChanged, !isLoading)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ConnectPortField(uiState, onPortChanged, !isLoading, Modifier.weight(0.4f))
                    ConnectServerNameField(uiState, onServerNameChanged, !isLoading, Modifier.weight(0.6f))
                }
            }
        }

        // Required disclosure is persistent and precedes the network action.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "HTTP security notice: HTTP is not encrypted by itself. Use it only over a trusted private network or an encrypted tunnel such as Tailscale."
            }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Private network only",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "HTTP is not encrypted by itself. Use it only over a trusted private network or an encrypted tunnel such as Tailscale.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Button(
            onClick = onTestConnection,
            enabled = !isLoading,
            shape = RoundedCornerShape(17.dp),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
        ) {
            Text("Connect", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(
            text = "Server name is a local label only; this does not change the address.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ConnectPortField(
    uiState: ConnectUiState,
    onPortChanged: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    ConnectTextField(
        value = uiState.portDraft,
        onValueChange = onPortChanged,
        label = "Port",
        description = "Port field",
        error = uiState.portError,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrect = false),
        modifier = modifier
    )
}

@Composable
private fun ConnectServerNameField(
    uiState: ConnectUiState,
    onServerNameChanged: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    ConnectTextField(
        value = uiState.serverNameDraft,
        onValueChange = onServerNameChanged,
        label = "Server name (optional)",
        description = "Server name optional field, local label only",
        enabled = enabled,
        modifier = modifier
    )
}

/** External labels stay visible while the real editable field carries error semantics. */
@Composable
private fun ConnectTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            enabled = enabled,
            isError = error != null,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceContainer,
                unfocusedContainerColor = colors.surfaceContainer,
                errorContainerColor = colors.surfaceContainer,
                focusedTextColor = colors.onSurface,
                unfocusedTextColor = colors.onSurface,
                errorTextColor = colors.onSurface,
                focusedPlaceholderColor = colors.onSurfaceVariant,
                unfocusedPlaceholderColor = colors.onSurfaceVariant,
                errorPlaceholderColor = colors.onSurfaceVariant,
                // Foreground roles, rather than decorative low-contrast outlines.
                focusedIndicatorColor = HermesTheme.extendedColors.primaryText,
                unfocusedIndicatorColor = colors.onSurfaceVariant,
                errorIndicatorColor = colors.error,
                cursorColor = HermesTheme.extendedColors.primaryText,
                errorCursorColor = colors.error
            ),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).semantics {
                contentDescription = description
                if (error != null) semanticError(error)
            }
        )
        val support = error ?: helper
        if (support != null) {
            Text(
                text = support,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) colors.error else colors.onSurfaceVariant,
                modifier = if (error != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
            )
        }
    }
}

/**
 * Login form card shown when the host requires authentication.
 * Contains username/password fields, error display, and sign-in button.
 */
@Composable
private fun LoginFormCard(
    uiState: ConnectUiState,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Sign In",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (uiState.providerLabel.isNotBlank()) {
                Text(
                    text = "Host: ${uiState.providerLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Enter your credentials to authenticate with the Hermes host.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Username field
            OutlinedTextField(
                value = uiState.usernameDraft,
                onValueChange = onUsernameChanged,
                label = { Text("Username") },
                singleLine = true,
                enabled = !uiState.loginLoading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrect = false),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .semantics {
                        contentDescription = "Username field"
                    }
            )

            // Password field
            OutlinedTextField(
                value = uiState.passwordDraft,
                onValueChange = onPasswordChanged,
                label = { Text("Password") },
                singleLine = true,
                enabled = !uiState.loginLoading,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) PasswordVisibilityIcons.Visible else PasswordVisibilityIcons.Hidden,
                            contentDescription = if (passwordVisible) "Hide password; password visible" else "Show password; password concealed",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .semantics {
                        contentDescription = "Password field"
                    }
            )

            // Login error display
            if (uiState.loginError != null) {
                Text(
                    text = uiState.loginError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Login error: ${uiState.loginError}"
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Sign in button
            Button(
                onClick = onLogin,
                enabled = !uiState.loginLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
            ) {
                if (uiState.loginLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Signing in...")
                } else {
                    Text("Sign In")
                }
            }
        }
    }
}

/**
 * Visual non-color state indicator banner.
 * Accessible via liveRegion semantics.
 */
private data class StateSignal(
    val icon: ImageVector,
    val badgeText: String
)

@Composable
fun StateSignalBanner(
    state: ConnectState,
    modifier: Modifier = Modifier
) {
    val signal = when (state) {
        ConnectState.FIRST_LAUNCH -> StateSignal(Icons.Default.Info, "SETUP REQUIRED")
        ConnectState.PREFLIGHT_LOADING -> StateSignal(Icons.Default.Refresh, "TESTING CONNECTION")
        ConnectState.AUTH_REQUIRED -> StateSignal(Icons.Default.Lock, "AUTHENTICATION REQUIRED")
        ConnectState.CONNECTED_COMPATIBLE -> StateSignal(Icons.Default.Check, "CONNECTED")
        ConnectState.HOST_UNREACHABLE -> StateSignal(Icons.Default.Warning, "HOST UNREACHABLE")
        ConnectState.ORIGIN_POLICY_REJECTED -> StateSignal(Icons.Default.Warning, "CONNECTION REJECTED")
        ConnectState.PROTOCOL_INCOMPATIBLE -> StateSignal(Icons.Default.Warning, "PROTOCOL INCOMPATIBLE")
    }

    val iconTint = when (state) {
        // CHG-008 repair round 1 (P1-A): this tint paints BOTH the state icon
        // (inside a 15%-alpha circle of its own colour, where #5352ED measures
        // 2.71:1 — below the 3:1 graphical-object floor) AND the labelSmall
        // badge text next to it (3.07:1 on the card — below the 4.5:1 text
        // floor). Both are foreground roles, so both read primaryText.
        ConnectState.FIRST_LAUNCH -> HermesTheme.extendedColors.primaryText
        ConnectState.PREFLIGHT_LOADING -> HermesTheme.extendedColors.primaryText
        ConnectState.AUTH_REQUIRED -> MaterialTheme.colorScheme.tertiary
        ConnectState.CONNECTED_COMPATIBLE -> MaterialTheme.colorScheme.secondary
        ConnectState.HOST_UNREACHABLE,
        ConnectState.ORIGIN_POLICY_REJECTED,
        ConnectState.PROTOCOL_INCOMPATIBLE -> MaterialTheme.colorScheme.error
    }

    Surface(
        shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .border(width = 1.dp, color = HermesTheme.extendedColors.glassOutline, shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall))
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Current state: ${state.label}. ${state.description}"
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = signal.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = signal.badgeText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = iconTint)
                Text(text = state.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
