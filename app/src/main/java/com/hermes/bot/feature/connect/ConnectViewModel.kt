package com.hermes.bot.feature.connect

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.HostStatus
import com.hermes.bot.data.diagnostics.CatalogEvent
import com.hermes.bot.data.diagnostics.DiagnosticField
import com.hermes.bot.data.diagnostics.DiagnosticMetadata
import com.hermes.bot.data.diagnostics.Diagnostics
import com.hermes.bot.data.diagnostics.NoOpDiagnostics
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayTicket
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.ConnectionProfileGenerationGate
import com.hermes.bot.data.local.ProfileCommitResult
import com.hermes.bot.data.local.ProfileReadResult
import com.hermes.bot.data.local.SignOutCacheChoice
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.origin.ConnectProtocol
import com.hermes.bot.data.origin.StructuredEndpointValidator
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.hermes.bot.navigation.ProfileSwitchDecision
import com.hermes.bot.navigation.ProfileSwitchPolicy
import kotlinx.coroutines.Job
import java.util.UUID

/**
 * Presentation state holder for the structured CHG-001 Connect form.
 *
 * Network work starts only after [StructuredEndpointValidator] produces a canonical HTTP
 * origin. Server name remains local metadata and never participates in an endpoint.
 */
class ConnectViewModel(
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val authRepository: AuthRepository? = null,
    private val gatewayAdapter: GatewayProtocolAdapter? = null,
    private val vaultStorage: VaultStorage? = null,
    private val diagnostics: Diagnostics = NoOpDiagnostics,
    private val onGatewayConnected: (CanonicalOrigin) -> Unit = {},
    private val onGatewayDisconnected: () -> Unit = {},
    private val recovery: com.hermes.bot.data.auth.ConnectionRecoveryCoordinator? = null
) : ViewModel() {

    companion object {
        const val KEY_HOST = "hermes_connect_host"
        const val KEY_PORT = "hermes_connect_port"
        const val KEY_SERVER_NAME = "hermes_connect_server_name"
        const val KEY_STATE = "hermes_connect_state"
        const val MAX_SERVER_NAME_LENGTH = 64

        private const val DEFAULT_SERVER_NAME = "Hermes"
        private const val SERVER_NAME_ERROR_PREFIX = "Server name"
        private val BIDI_CONTROL_REGEX = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C\\uFEFF]")
    }

    private val initialHost: String = savedStateHandle[KEY_HOST] ?: ""
    private val initialPort: String = savedStateHandle[KEY_PORT] ?: "9119"
    private val restoredSavedStateServerName: String = savedStateHandle[KEY_SERVER_NAME] ?: DEFAULT_SERVER_NAME
    private val initialServerNameError: String? = validateServerName(restoredSavedStateServerName)
    private val initialServerName: String = restoredSavedStateServerName
        .takeIf { initialServerNameError == null }
        ?: DEFAULT_SERVER_NAME
    // Saved presentation is not evidence of a live connection after process death.
    private val initialState = ConnectState.FIRST_LAUNCH

    private val _uiState = MutableStateFlow(
        ConnectUiState(
            startupRoutePending = vaultStorage != null,
            startupPresentation = if (vaultStorage != null) StartupPresentation.LOADING else StartupPresentation.SETUP,
            selectedProtocol = ConnectProtocol.HTTP,
            serverNameDraft = initialServerName,
            hostDraft = initialHost,
            portDraft = initialPort,
            state = initialState,
            errorMessage = initialServerNameError
        )
    )
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    private val _profilesUiState = MutableStateFlow(ProfilesUiState())
    val profilesUiState: StateFlow<ProfilesUiState> = _profilesUiState.asStateFlow()

    var isAddMode: Boolean = false

    fun enterAddProfileMode() {
        recovery?.invalidate()
        profileGenerationGate.invalidate()
        profileLoadJob?.cancel()
        testJob?.cancel()
        authJob?.cancel()
        loginJob?.cancel()
        isAddMode = true
        ++draftRevision
        explicitPortEdit = false
        serverNameEdited = false
        endpointPreferenceJob?.cancel()
        connectionSubmitJob?.cancel()
        resetToFirstLaunch()
        _uiState.update {
            it.copy(
                serverNameDraft = DEFAULT_SERVER_NAME,
                hostDraft = "",
                portDraft = "9119",
                hostError = null,
                portError = null,
                usernameDraft = "",
                passwordDraft = "",
                loginError = null,
                errorMessage = null
            )
        }
    }

    suspend fun reloadProfiles() {
        val vault = vaultStorage ?: return
        try {
            val profiles = vault.listConnectionProfiles()
            val activeId = vault.getActiveProfileId()
            _profilesUiState.value = ProfilesUiState(
                profiles = profiles,
                activeProfileId = activeId
            )
        } catch (_: Exception) {}
    }

    fun refreshProfiles(): Job = viewModelScope.launch {
        reloadProfiles()
    }

    private var lastAttemptedOrigin: CanonicalOrigin? = null
    private var loginJob: Job? = null
    private var recoveryOperationOwned = false
    private var testJob: Job? = null
    private var authJob: Job? = null
    private var profileLoadJob: Job? = null
    val profileGenerationGate = ConnectionProfileGenerationGate()
    @Volatile private var gatewayStatusGeneration: Long? = null
    private var droppedGatewayGeneration: Long? = null
    private var endpointPreferenceJob: Job? = null
    private var draftRevision = 0L
    private var explicitPortEdit = false
    private var connectionSubmitJob: Job? = null
    private var serverNameEdited = false

    init {
        if (initialServerNameError != null) {
            savedStateHandle[KEY_SERVER_NAME] = initialServerName
        }
        if (recovery != null) viewModelScope.launch {
            authRepository?.localAccessRevision?.collect { revision ->
                _uiState.update { it.copy(localAccessRevision = revision) }
            }
        }
        if (recovery != null) viewModelScope.launch {
            recovery.phase.collect { phase ->
                if (recoveryOperationOwned || testJob?.isActive == true || authJob?.isActive == true || loginJob?.isActive == true ||
                    profileLoadJob?.isActive == true || _uiState.value.startupPresentation != StartupPresentation.SESSIONS) return@collect
                when (phase) {
                    com.hermes.bot.data.auth.RecoveryPhase.CONNECTING -> setState(ConnectState.PREFLIGHT_LOADING)
                    com.hermes.bot.data.auth.RecoveryPhase.READY -> setState(ConnectState.CONNECTED_COMPATIBLE)
                    com.hermes.bot.data.auth.RecoveryPhase.SIGN_IN_REQUIRED -> setState(ConnectState.AUTH_REQUIRED)
                    com.hermes.bot.data.auth.RecoveryPhase.OFFLINE -> setState(ConnectState.HOST_UNREACHABLE)
                    com.hermes.bot.data.auth.RecoveryPhase.POLICY_FAILURE -> setState(ConnectState.ORIGIN_POLICY_REJECTED)
                    else -> Unit
                }
            }
        }
        if (gatewayAdapter != null) {
            viewModelScope.launch {
                try {
                    gatewayAdapter.connectionStatus.collect { status ->
                        val generation = gatewayStatusGeneration ?: return@collect
                        if (!profileGenerationGate.isCurrent(generation)) return@collect
                        when (status) {
                            ConnectionStatus.AUTHENTICATION_REQUIRED -> {
                                if (_uiState.value.state == ConnectState.CONNECTED_COMPATIBLE ||
                                    (droppedGatewayGeneration == generation && _uiState.value.state == ConnectState.HOST_UNREACHABLE)) {
                                    droppedGatewayGeneration = null
                                    if (recovery == null) setState(ConnectState.AUTH_REQUIRED)
                                }
                            }
                            ConnectionStatus.OFFLINE,
                            ConnectionStatus.FAILED -> {
                                diagnostics.emit(CatalogEvent.GATEWAY_DISCONNECTED)
                                if (_uiState.value.state == ConnectState.CONNECTED_COMPATIBLE) {
                                    droppedGatewayGeneration = generation
                                    setState(ConnectState.HOST_UNREACHABLE)
                                }
                            }
                            ConnectionStatus.CONNECTED -> {
                                // Only recover an observed steady-state drop. A new
                                // preflight/login operation still owns its transitions.
                                if (droppedGatewayGeneration == generation &&
                                    _uiState.value.state == ConnectState.HOST_UNREACHABLE &&
                                    gatewayAdapter.terminalFailure.value?.isTerminal != true
                                ) {
                                    droppedGatewayGeneration = null
                                    setState(ConnectState.CONNECTED_COMPATIBLE)
                                    _uiState.update { it.copy(errorMessage = null) }
                                }
                            }
                            ConnectionStatus.CONNECTING -> Unit
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    diagnostics.emit(CatalogEvent.GATEWAY_DISCONNECTED)
                    if (_uiState.value.state == ConnectState.CONNECTED_COMPATIBLE) {
                        setState(ConnectState.HOST_UNREACHABLE)
                    }
                }
            }
        }
        if (vaultStorage != null) reloadSavedConnection()
    }

    fun onStartupPresented() { _uiState.update { it.copy(startupRoutePending = false) } }

    fun reloadSavedConnection() {
        ++draftRevision
        endpointPreferenceJob?.cancel()
        connectionSubmitJob?.cancel()
        profileLoadJob?.cancel()
        profileGenerationGate.invalidate()
        val generation = profileGenerationGate.currentGeneration()
        _uiState.update { it.copy(startupPresentation = StartupPresentation.LOADING, state = ConnectState.FIRST_LAUNCH) }
        profileLoadJob = viewModelScope.launch {
            try {
                reloadProfiles()
                loadStoredProfile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!isAddMode && _uiState.value.startupPresentation == StartupPresentation.LOADING &&
                    profileGenerationGate.isCurrent(generation)) _uiState.update {
                    it.copy(startupPresentation = StartupPresentation.STORAGE_UNAVAILABLE,
                        errorMessage = "Saved connection could not be loaded. Try again.")
                }
            }
        }
    }

    fun updateServerName(newName: String) {
        serverNameEdited = true
        val validationError = validateServerName(newName)
        if (validationError != null) {
            _uiState.update { it.copy(errorMessage = validationError) }
            return
        }
        savedStateHandle[KEY_SERVER_NAME] = newName
        _uiState.update {
            it.copy(
                serverNameDraft = newName,
                errorMessage = it.errorMessage?.takeUnless { error -> error.startsWith(SERVER_NAME_ERROR_PREFIX) }
            )
        }
    }


    private fun cancelDraftConnection() {
        if (profileLoadJob?.isActive != true && testJob?.isActive != true && loginJob?.isActive != true &&
            authJob?.isActive != true && connectionSubmitJob?.isActive != true) return
        recovery?.invalidate()
        authRepository?.invalidatePendingAuthentication()
        profileGenerationGate.invalidate()
        profileLoadJob?.cancel()
        testJob?.cancel()
        loginJob?.cancel()
        authJob?.cancel()
        connectionSubmitJob?.cancel()
        onGatewayDisconnected()
        resetToFirstLaunch()
    }

    fun updateRememberLogin(remember: Boolean) { _uiState.update { it.copy(rememberLogin = remember) } }

    fun forgetSavedLogin(localId: String): Job {
        authRepository?.invalidatePendingAuthentication()
        return viewModelScope.launch {
            try {
                val profile = vaultStorage?.getConnectionProfile(localId) ?: return@launch
                val origin = CanonicalOrigin.parseOrThrow(profile.normalizedOrigin)
                authRepository?.forgetRememberedLogin(origin) ?: error("Authentication unavailable")
                _uiState.update {
                    it.copy(savedLoginMessage = "Saved login forgotten.", rememberLogin =
                        if (CanonicalOrigin.parse(it.origin).getOrNull()?.isSameOrigin(origin) == true) false else it.rememberLogin)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _uiState.update { it.copy(savedLoginMessage = "Saved login could not be forgotten. Try again.") } }
        }
    }

    fun updateHost(newHost: String) {
        if (newHost != _uiState.value.hostDraft) cancelDraftConnection()
        savedStateHandle[KEY_HOST] = newHost
        _uiState.update { it.copy(hostDraft = newHost, hostError = null) }
        val revision = ++draftRevision
        endpointPreferenceJob?.cancel()
        val repository = authRepository ?: return
        endpointPreferenceJob = viewModelScope.launch {
            try {
                val endpoint = repository.rememberedEndpoint(newHost.trim())
                if (revision == draftRevision && !explicitPortEdit &&
                    (endpoint == null || endpoint.scheme == CanonicalOrigin.Scheme.HTTP)) {
                    val port = (endpoint?.port ?: 9119).toString()
                    savedStateHandle[KEY_PORT] = port
                    _uiState.update { it.copy(portDraft = port, portError = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Explicit Connect still validates the entered address. */ }
        }
    }

    fun updatePort(newPort: String) {
        if (newPort != _uiState.value.portDraft) cancelDraftConnection()
        explicitPortEdit = true
        ++draftRevision
        endpointPreferenceJob?.cancel()
        savedStateHandle[KEY_PORT] = newPort
        _uiState.update { it.copy(portDraft = newPort, portError = null) }
    }

    fun updateUsername(newUsername: String) {
        _uiState.update { it.copy(usernameDraft = newUsername, loginError = null) }
    }

    fun updatePassword(newPassword: String) {
        _uiState.update { it.copy(passwordDraft = newPassword, loginError = null) }
    }

    fun testConnection() {
        if (testJob?.isActive == true || authJob?.isActive == true || loginJob?.isActive == true) return
        val current = _uiState.value
        // CHG-004: trim drafts at validation time only; the visible text stays as typed.
        val result = StructuredEndpointValidator.parse(
            ConnectProtocol.HTTP,
            current.hostDraft.trim(),
            current.portDraft.trim()
        )
        when (result) {
            is StructuredEndpointValidator.ValidationResult.HostError -> {
                diagnostics.emit(CatalogEvent.ENDPOINT_INVALID, DiagnosticMetadata.forEvent(CatalogEvent.ENDPOINT_INVALID, DiagnosticField.Stage.HOST))
                _uiState.update { it.copy(hostError = result.message) }
                return
            }
            is StructuredEndpointValidator.ValidationResult.PortError -> {
                diagnostics.emit(CatalogEvent.ENDPOINT_INVALID, DiagnosticMetadata.forEvent(CatalogEvent.ENDPOINT_INVALID, DiagnosticField.Stage.PORT))
                _uiState.update { it.copy(portError = result.message) }
                return
            }
            is StructuredEndpointValidator.ValidationResult.Success -> Unit
        }

        val preferenceRead = endpointPreferenceJob
        if (preferenceRead?.isActive == true) {
            if (connectionSubmitJob?.isActive != true) connectionSubmitJob = viewModelScope.launch {
                preferenceRead.join()
                testConnection()
            }
            return
        }
        val canonicalOrigin = (result as StructuredEndpointValidator.ValidationResult.Success).origin
        profileLoadJob?.cancel()
        val generation = beginConnectionGeneration()
        diagnostics.emit(CatalogEvent.CONNECT_SUBMIT)
        setState(ConnectState.PREFLIGHT_LOADING)
        val repository = authRepository ?: return
        val adapter = gatewayAdapter ?: return

        testJob = viewModelScope.launch {
            try {
                diagnostics.emit(CatalogEvent.PREFLIGHT_START)
                // An endpoint identity change must clear auth/runtime state before any request.
                if (lastAttemptedOrigin?.isSameOrigin(canonicalOrigin) == false) {
                    try {
                        onGatewayDisconnected()
                        adapter.disconnect()
                        // PR-6 REQ-06: an endpoint identity change is NOT the
                        // sign-out dialog — explicit KEEP (rotation still
                        // confines; no user was asked to purge).
                        repository.leaveConnection()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (profileGenerationGate.isCurrent(generation)) {
                            setState(ConnectState.HOST_UNREACHABLE)
                            _uiState.update { it.copy(errorMessage = "Previous connection could not be reset safely.") }
                        }
                        return@launch
                    }
                }
                lastAttemptedOrigin = canonicalOrigin
                val matchedProfile = !isAddMode && repository.selectConnectionForEndpoint(canonicalOrigin)
                if (matchedProfile && !serverNameEdited) {
                    val selected = vaultStorage?.getConnectionProfile()
                    if (selected != null && profileGenerationGate.isCurrent(generation))
                        _uiState.update { it.copy(serverNameDraft = selected.displayLabel) }
                } else if (!isAddMode && !matchedProfile && vaultStorage != null) {
                    val previous = vaultStorage.getConnectionProfile()
                    if (CanonicalOrigin.parse(previous?.normalizedOrigin).getOrNull()?.isSameOrigin(canonicalOrigin) != true) {
                        isAddMode = true
                        if (!serverNameEdited) _uiState.update { it.copy(serverNameDraft = DEFAULT_SERVER_NAME) }
                    }
                }
                val remember = repository.rememberLoginPreference(canonicalOrigin)
                if (!profileGenerationGate.isCurrent(generation)) return@launch
                _uiState.update { it.copy(rememberLogin = remember) }
                reloadProfiles()

                val statusResult = try {
                    repository.checkStatus(canonicalOrigin)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Result.failure(exception)
                }
                if (!profileGenerationGate.isCurrent(generation)) return@launch

                if (statusResult.isFailure) {
                    diagnostics.emit(CatalogEvent.PREFLIGHT_FAILED, DiagnosticMetadata.forEvent(CatalogEvent.PREFLIGHT_FAILED, DiagnosticField.ExceptionClass.from(statusResult.exceptionOrNull() ?: IllegalStateException())))
                    val exception = statusResult.exceptionOrNull()
                    if (exception is SecurityException ||
                        exception?.message?.contains("redirect", ignoreCase = true) == true ||
                        exception?.message?.contains("policy", ignoreCase = true) == true
                    ) {
                        setState(ConnectState.ORIGIN_POLICY_REJECTED)
                        _uiState.update {
                            it.copy(errorMessage = "Connection rejected by address, redirect, or Android network policy.")
                        }
                    } else {
                        setState(ConnectState.HOST_UNREACHABLE)
                        _uiState.update {
                            it.copy(errorMessage = "Host unreachable. Check your network or Tailscale connection.")
                        }
                    }
                    return@launch
                }

                val hostStatus = statusResult.getOrThrow()
                diagnostics.emit(CatalogEvent.PREFLIGHT_OK)
                if (!hostStatus.gatewayRunning) {
                    setState(ConnectState.HOST_UNREACHABLE)
                    _uiState.update { it.copy(errorMessage = "Hermes gateway is not running on host.") }
                    return@launch
                }

                val hasValidSession = try {
                    repository.hasValidSession(canonicalOrigin)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!profileGenerationGate.isCurrent(generation)) return@launch

                if (hostStatus.authRequired && !hasValidSession && recovery == null) {
                    setState(ConnectState.AUTH_REQUIRED)
                    diagnostics.emit(CatalogEvent.AUTH_REQUIRED)
                    _uiState.update {
                        it.copy(providerLabel = "Hermes Host (${hostStatus.version})", errorMessage = null)
                    }
                    return@launch
                }

                // An added profile must own authentication before borrowing an endpoint login.
                if (isAddMode && recovery != null) {
                    saveSuccessfulProfile(canonicalOrigin, generation)
                    if (!profileGenerationGate.isCurrent(generation)) return@launch
                    if (vaultStorage?.getConnectionProfile()?.normalizedOrigin != canonicalOrigin.originUrl) {
                        setState(ConnectState.FIRST_LAUNCH)
                        _uiState.update { it.copy(startupPresentation = StartupPresentation.SETUP,
                            errorMessage = "Connection details could not be saved. Try again.") }
                        return@launch
                    }
                }
                performGatewayConnect(canonicalOrigin, hostStatus, generation, explicitConnect = true)
            } catch (cancelled: CancellationException) {
                if (profileGenerationGate.isCurrent(generation) && _uiState.value.state == ConnectState.PREFLIGHT_LOADING) {
                    resetToFirstLaunch()
                }
                throw cancelled
            }
        }
    }

    private suspend fun performGatewayConnect(canonicalOrigin: CanonicalOrigin, hostStatus: HostStatus?, generation: Long,
        explicitConnect: Boolean = false) {
        val repository = authRepository ?: return
        val adapter = gatewayAdapter ?: return
        if (recovery != null) {
            recoveryOperationOwned = true
            try {
                val connected = recovery.connect(canonicalOrigin, retryPolicy = true, explicitConnect = explicitConnect)
                if (!profileGenerationGate.isCurrent(generation)) return
                if (!connected) {
                    setState(when (recovery.phase.value) {
                        com.hermes.bot.data.auth.RecoveryPhase.SIGN_IN_REQUIRED -> ConnectState.AUTH_REQUIRED
                        com.hermes.bot.data.auth.RecoveryPhase.POLICY_FAILURE -> ConnectState.ORIGIN_POLICY_REJECTED
                        else -> ConnectState.HOST_UNREACHABLE
                    })
                    return
                }
                saveSuccessfulProfile(canonicalOrigin, generation)
                if (!profileGenerationGate.isCurrent(generation)) return
                if (adapter.connectionStatus.value != ConnectionStatus.CONNECTED) { setState(ConnectState.HOST_UNREACHABLE); return }
                completeSuccessfulConnection(canonicalOrigin, generation)
                return
            } finally { recoveryOperationOwned = false }
        }
        diagnostics.emit(CatalogEvent.TICKET_START)
        val ticketResult = try {
            repository.mintWebSocketTicket(canonicalOrigin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Result.failure(exception)
        }
        if (!profileGenerationGate.isCurrent(generation)) return
        if (ticketResult.isFailure) {
            diagnostics.emit(CatalogEvent.TICKET_FAILED, DiagnosticMetadata.forEvent(CatalogEvent.TICKET_FAILED, DiagnosticField.ExceptionClass.from(ticketResult.exceptionOrNull() ?: IllegalStateException())))
            val exception = ticketResult.exceptionOrNull()
            if (exception is com.hermes.bot.data.auth.AuthSessionRejectedException) {
                setState(ConnectState.AUTH_REQUIRED)
            } else if (exception is com.hermes.bot.data.auth.AuthOriginPolicyRejectedException) {
                setState(ConnectState.ORIGIN_POLICY_REJECTED)
            } else {
                setState(ConnectState.HOST_UNREACHABLE)
                _uiState.update { it.copy(errorMessage = "Failed to obtain WebSocket ticket.") }
            }
            return
        }

        diagnostics.emit(CatalogEvent.TICKET_OK)
        diagnostics.emit(CatalogEvent.GATEWAY_CONNECT_START)
        val connected = try {
            adapter.connect(canonicalOrigin.originUrl, ticketResult.getOrThrow())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            if (profileGenerationGate.isCurrent(generation)) {
                diagnostics.emit(CatalogEvent.GATEWAY_CONNECT_FAILED, DiagnosticMetadata.forEvent(CatalogEvent.GATEWAY_CONNECT_FAILED, DiagnosticField.ExceptionClass.from(exception)))
            }
            false
        }
        if (!profileGenerationGate.isCurrent(generation)) return
        if (!connected) {
            if (adapter.connectionStatus.value == ConnectionStatus.AUTHENTICATION_REQUIRED) {
                setState(ConnectState.AUTH_REQUIRED)
            } else {
                setState(ConnectState.PROTOCOL_INCOMPATIBLE)
                _uiState.update { it.copy(errorMessage = "Gateway protocol handshake failed or protocol incompatible.") }
            }
            return
        }

        diagnostics.emit(CatalogEvent.GATEWAY_READY)
        diagnostics.emit(CatalogEvent.GATEWAY_CAPABILITIES_OK)
        _uiState.update {
            it.copy(
                providerLabel = "Hermes Host (${hostStatus?.version ?: "unknown"})",
                capabilitySummary = "hermes-gateway-v1: session.list, session.create, chat.stream",
                errorMessage = null
            )
        }
        saveSuccessfulProfile(canonicalOrigin, generation)
        if (!profileGenerationGate.isCurrent(generation)) return
        // Keep ownership through persistence: the shell's initial live read must
        // see the committed profile, and a socket lost during the save is not ready.
        when (adapter.connectionStatus.value) {
            ConnectionStatus.CONNECTED -> {
                completeSuccessfulConnection(canonicalOrigin, generation)
            }
            ConnectionStatus.AUTHENTICATION_REQUIRED -> setState(ConnectState.AUTH_REQUIRED)
            else -> setState(ConnectState.HOST_UNREACHABLE)
        }
    }

    fun authenticate() {
        if (_uiState.value.state != ConnectState.AUTH_REQUIRED) return
        if (authJob?.isActive == true || testJob?.isActive == true || loginJob?.isActive == true) return
        val repository = authRepository ?: run {
            _uiState.update { it.copy(errorMessage = "Authentication is unavailable until a host session is configured.") }
            return
        }
        if (gatewayAdapter == null) return

        val generation = beginConnectionGeneration()
        authJob = viewModelScope.launch {
            val result = StructuredEndpointValidator.parse(
                ConnectProtocol.HTTP,
                _uiState.value.hostDraft.trim(),
                _uiState.value.portDraft.trim()
            )
            val canonical = (result as? StructuredEndpointValidator.ValidationResult.Success)?.origin
            val hasValidSession = try {
                if (canonical != null) repository.hasValidSession(canonical) else false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!profileGenerationGate.isCurrent(generation)) return@launch
            if (canonical != null && hasValidSession) {
                performGatewayConnect(canonical, null, generation)
            } else {
                _uiState.update { it.copy(errorMessage = "Authentication required. Host session not found.") }
            }
        }
    }

    fun login() {
        if (_uiState.value.state != ConnectState.AUTH_REQUIRED) return
        if (loginJob?.isActive == true || authJob?.isActive == true || testJob?.isActive == true || _uiState.value.loginLoading) return
        val repository = authRepository ?: run {
            _uiState.update { it.copy(loginError = "Authentication is unavailable.") }
            return
        }
        if (gatewayAdapter == null) return

        val current = _uiState.value
        val username = current.usernameDraft.trim()
        if (username.isBlank()) {
            _uiState.update { it.copy(loginError = "Username is required.") }
            return
        }
        if (current.passwordDraft.isEmpty()) {
            _uiState.update { it.copy(loginError = "Password is required.") }
            return
        }

        val result = StructuredEndpointValidator.parse(
            ConnectProtocol.HTTP,
            current.hostDraft.trim(),
            current.portDraft.trim()
        )
        val canonical = (result as? StructuredEndpointValidator.ValidationResult.Success)?.origin
        if (canonical == null) {
            _uiState.update { it.copy(loginError = "Invalid host address. Go back and fix the connection details.") }
            return
        }

        recovery?.invalidate()
        val generation = beginConnectionGeneration()
        _uiState.update { it.copy(loginLoading = true, loginError = null) }

        loginJob = viewModelScope.launch {
            val passwordChars = current.passwordDraft.toCharArray()
            diagnostics.emit(CatalogEvent.LOGIN_START)
            val loginResult = try {
                if (recovery != null) {
                    saveSuccessfulProfile(canonical, generation)
                    if (!profileGenerationGate.isCurrent(generation) ||
                        vaultStorage?.getConnectionProfile()?.normalizedOrigin != canonical.originUrl) {
                        throw IllegalStateException("Connection could not be saved")
                    }
                }
                repository.login(canonical, username, passwordChars, remember = current.rememberLogin)
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(loginLoading = false) }
                throw cancelled
            } catch (exception: Exception) {
                Result.failure(exception)
            } finally {
                passwordChars.fill('\u0000')
            }

            // A non-cooperative repository can return after cancellation. It must not
            // publish login state or begin gateway work for a newer connection epoch.
            if (!profileGenerationGate.isCurrent(generation)) return@launch

            if (loginResult.isFailure) {
                // CHG-004 note: DiagnosticField.Stage.LOGIN cannot be added here without
                // extending CatalogEvent.LOGIN_FAILED.allowedMetadata in DiagnosticsModel.kt
                // (outside this change's allowed paths); diagnostics left unchanged.
                diagnostics.emit(CatalogEvent.LOGIN_FAILED, DiagnosticMetadata.forEvent(CatalogEvent.LOGIN_FAILED, DiagnosticField.ExceptionClass.from(loginResult.exceptionOrNull() ?: IllegalStateException())))
                val ex = loginResult.exceptionOrNull()
                val message = when {
                    ex is com.hermes.bot.data.auth.SavedPasswordRejectedException -> "Invalid username or password."
                    ex is SecurityException && ex.message?.contains("401") == true ->
                        "Invalid username or password."
                    ex is SecurityException && ex.message?.contains("403") == true ->
                        "Access denied. Check your credentials."
                    ex is SecurityException && ex.message?.contains("Preflight") == true ->
                        "Host became unreachable. Check your connection and try again."
                    ex is SecurityException && ex.message?.contains("no valid session cookie", ignoreCase = true) == true ->
                        "Authentication succeeded but the host returned no session cookie. Contact your host administrator."
                    else -> "Login failed (${describeLoginFailureCause(ex)}). Please try again."
                }
                _uiState.update { it.copy(loginLoading = false, loginError = message) }
                return@launch
            }

            // Password entry has finished; keep connection progress separate.
            diagnostics.emit(CatalogEvent.LOGIN_OK)
            _uiState.update { it.copy(loginLoading = false, passwordDraft = "", loginError = null) }
            setState(ConnectState.PREFLIGHT_LOADING)
            performGatewayConnect(canonical, null, generation)
        }
    }

    fun retry() = testConnection()

    fun toggleDiagnostics() {
        _uiState.update { it.copy(showDiagnostics = !it.showDiagnostics) }
    }

    fun resetToFirstLaunch() {
        _uiState.update { it.copy(startupPresentation = StartupPresentation.SETUP) }
        setState(ConnectState.FIRST_LAUNCH)
    }

    /**
     * PR-6 REQ-06: [cacheChoice] is an explicit, non-defaulted parameter —
     * the sign-out dialog resolves KEEP/CLEAR before this call; retention is
     * only ever an explicitly designated [SignOutCacheChoice.KEEP].
     */
    fun disconnect(cacheChoice: SignOutCacheChoice) {
        ++draftRevision
        endpointPreferenceJob?.cancel()
        connectionSubmitJob?.cancel()
        authRepository?.invalidatePendingAuthentication()
        profileGenerationGate.invalidate()
        diagnostics.emit(CatalogEvent.GATEWAY_DISCONNECTED)
        profileLoadJob?.cancel()
        testJob?.cancel()
        authJob?.cancel()
        loginJob?.cancel()
        isAddMode = false
        _uiState.update { it.copy(loginLoading = false, loginError = null) }
        // BUG-012 symmetry: an explicit disconnect disarms automatic reconnect.
        onGatewayDisconnected()
        viewModelScope.launch {
            try {
                authRepository?.signOut(cacheChoice = cacheChoice)
                gatewayAdapter?.disconnect()
            } catch (_: Exception) { }
        }
        resetToFirstLaunch()
    }

    fun switchProfile(targetLocalId: String): Job = viewModelScope.launch {
        val vault = vaultStorage ?: return@launch
        val currentProfiles = _profilesUiState.value.profiles.ifEmpty {
            vault.listConnectionProfiles().also { loaded ->
                _profilesUiState.update { it.copy(profiles = loaded) }
            }
        }
        val activeId = _profilesUiState.value.activeProfileId ?: vault.getActiveProfileId()
        val decision = ProfileSwitchPolicy.canSwitch(
            currentConnectState = _uiState.value.state,
            targetLocalId = targetLocalId,
            activeLocalId = activeId,
            availableProfileIds = currentProfiles.map { it.localId }
        )
        if (decision !is ProfileSwitchDecision.Allowed) return@launch
        ++draftRevision
        endpointPreferenceJob?.cancel()
        connectionSubmitJob?.cancel()

        // 1. Persist intent first
        vault.setActiveProfileId(targetLocalId)
        setState(ConnectState.PREFLIGHT_LOADING)
        _profilesUiState.update { it.copy(activeProfileId = targetLocalId) }

        // 2. Gate invalidation cancels stale preflight
        profileGenerationGate.invalidate()
        profileLoadJob?.cancel()
        testJob?.cancel()
        authJob?.cancel()
        loginJob?.cancel()
        _uiState.update { it.copy(loginLoading = false, loginError = null) }

        // 3. Disconnect
        onGatewayDisconnected()
        try {
            gatewayAdapter?.disconnect()
        } catch (_: Exception) {}

        // 4. Rebind & auto-connect
        isAddMode = false
        loadStoredProfile(explicitConnect = true)
        reloadProfiles()
    }

    fun onSwitchProfile(targetLocalId: String): Job = switchProfile(targetLocalId)

    fun deleteProfile(localId: String): Job = viewModelScope.launch {
        val vault = vaultStorage ?: return@launch
        val wasActive = _profilesUiState.value.activeProfileId == localId
        val deleted = vault.deleteConnectionProfile(localId)
        if (deleted) {
            if (wasActive) {
                // PR-6 REQ-06: profile delete is not the sign-out dialog —
                // explicit KEEP (the profile's own subtree purge is
                // deleteConnectionProfile's job, unchanged).
                disconnect(SignOutCacheChoice.KEEP)
            }
            reloadProfiles()
        }
    }

    /** Internal automated-test seam. It has no production UI callback. */
    internal fun transitionForTest(newState: ConnectState) = setState(newState)

    private fun setState(newState: ConnectState) {
        savedStateHandle[KEY_STATE] = newState.name
        _uiState.update { current ->
            current.copy(
                state = newState,
                startupPresentation = when (newState) {
                    ConnectState.AUTH_REQUIRED -> if (current.startupPresentation == StartupPresentation.SESSIONS)
                        StartupPresentation.SESSIONS else StartupPresentation.SIGN_IN
                    ConnectState.CONNECTED_COMPATIBLE -> StartupPresentation.SESSIONS
                    else -> current.startupPresentation
                },
                loginError = if (newState == ConnectState.AUTH_REQUIRED && current.startupPresentation == StartupPresentation.SESSIONS)
                    "Your login expired. Sign in again." else current.loginError,
                errorMessage = when (newState) {
                    ConnectState.HOST_UNREACHABLE -> "Unable to reach host. Check your private network or Tailscale connection."
                    ConnectState.ORIGIN_POLICY_REJECTED -> "Connection rejected: invalid address, redirect blocked, or Android network policy conflict."
                    ConnectState.PROTOCOL_INCOMPATIBLE -> "Protocol version mismatch: required hermes-gateway-v1."
                    else -> null
                }
            )
        }
    }

    private fun validateServerName(name: String): String? = when {
        name.length > MAX_SERVER_NAME_LENGTH -> "Server name must be at most $MAX_SERVER_NAME_LENGTH characters."
        name.any { it.code in 0..31 || it.code in 127..159 } -> "Server name contains forbidden control characters."
        BIDI_CONTROL_REGEX.containsMatchIn(name) -> "Server name contains forbidden bidirectional characters."
        else -> null
    }

    /**
     * CHG-004: maps a login failure exception to a small fixed, user-safe cause
     * vocabulary. Never returns raw exception text, hostnames, IPs, or credentials.
     */
    private fun describeLoginFailureCause(ex: Throwable?): String {
        val cause = when (ex) {
            is java.io.IOException -> "network error"
            is SecurityException -> "security check failed"
            is IllegalStateException -> "host response invalid"
            else -> "unexpected error"
        }
        // CHG-004b: surface the exception class simple name so a failing device can be
        // diagnosed from the UI alone. Class simple names are fixed category labels —
        // they never contain credentials, hostnames, IPs, or exception messages.
        val className = ex?.javaClass?.simpleName?.take(48).orEmpty()
        return if (className.isBlank()) cause else "$cause: $className"
    }

    private suspend fun loadStoredProfile(explicitConnect: Boolean = false) {
        val draftAtLoad = draftRevision
        val loadGeneration = profileGenerationGate.currentGeneration()
        if (!explicitConnect) {
            val endpoint = authRepository?.rememberedEndpoint()
            if (endpoint != null && profileGenerationGate.isCurrent(loadGeneration) && draftAtLoad == draftRevision)
                authRepository?.selectConnectionForEndpoint(endpoint)
        }
        val profile = vaultStorage?.getConnectionProfile() ?: run {
            val endpoint = authRepository?.rememberedEndpoint()
            if (endpoint != null && endpoint.scheme == CanonicalOrigin.Scheme.HTTP &&
                profileGenerationGate.isCurrent(loadGeneration) && draftAtLoad == draftRevision) {
                _uiState.update { it.copy(hostDraft = endpoint.host, portDraft = endpoint.port.toString()) }
            }
            if (profileGenerationGate.isCurrent(loadGeneration)) resetToFirstLaunch()
            return
        }
        if (!profileGenerationGate.isCurrent(loadGeneration) || draftAtLoad != draftRevision) return
        val parsed = CanonicalOrigin.parse(profile.normalizedOrigin).getOrNull()
        if (parsed?.scheme == CanonicalOrigin.Scheme.HTTPS) vaultStorage?.clearAuthCredential(profile.localId)
        if (parsed == null || parsed.pathPrefix.isNotEmpty()) {
            if (profileGenerationGate.isCurrent(loadGeneration)) {
                _uiState.update { it.copy(startupPresentation = StartupPresentation.SETUP, errorMessage = "Saved address requires re-entry before connecting.") }
            }
            return
        }
        serverNameEdited = false
        explicitPortEdit = false
        val restoredServerNameError = validateServerName(profile.displayLabel)
        val restoredServerName = profile.displayLabel
            .takeIf { restoredServerNameError == null }
            ?: DEFAULT_SERVER_NAME
        if (restoredServerNameError != null) {
            savedStateHandle[KEY_SERVER_NAME] = restoredServerName
        }
        if (!profileGenerationGate.isCurrent(loadGeneration)) return
        _uiState.update {
            it.copy(
                startupPresentation = if (restoredServerNameError != null || parsed.scheme == CanonicalOrigin.Scheme.HTTPS)
                    StartupPresentation.SETUP else it.startupPresentation,
                serverNameDraft = restoredServerName,
                hostDraft = parsed.host,
                portDraft = parsed.port.toString(),
                errorMessage = restoredServerNameError ?: if (parsed.scheme == CanonicalOrigin.Scheme.HTTPS) {
                    "Saved HTTPS address was loaded for re-entry. HTTP is selected; submit explicitly to change transport."
                } else {
                    null
                }
            )
        }

        // Auto-reconnect: if we have a valid stored session for this origin, attempt gateway connect
        if (parsed.scheme != CanonicalOrigin.Scheme.HTTPS && restoredServerNameError == null) {
            val repository = authRepository ?: return
            val remember = repository.rememberLoginPreference(parsed)
            if (!profileGenerationGate.isCurrent(loadGeneration)) return
            _uiState.update { it.copy(rememberLogin = remember) }
            val localLogin = repository.prepareLocalLogin(parsed)
            if (localLogin) _uiState.update { it.copy(startupPresentation = StartupPresentation.SESSIONS) }
            val hasSession = try {
                repository.hasValidSession(parsed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            if (!profileGenerationGate.isCurrent(loadGeneration)) return
            val automaticLogin = repository.hasAutomaticLogin(parsed)
            if (!profileGenerationGate.isCurrent(loadGeneration)) return
            if (hasSession || localLogin || explicitConnect || automaticLogin) {
                _uiState.update { it.copy(startupPresentation = if (hasSession || localLogin || explicitConnect)
                    StartupPresentation.SESSIONS else StartupPresentation.SETUP) }
                val generation = beginConnectionGeneration()
                setState(ConnectState.PREFLIGHT_LOADING)
                lastAttemptedOrigin = parsed
                if (recovery != null) onGatewayConnected(parsed)
                performGatewayConnect(parsed, null, generation, explicitConnect = explicitConnect)
            } else {
                val savedLogin = repository.hasRememberedLogin(parsed)
                if (!profileGenerationGate.isCurrent(loadGeneration)) return
                if (savedLogin) _uiState.update { it.copy(startupPresentation = StartupPresentation.SETUP) }
                setState(if (savedLogin) ConnectState.FIRST_LAUNCH else ConnectState.AUTH_REQUIRED)
            }
        }
    }

    private suspend fun completeSuccessfulConnection(origin: CanonicalOrigin, generation: Long) {
        recordSuccessfulEndpoint(origin, generation)
        if (!profileGenerationGate.isCurrent(generation)) return
        when (gatewayAdapter?.connectionStatus?.value) {
            ConnectionStatus.CONNECTED -> {
                val persistenceError = _uiState.value.errorMessage
                setState(ConnectState.CONNECTED_COMPATIBLE)
                _uiState.update { it.copy(errorMessage = persistenceError) }
                onGatewayConnected(origin)
            }
            ConnectionStatus.AUTHENTICATION_REQUIRED -> setState(ConnectState.AUTH_REQUIRED)
            else -> setState(ConnectState.HOST_UNREACHABLE)
        }
    }

    private suspend fun recordSuccessfulEndpoint(origin: CanonicalOrigin, generation: Long) {
        try { authRepository?.noteSuccessfulEndpoint(origin) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (profileGenerationGate.isCurrent(generation)) _uiState.update {
                it.copy(errorMessage = "Connected, but the last-used address could not be saved.")
            }
        }
    }

    private suspend fun saveSuccessfulProfile(origin: CanonicalOrigin, generation: Long) {
        if (!profileGenerationGate.isCurrent(generation)) return
        try {
            val vault = vaultStorage
            if (vault == null) {
                if (profileGenerationGate.isCurrent(generation)) diagnostics.emit(CatalogEvent.PROFILE_SAVE_OK)
                return
            }
            val existing = if (isAddMode) {
                null
            } else {
                when (val read = vault.readConnectionProfileForUpdate()) {
                    is ProfileReadResult.AVAILABLE -> read.profile
                    ProfileReadResult.ABSENT -> null
                    is ProfileReadResult.FAILED -> {
                        reportCurrentProfileSaveFailure(generation)
                        return
                    }
                }
            }
            if (!profileGenerationGate.isCurrent(generation)) return
            val draftedServerName = _uiState.value.serverNameDraft.trim()
            val serverNameValidationError = validateServerName(draftedServerName)
            val persistedServerName = draftedServerName
                .takeIf { serverNameValidationError == null }
                ?: DEFAULT_SERVER_NAME
            if (serverNameValidationError != null) {
                savedStateHandle[KEY_SERVER_NAME] = persistedServerName
                _uiState.update { it.copy(serverNameDraft = persistedServerName, errorMessage = serverNameValidationError) }
            }
            if (!profileGenerationGate.isCurrent(generation)) return
            val targetLocalId = if (isAddMode || existing == null) {
                UUID.randomUUID().toString()
            } else {
                existing.localId
            }
            val targetCreatedAt = if (isAddMode || existing == null) {
                System.currentTimeMillis()
            } else {
                existing.createdAt
            }
            val profileToSave = ConnectionProfile(
                localId = targetLocalId,
                normalizedOrigin = origin.originUrl,
                displayLabel = persistedServerName,
                lastSuccessfulProtocol = origin.scheme.value,
                createdAt = targetCreatedAt
            )
            when (
                vault.saveConnectionProfileIfCurrent(
                    profileToSave,
                    generation,
                    profileGenerationGate
                )
            ) {
                ProfileCommitResult.SAVED -> if (profileGenerationGate.isCurrent(generation)) {
                    diagnostics.emit(CatalogEvent.PROFILE_SAVE_OK)
                    vault.setActiveProfileId(targetLocalId)
                    isAddMode = false
                    reloadProfiles()
                }
                ProfileCommitResult.STALE -> Unit
                is ProfileCommitResult.FAILED -> reportCurrentProfileSaveFailure(generation)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportCurrentProfileSaveFailure(generation)
        }
    }

    private fun reportCurrentProfileSaveFailure(generation: Long) {
        if (!profileGenerationGate.isCurrent(generation)) return
        diagnostics.emit(
            CatalogEvent.PROFILE_SAVE_FAILED,
            DiagnosticMetadata.forEvent(
                CatalogEvent.PROFILE_SAVE_FAILED,
                DiagnosticField.ExceptionClass.from(IllegalStateException())
            )
        )
        if (profileGenerationGate.isCurrent(generation)) {
            _uiState.update { it.copy(errorMessage = "Connected, but the local connection could not be saved.") }
        }
    }

    private fun beginConnectionGeneration(): Long = profileGenerationGate.beginAttempt().also {
        gatewayStatusGeneration = it
        droppedGatewayGeneration = null
    }
}
