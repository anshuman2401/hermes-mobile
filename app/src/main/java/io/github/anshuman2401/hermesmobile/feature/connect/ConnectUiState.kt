package io.github.anshuman2401.hermesmobile.feature.connect

import io.github.anshuman2401.hermesmobile.data.origin.ConnectProtocol

/**
 * Seven deterministic Connect states for CHG-001.
 * Uses the CHG-001 origin-policy failure state while preserving seven automated states.
 */
enum class ConnectState(
    val label: String,
    val categoryName: String,
    val description: String
) {
    FIRST_LAUNCH(
        label = "First Launch",
        categoryName = "Setup",
        description = "Enter your private Hermes host address. Hermes execution remains strictly on your host."
    ),
    PREFLIGHT_LOADING(
        label = "Testing Connection",
        categoryName = "Testing",
        description = "Testing host reachability and gateway protocol version..."
    ),
    AUTH_REQUIRED(
        label = "Authentication Required",
        categoryName = "Authentication",
        description = "Host reachable and compatible. Authentication is required to continue."
    ),
    CONNECTED_COMPATIBLE(
        label = "Connected",
        categoryName = "Connected",
        description = "Connected and compatible with hermes-gateway-v1."
    ),
    HOST_UNREACHABLE(
        label = "Host Unreachable",
        categoryName = "Connection Error",
        description = "Unable to reach the host. Check your private network or Tailscale connection."
    ),
    ORIGIN_POLICY_REJECTED(
        label = "Origin Policy Rejected",
        categoryName = "Security Error",
        description = "The connection was rejected: invalid address, blocked redirect, or Android network policy conflict. No trust bypass is available."
    ),
    PROTOCOL_INCOMPATIBLE(
        label = "Protocol Incompatible",
        categoryName = "Protocol Error",
        description = "Installed app requires hermes-gateway-v1. Host protocol is incompatible."
    );

    val isErrorState: Boolean
        get() = this in listOf(HOST_UNREACHABLE, ORIGIN_POLICY_REJECTED, PROTOCOL_INCOMPATIBLE)
}

/**
 * Immutable presentation model for the Connect screen (CHG-001).
 * Uses structured fields (protocol, serverName, host, port) instead of opaque origin string.
 * Strictly local state; contains no credentials, passwords, or tokens.
 */
enum class StartupPresentation { LOADING, SETUP, SESSIONS, SIGN_IN, STORAGE_UNAVAILABLE }

data class ConnectUiState(
    val startupPresentation: StartupPresentation = StartupPresentation.SETUP,
    val startupRoutePending: Boolean = false,
    val localAccessRevision: Long = 0,
    // Structured form fields
    val selectedProtocol: ConnectProtocol = ConnectProtocol.HTTP,
    val serverNameDraft: String = "Hermes",
    val hostDraft: String = "",
    val portDraft: String = "9119",
    // Per-field errors
    val hostError: String? = null,
    val portError: String? = null,
    // Connection state
    val state: ConnectState = ConnectState.FIRST_LAUNCH,
    val providerLabel: String = "",
    val capabilitySummary: String = "",
    val errorMessage: String? = null,
    val showDiagnostics: Boolean = false,
    val diagnosticDetails: String = "App requirement: hermes-gateway-v1 (protocol v1)\nObserved host: unsupported version\nNegotiated features: NONE\nStatus: Protocol mismatch",
    // Login form fields
    val usernameDraft: String = "",
    val passwordDraft: String = "",
    val loginLoading: Boolean = false,
    val loginError: String? = null
) {
    // Legacy compatibility: synthesize origin string for display (non-secret, non-credential)
    // Server name is never part of this; host+port only.
    val origin: String get() = if (hostDraft.isNotBlank() && portDraft.isNotBlank()) {
        val bracketedHost = if (hostDraft.contains(':')) "[$hostDraft]" else hostDraft
        "http://$bracketedHost:$portDraft"
    } else ""
}

/**
 * Immutable UI state for the profile directory and active profile switcher.
 */
data class ProfilesUiState(
    val profiles: List<io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile> = emptyList(),
    val activeProfileId: String? = null
)
