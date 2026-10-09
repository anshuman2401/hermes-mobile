package com.hermes.bot.data.gateway

import com.hermes.bot.data.approval.*
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonBoolean
import com.hermes.bot.data.gateway.json.JsonNull
import com.hermes.bot.data.gateway.json.JsonArray
import com.hermes.bot.data.gateway.json.JsonNumber
import com.hermes.bot.data.gateway.json.JsonObject
import com.hermes.bot.data.gateway.json.JsonString
import com.hermes.bot.data.gateway.json.JsonValue
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.botscreen.ScreenLease
import com.hermes.bot.data.botscreen.ScreenObservation
import com.hermes.bot.data.botscreen.ScreenStatus
import com.hermes.bot.data.diagnostics.DiagnosticEvent
import com.hermes.bot.data.diagnostics.DiagnosticRing
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Production implementation of [GatewayProtocolAdapter] operating over a [GatewayTransport] seam.
 * Enforces JSON-RPC correlation, strict capability validation, dual session.resume parsing,
 * heartbeat monitoring, secret redaction, and deterministic replay reduction with history-fallback signaling.
 *
 * RPR-004 enhancements:
 * - Connection generations & mutex serialization for connect/disconnect/cleanup transitions.
 * - Stale generations (transports, readers, RPCs, waiters, callbacks) are inert.
 * - Strict RPC gating behind active accepted socket, gateway.ready, and per_session_exclusive_submit.
 * - Heartbeat management: only runs when advertised, pausible for backgrounding, exactly one job per active socket.
 * - Lossless replay/reconnect: holds live events during replay and applies replay then held live events atomically.
 * - Observable watermark advancement: watermark only commits after downstream observable acceptance; overflow signals history fallback.
 * - DoS limits: frame size limit (2 MB), replay batch count limit (10,000).
 */
class DefaultGatewayProtocolAdapter(
    private val transport: GatewayTransport,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val readyTimeoutMs: Long = 10_000L,
    private val rpcTimeoutMs: Long = 10_000L,
    private val heartbeatIntervalMs: Long = 15_000L,
    private val heartbeatDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val diagnosticRing: DiagnosticRing? = null
) : GatewayProtocolAdapter {

    companion object {
        const val MAX_FRAME_BYTES = 2 * 1024 * 1024 // 2 MB
        const val MAX_REPLAY_BATCH_COUNT = 10_000
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val connectionMutex = Mutex()
    private val approvalStore = ApprovalInboxStore()
    override val approvalInbox: StateFlow<ApprovalInbox> = approvalStore.state.asStateFlow()


    private val _connectionStatus = MutableStateFlow(ConnectionStatus.OFFLINE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _terminalFailure = MutableStateFlow<GatewayFailure?>(null)
    override val terminalFailure: StateFlow<GatewayFailure?> = _terminalFailure.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _historyFallbackSignals = MutableSharedFlow<HistoryFallbackSignal>(extraBufferCapacity = 64)
    override val historyFallbackSignals: SharedFlow<HistoryFallbackSignal> = _historyFallbackSignals.asSharedFlow()

    private val replayReducer = GatewayReplayReducer()
    override val currentReplayEpoch: String?
        get() = replayReducer.currentReplayEpoch

    @Volatile
    override var connectionGeneration: Long = 0L
        private set

    @Volatile private var connectedOrigin: CanonicalOrigin? = null

    private val requestCounter = AtomicLong(0)

    private val pendingRequests = ConcurrentHashMap<String, PendingRpc>()
    private var readyDeferred: Pair<Long, CompletableDeferred<GatewayEvent.Ready>>? = null
    private var pingDeferred: Pair<Long, CompletableDeferred<Boolean>>? = null
    private var transportReaderJob: Job? = null
    private var heartbeatJob: Job? = null
    private var heartbeatAdvertised = false

    private val sessionReplayMutexes = ConcurrentHashMap<String, Mutex>()
    private val sessionLocks = ConcurrentHashMap<String, Any>()
    private val replayingSessions = ConcurrentHashMap.newKeySet<String>()
    private val heldLiveEvents = ConcurrentHashMap<String, MutableList<GatewayEvent>>()

    private data class PendingObservableEvent(
        val event: GatewayEvent,
        val durableSessionId: String?
    )
    private val pendingUnsubscribedEvents = ConcurrentLinkedQueue<PendingObservableEvent>()

    init {
        scope.launch {
            _events.subscriptionCount.collect { count ->
                if (count > 0) {
                    drainPendingUnsubscribedEvents()
                }
            }
        }
    }

    private fun drainPendingUnsubscribedEvents() {
        while (pendingUnsubscribedEvents.isNotEmpty() && _events.subscriptionCount.value > 0) {
            val pending = pendingUnsubscribedEvents.poll() ?: break
            val sid = pending.event.sessionId
            val seq = pending.event.seq
            val emitted = _events.tryEmit(pending.event)
            if (emitted) {
                if (sid != null && seq != null && seq > 0L) {
                    replayReducer.commitWatermark(sid, seq)
                }
            } else {
                if (sid != null) {
                    replayReducer.clearWatermark(sid)
                    replayReducer.markAwaitingHistoryRehydration(sid)
                    _historyFallbackSignals.tryEmit(
                        HistoryFallbackSignal(
                            durableSessionId = pending.durableSessionId,
                            runtimeSessionId = sid,
                            reason = FallbackReason.OVERFLOW,
                            details = "Downstream event buffer overflow draining pending events"
                        )
                    )
                }
            }
        }
    }

    private data class PendingRpc(
        val method: String,
        val deferred: CompletableDeferred<JsonValue>,
        val generation: Long
    )

    override fun getLastSeenSeq(sessionId: String): Long? = replayReducer.getWatermark(sessionId)

    override fun setLastSeenSeq(sessionId: String, seq: Long) {
        replayReducer.setWatermark(sessionId, seq)
    }

    override fun clearWatermark(sessionId: String) {
        replayReducer.clearWatermark(sessionId)
    }

    override fun markHistoryRehydrated(sessionId: String) {
        replayReducer.markHistoryRehydrated(sessionId)
    }

    override suspend fun connect(
        origin: String,
        ticket: GatewayTicket,
        useQueryFallback: Boolean
    ): Boolean = connectionMutex.withLock {
        connectedOrigin = null
        approvalStore.reset()
        // Enforce CanonicalOrigin at adapter boundary per ADR-0003 & RPR-003
        val canonicalOrigin = CanonicalOrigin.parse(origin).getOrElse {
            _connectionStatus.value = ConnectionStatus.FAILED
            return false
        }

        // Validate ticket format and positive TTL
        if (ticket.value.isBlank() || ticket.ttlSeconds <= 0) {
            _connectionStatus.value = ConnectionStatus.FAILED
            return false
        }

        val wsBase = canonicalOrigin.webSocketUrl
        val safeWsUrl = "$wsBase/api/ws"
        diagnosticRing?.updateHostInfo(origin = canonicalOrigin.originUrl)
        diagnosticRing?.record(DiagnosticEvent.WebSocket.Connecting(url = safeWsUrl))

        // Clean up previous generation BEFORE advancing connectionGeneration per F-002
        closeTransport(connectionGeneration, 1000, "Reconnecting new generation")

        val gen: Long
        synchronized(this) {
            gen = ++connectionGeneration
        }
        if (transport is com.hermes.bot.data.gateway.okhttp.OkHttpGatewayTransport) {
            transport.activeGeneration = gen
        }

        _terminalFailure.value = null
        _connectionStatus.value = ConnectionStatus.CONNECTING
        requestCounter.set(0)

        val readyDef = CompletableDeferred<GatewayEvent.Ready>()
        readyDeferred = Pair(gen, readyDef)

        val ticketValue = ticket.value
        val config = if (useQueryFallback) {
            GatewayConnectConfig(
                url = "$wsBase/api/ws?ticket=$ticketValue",
                subprotocols = listOf("hermes-gateway-v1")
            )
        } else {
            GatewayConnectConfig(
                url = "$wsBase/api/ws",
                subprotocols = listOf("hermes-gateway-v1", "hermes-gateway-ticket.$ticketValue")
            )
        }

        // Launch frame reader for this generation before connect so handshake frames are captured
        startTransportReader(gen)

        val connectResult = transport.connect(config)
        if (connectResult is GatewayConnectResult.Failure) {
            closeTransport(gen, 1000, "Connect failed")
            diagnosticRing?.record(
                DiagnosticEvent.WebSocket.Disconnected(
                    code = connectResult.handshakeStatusCode,
                    reason = "Connect failed: ${connectResult.handshakeStatusCode}"
                )
            )
            // Certified Host Incompatibility #1: ASGI 403 on ticket failure maps to 4401 equivalent
            if (connectResult.handshakeStatusCode == 403 || connectResult.handshakeStatusCode == 4401 || connectResult.handshakeStatusCode == 4403) {
                _terminalFailure.value = GatewayFailure.Transport("Handshake rejected (${connectResult.handshakeStatusCode})", statusCode = connectResult.handshakeStatusCode)
                _connectionStatus.value = ConnectionStatus.AUTHENTICATION_REQUIRED
            } else {
                _connectionStatus.value = ConnectionStatus.FAILED
            }
            return false
        }

        // Gate 1: Wait for gateway.ready frame (terminated promptly on close/error)
        val readyEvent = try {
            withTimeoutOrNull(readyTimeoutMs) {
                readyDef.await()
            }
        } catch (_: Exception) {
            null
        }

        if (readyEvent == null) {
            // Preserve classified connection status if set by incoming close/error
            if (_connectionStatus.value == ConnectionStatus.CONNECTING) {
                _connectionStatus.value = ConnectionStatus.FAILED
            }
            closeTransport(gen, 1000, "Ready frame missing or connection terminated")
            diagnosticRing?.record(
                DiagnosticEvent.WebSocket.Disconnected(
                    code = 1000,
                    reason = "Ready frame missing or connection terminated"
                )
            )
            return false
        }

        heartbeatAdvertised = readyEvent.heartbeat

        // Evaluate ready epoch; emit observable history fallback signals if epoch changed
        val epochResult = replayReducer.updateEpoch(readyEvent.replayEpoch)
        if (epochResult.changed) {
            if (epochResult.affectedSessions.isEmpty()) {
                _historyFallbackSignals.tryEmit(
                    HistoryFallbackSignal(
                        durableSessionId = null,
                        runtimeSessionId = null,
                        reason = FallbackReason.EPOCH_CHANGED_READY,
                        details = "gateway.ready announced new epoch ${epochResult.newEpoch} (was ${epochResult.oldEpoch}); history must be rehydrated via session.resume"
                    )
                )
            } else {
                for (sid in epochResult.affectedSessions) {
                    _historyFallbackSignals.tryEmit(
                        HistoryFallbackSignal(
                            durableSessionId = null,
                            runtimeSessionId = sid,
                            reason = FallbackReason.EPOCH_CHANGED_READY,
                            details = "gateway.ready announced new epoch ${epochResult.newEpoch} (was ${epochResult.oldEpoch}); history for session $sid must be rehydrated via session.resume"
                        )
                    )
                }
            }
        }

        // Gate 2: Call gateway.capabilities and verify per_session_exclusive_submit
        val capabilitiesResult = withTimeoutOrNull(rpcTimeoutMs) {
            getCapabilities(allowHandshakeRpc = true)
        }

        val capabilities = when (capabilitiesResult) {
            null -> {
                val failure = GatewayFailure.Transport(
                    message = "Capabilities RPC timed out after ${rpcTimeoutMs}ms",
                    terminal = true
                )
                _terminalFailure.value = failure
                closeTransport(gen, 1000, "Capabilities RPC timed out")
                _connectionStatus.value = ConnectionStatus.FAILED
                return false
            }
            is GatewayResult.Failure -> {
                val failure = when (val f = capabilitiesResult.failure) {
                    is GatewayFailure.Rpc -> GatewayFailure.Rpc(
                        code = f.code,
                        message = "Capabilities RPC failed (${f.code}): ${f.message}",
                        data = f.data,
                        terminal = true
                    )
                    is GatewayFailure.Transport -> GatewayFailure.Transport(
                        message = "Capabilities RPC transport failure: ${f.message}",
                        causeClassName = f.causeClassName,
                        statusCode = f.statusCode,
                        terminal = true
                    )
                    is GatewayFailure.IncompatibleProtocol -> f
                    is GatewayFailure.AuthenticationRequired -> f
                    else -> GatewayFailure.IncompatibleProtocol("Capabilities RPC failed: $f")
                }
                _terminalFailure.value = failure
                closeTransport(gen, 1000, "Capabilities RPC failed")
                _connectionStatus.value = ConnectionStatus.FAILED
                return false
            }
            is GatewayResult.Success -> capabilitiesResult.value
        }

        if (!capabilities.perSessionExclusiveSubmit) {
            val failure = GatewayFailure.IncompatibleProtocol("Incompatible gateway capabilities: missing per_session_exclusive_submit")
            _terminalFailure.value = failure
            closeTransport(gen, 1000, "Incompatible capabilities")
            _connectionStatus.value = ConnectionStatus.FAILED
            return false
        }

        if (gen != connectionGeneration) return false
        // Optional client capability: older hosts remain usable for ordinary chat.
        val advertisement = sendRpc("client.capabilities", mapOf("server_requests" to true), allowHandshakeRpc = true)
        if (gen != connectionGeneration) return false
        approvalStore.available(advertisement is GatewayResult.Success &&
            (advertisement.value["server_requests"] as? JsonArray)?.list?.any { it.asStringOrNull() == "approval" } == true)
        connectedOrigin = canonicalOrigin
        _connectionStatus.value = ConnectionStatus.CONNECTED
        diagnosticRing?.record(DiagnosticEvent.WebSocket.Connected(url = safeWsUrl))

        if (heartbeatAdvertised) {
            startHeartbeatLoop(gen)
        }

        return true
    }

    private fun startHeartbeatLoop(generation: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(heartbeatDispatcher) {
            while (isActive && generation == connectionGeneration && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                delay(heartbeatIntervalMs)
                if (!isActive || generation != connectionGeneration || _connectionStatus.value != ConnectionStatus.CONNECTED) {
                    break
                }
                val pingRes = ping()
                if (pingRes is GatewayResult.Failure) {
                    closeTransport(generation, 1000, "Heartbeat ping failed")
                    _connectionStatus.value = ConnectionStatus.FAILED
                    break
                }
            }
        }
    }

    override fun suspendHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    override fun resumeHeartbeat() {
        if (heartbeatAdvertised && _connectionStatus.value == ConnectionStatus.CONNECTED && heartbeatJob == null) {
            startHeartbeatLoop(connectionGeneration)
        }
    }

    private fun closeTransport(generation: Long, code: Int = 1000, reason: String? = null) {
        if (generation != connectionGeneration && generation != -1L) {
            return
        }

        connectedOrigin = null
        approvalStore.reset()
        heartbeatJob?.cancel()
        heartbeatJob = null
        transportReaderJob?.cancel()
        transportReaderJob = null

        // Teardown path: discard held replay state and emit fallback signals per F-003
        val sessionsInHold = heldLiveEvents.keys.toList()
        for (sid in sessionsInHold) {
            val lock = sessionLocks.computeIfAbsent(sid) { Any() }
            synchronized(lock) {
                val wasReplaying = replayingSessions.remove(sid)
                val held = heldLiveEvents.remove(sid)
                if (wasReplaying || !held.isNullOrEmpty()) {
                    replayReducer.clearWatermark(sid)
                    replayReducer.markAwaitingHistoryRehydration(sid)
                    _historyFallbackSignals.tryEmit(
                        HistoryFallbackSignal(
                            durableSessionId = null,
                            runtimeSessionId = sid,
                            reason = FallbackReason.INVALID_ORDER_OR_GAP,
                            details = "Transport closed during replay hold; held events dropped, session requires rehydration"
                        )
                    )
                }
            }
        }

        transport.close(code, reason)

        val pending = pendingRequests.values.toList()
        pendingRequests.clear()
        for (p in pending) {
            p.deferred.completeExceptionally(
                GatewayException(GatewayFailure.NotConnected("Gateway closed"))
            )
        }
        readyDeferred?.second?.cancel()
        readyDeferred = null
        pingDeferred?.second?.cancel()
        pingDeferred = null
    }

    override fun disconnect() {
        closeTransport(connectionGeneration, 1000, "Normal disconnect")
        diagnosticRing?.record(DiagnosticEvent.WebSocket.Disconnected(code = 1000, reason = "Normal disconnect"))
        synchronized(this) {
            connectionGeneration++
        }
        if (transport is com.hermes.bot.data.gateway.okhttp.OkHttpGatewayTransport) {
            transport.activeGeneration = connectionGeneration
        }
        _connectionStatus.value = ConnectionStatus.OFFLINE
    }

    private fun startTransportReader(gen: Long) {
        transportReaderJob?.cancel()
        transportReaderJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.collect { rawFrame ->
                val (frameGen, frame) = if (rawFrame is TaggedGatewayTransportFrame) {
                    Pair(rawFrame.generation, rawFrame.frame)
                } else {
                    Pair(gen, rawFrame)
                }
                if (frameGen != connectionGeneration || gen != connectionGeneration) {
                    // Stale generation frame: inert
                    return@collect
                }
                when (frame) {
                    is GatewayTransportFrame.Text -> handleIncomingText(frame.text, gen)
                    is GatewayTransportFrame.Close -> handleIncomingClose(frame.code, frame.reason, gen)
                    is GatewayTransportFrame.Error -> handleIncomingError(frame.cause, frame.handshakeStatusCode, gen)
                    is TaggedGatewayTransportFrame -> { /* unwrapped above */ }
                }
            }
        }
    }

    private fun handleIncomingText(text: String, gen: Long) {
        if (gen != connectionGeneration) return
        if (text.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) return // DoS mitigation (N-002: count UTF-8 bytes)

        val json = GatewayJson.parseOrNull(text) as? JsonObject ?: return

        // 1. Inbound event envelope: {"jsonrpc":"2.0","method":"event","params":{...}}
        val method = json["method"]?.asStringOrNull()
        if (method == "event") {
            val params = json["params"] as? JsonObject
            if (params != null) {
                handleEventFrame(params, gen)
            }
            return
        }

        // Server requests have their own identity namespace. Never consume a client RPC waiter.
        if (method != null) {
            val id = ApprovalWireDecoder.id(json["id"]) ?: return
            val params = json["params"] as? JsonObject
            val request = if (method == "approval" && json["jsonrpc"]?.asStringOrNull() == "2.0" && params != null)
                ApprovalWireDecoder.request(params, id) else null
            if (request != null && (approvalInbox.value.requests.size < 128 ||
                    approvalInbox.value.requests.any { it.runtimeId == request.runtimeId && it.requestId == request.requestId })) {
                approvalStore.receive(request)
            } else scope.launch {
                connectionMutex.withLock {
                    if (gen == connectionGeneration) transport.send(GatewayJson.serialize(JsonObject(
                        "jsonrpc" to JsonString("2.0"), "id" to JsonString(id),
                        "error" to JsonObject("code" to JsonNumber(if (method == "approval") -32602L else -32601L),
                            "message" to JsonString("Request unsupported or invalid")))))
                }
            }
            return
        }

        // 2. Heartbeat keepalive response: {"ok":true} or {"pong":true}
        if (json["ok"]?.asBooleanOrNull() == true || json["pong"]?.asBooleanOrNull() == true) {
            if (pingDeferred?.first == gen) {
                pingDeferred?.second?.complete(true)
            }
        }

        // 3. Correlate JSON-RPC response by exact ID
        val id = json["id"]?.asStringOrNull()
        if (id != null) {
            val pending = pendingRequests.remove(id) ?: return
            if (pending.generation != gen) return // Stale correlation

            val jsonrpc = json["jsonrpc"]?.asStringOrNull()
            if (jsonrpc != null && jsonrpc != "2.0") {
                val safeJsonRpc = GatewayRedactor.redactString(jsonrpc)
                pending.deferred.completeExceptionally(
                    GatewayException(
                        GatewayFailure.IncompatibleProtocol("Invalid jsonrpc version '$safeJsonRpc' (expected '2.0')")
                    )
                )
                return
            }

            val hasResult = json.map.containsKey("result")
            val hasError = json.map.containsKey("error") && json["error"] !is com.hermes.bot.data.gateway.json.JsonNull

            if ((hasResult && hasError) || (!hasResult && !hasError)) {
                pending.deferred.completeExceptionally(
                    GatewayException(
                        GatewayFailure.IncompatibleProtocol("Correlated response must contain exactly one of 'result' or 'error'")
                    )
                )
                return
            }

            if (hasError) {
                val errorObj = json["error"] as? JsonObject
                val code = errorObj?.get("code")?.asIntOrNull() ?: -32603
                val msg = GatewayRedactor.redactString(errorObj?.get("message")?.asStringOrNull() ?: "RPC Error")
                val rawData = errorObj?.get("data")?.toAny()
                val dataSummary = rawData?.let {
                    GatewayRedactor.redactAny(it)?.toString()?.let { s -> GatewayRedactor.redactString(s) }
                }
                pending.deferred.completeExceptionally(
                    GatewayException(GatewayFailure.Rpc(code, msg, dataSummary))
                )
                return
            }

            val resultVal = json["result"]
            pending.deferred.complete(resultVal ?: JsonObject())
        }
    }

    private fun handleEventFrame(params: JsonObject, gen: Long) {
        if (gen != connectionGeneration) return
        val event = parseEventFromParams(params)

        if (event is GatewayEvent.Ready) {
            if (readyDeferred?.first == gen) {
                readyDeferred?.second?.complete(event)
            }
        }

        val sid = event.sessionId
        // Validate session requirement on session-bearing events
        if (event.requiresSessionId && sid.isNullOrBlank()) {
            _historyFallbackSignals.tryEmit(
                HistoryFallbackSignal(
                    durableSessionId = null,
                    runtimeSessionId = null,
                    reason = FallbackReason.DECODE_ERROR,
                    details = "Session event missing required session_id: ${event::class.simpleName}"
                )
            )
            return
        }

        // Validate sequence positivity
        if (event.seq != null && event.seq!! <= 0L) {
            _historyFallbackSignals.tryEmit(
                HistoryFallbackSignal(
                    durableSessionId = null,
                    runtimeSessionId = sid,
                    reason = FallbackReason.DECODE_ERROR,
                    details = "Invalid non-positive sequence number ${event.seq} on event ${event::class.simpleName}"
                )
            )
            return
        }

        // If replay is currently in-flight for this session, hold live events atomically
        if (sid != null) {
            val sessionLock = sessionLocks.computeIfAbsent(sid) { Any() }
            val isHeld = synchronized(sessionLock) {
                if (replayingSessions.contains(sid)) {
                    val list = heldLiveEvents[sid]
                    list?.add(event)
                    true
                } else {
                    false
                }
            }
            if (isHeld) {
                return
            }
        }

        when (val outcome = replayReducer.evaluateLiveEvent(event)) {
            is LiveEventOutcome.Accepted -> emitEventObservably(outcome.event)
            is LiveEventOutcome.Fallback -> _historyFallbackSignals.tryEmit(outcome.signal)
            is LiveEventOutcome.Deduplicated -> { /* deduped */ }
            is LiveEventOutcome.Rejected -> { /* rejected while awaiting history rehydration */ }
        }
    }

    private fun emitEventObservably(event: GatewayEvent, durableSessionId: String? = null) {
        if (event is GatewayEvent.ApprovalCancelled) approvalStore.cancel(event.sessionId, event.requestId, event.reason)

        val sid = event.sessionId
        val seq = event.seq

        if (sid != null && seq != null && seq > 0L) {
            if (_events.subscriptionCount.value == 0) {
                // No active subscriber: queue event and DO NOT commit watermark yet
                pendingUnsubscribedEvents.add(PendingObservableEvent(event, durableSessionId))
                return
            }
        }

        val emitted = _events.tryEmit(event)
        if (emitted) {
            if (sid != null && seq != null && seq > 0L) {
                replayReducer.commitWatermark(sid, seq)
            }
        } else {
            // Slow subscriber / buffer overflow: NEVER silently drop events
            if (sid != null) {
                replayReducer.clearWatermark(sid)
                replayReducer.markAwaitingHistoryRehydration(sid)
                _historyFallbackSignals.tryEmit(
                    HistoryFallbackSignal(
                        durableSessionId = durableSessionId,
                        runtimeSessionId = sid,
                        reason = FallbackReason.OVERFLOW,
                        details = "Downstream event buffer overflow; slow subscriber caused event loss at seq ${seq ?: 0L}"
                    )
                )
            }
        }
    }

    private fun handleIncomingClose(code: Int, reason: String?, gen: Long) {
        if (gen != connectionGeneration) return
        approvalStore.reset()

        val safeReason = reason?.let { GatewayRedactor.redactString(it) }
        diagnosticRing?.record(DiagnosticEvent.WebSocket.Disconnected(code = code, reason = safeReason))

        when (code) {
            4401 -> {
                _terminalFailure.value = GatewayFailure.AuthenticationRequired("Authentication required (4401): ${safeReason ?: "Unauthorized"}")
                _connectionStatus.value = ConnectionStatus.AUTHENTICATION_REQUIRED
            }
            4403 -> {
                _terminalFailure.value = GatewayFailure.Transport("Policy rejection (4403): ${safeReason ?: "Forbidden"}", statusCode = 4403)
                _connectionStatus.value = ConnectionStatus.FAILED
            }
            1000 -> _connectionStatus.value = ConnectionStatus.OFFLINE
            else -> _connectionStatus.value = ConnectionStatus.FAILED
        }

        heartbeatJob?.cancel()
        heartbeatJob = null
        if (readyDeferred?.first == gen) readyDeferred?.second?.cancel()
        if (pingDeferred?.first == gen) pingDeferred?.second?.cancel()

        val pending = pendingRequests.values.toList()
        pendingRequests.clear()
        for (p in pending) {
            val failure = if (p.method == "prompt.submit") {
                GatewayFailure.SubmissionUnknown("Connection closed ($code) before prompt confirmation")
            } else {
                GatewayFailure.Transport("Connection closed ($code): $safeReason", statusCode = code)
            }
            p.deferred.completeExceptionally(GatewayException(failure))
        }
    }

    private fun handleIncomingError(cause: Throwable, handshakeStatusCode: Int?, gen: Long) {
        if (gen != connectionGeneration) return
        approvalStore.reset()

        val safeMsg = GatewayRedactor.redactString(cause.message ?: "Transport error")
        val causeClass = cause.javaClass.simpleName
        diagnosticRing?.record(DiagnosticEvent.WebSocket.Disconnected(code = handshakeStatusCode, reason = safeMsg))

        if (handshakeStatusCode == 403 || handshakeStatusCode == 4403) {
            _terminalFailure.value = GatewayFailure.Transport("Handshake rejected ($handshakeStatusCode)", statusCode = handshakeStatusCode)
            _connectionStatus.value = ConnectionStatus.AUTHENTICATION_REQUIRED
        } else {
            _connectionStatus.value = ConnectionStatus.FAILED
        }

        heartbeatJob?.cancel()
        heartbeatJob = null
        if (readyDeferred?.first == gen) readyDeferred?.second?.cancel()
        if (pingDeferred?.first == gen) pingDeferred?.second?.cancel()

        val pending = pendingRequests.values.toList()
        pendingRequests.clear()
        for (p in pending) {
            val failure = if (p.method == "prompt.submit") {
                GatewayFailure.SubmissionUnknown("Transport error before prompt confirmation: $safeMsg", causeClass)
            } else {
                GatewayFailure.Transport(safeMsg, causeClass, statusCode = handshakeStatusCode)
            }
            p.deferred.completeExceptionally(GatewayException(failure))
        }
    }

    private fun nextRequestId(): String {
        return "android-$connectionGeneration-${requestCounter.incrementAndGet()}"
    }

    private suspend fun sendRpc(
        method: String,
        params: Map<String, Any?>,
        allowHandshakeRpc: Boolean = false
    ): GatewayResult<JsonValue> {
        val status = _connectionStatus.value
        if (!allowHandshakeRpc && status != ConnectionStatus.CONNECTED) {
            return GatewayResult.Failure(GatewayFailure.NotConnected("Gateway is not connected (state: $status)"))
        }
        if (status == ConnectionStatus.OFFLINE) {
            return GatewayResult.Failure(GatewayFailure.NotConnected())
        }

        val gen = connectionGeneration
        val id = nextRequestId()
        val requestJson = JsonObject(
            "jsonrpc" to JsonString("2.0"),
            "id" to JsonString(id),
            "method" to JsonString(method),
            "params" to GatewayJson.fromAny(params)
        )
        val rawWire = GatewayJson.serialize(requestJson)

        val deferred = CompletableDeferred<JsonValue>()
        pendingRequests[id] = PendingRpc(method, deferred, gen)

        val sent = try {
            transport.send(rawWire)
        } catch (e: Exception) {
            pendingRequests.remove(id)
            val safeMsg = GatewayRedactor.redactString(e.message ?: "Failed sending RPC frame")
            val failure = if (method == "prompt.submit") {
                GatewayFailure.SubmissionUnknown("Failed writing prompt frame to socket: $safeMsg", e.javaClass.simpleName)
            } else {
                GatewayFailure.Transport("Failed sending RPC frame: $safeMsg", e.javaClass.simpleName)
            }
            return GatewayResult.Failure(failure)
        }

        if (!sent) {
            pendingRequests.remove(id)
            val failure = if (method == "prompt.submit") {
                GatewayFailure.SubmissionUnknown("Failed sending prompt frame")
            } else {
                GatewayFailure.Transport("Failed sending RPC frame")
            }
            return GatewayResult.Failure(failure)
        }

        return try {
            val response = withTimeoutOrNull(rpcTimeoutMs) {
                deferred.await()
            }
            if (response == null) {
                pendingRequests.remove(id)
                val failure = if (method == "prompt.submit") {
                    GatewayFailure.SubmissionUnknown("Timed out awaiting prompt confirmation")
                } else {
                    GatewayFailure.Transport("RPC call '$method' timed out")
                }
                GatewayResult.Failure(failure)
            } else {
                GatewayResult.Success(response)
            }
        } catch (e: GatewayException) {
            GatewayResult.Failure(e.failure)
        } catch (e: Exception) {
            val safeMsg = GatewayRedactor.redactString(e.message ?: "RPC execution exception")
            val failure = if (method == "prompt.submit") {
                GatewayFailure.SubmissionUnknown("Exception awaiting prompt confirmation: $safeMsg", e.javaClass.simpleName)
            } else {
                GatewayFailure.Transport("RPC execution exception: $safeMsg", e.javaClass.simpleName)
            }
            GatewayResult.Failure(failure)
        }
    }

    override suspend fun listSessions(limit: Int): GatewayResult<SessionListResult> {
        return when (val res = sendRpc("session.list", mapOf("limit" to limit))) {
            is GatewayResult.Failure -> res
            is GatewayResult.Success -> {
                val sessionsArray = res.value["sessions"]?.asArrayOrNull()
                    ?: return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("session.list missing 'sessions' array"))
                val summaries = sessionsArray.list.mapNotNull { item ->
                    val obj = item.asObjectOrNull() ?: return@mapNotNull null
                    val id = obj["id"]?.asStringOrNull() ?: return@mapNotNull null
                    SessionSummary(
                        id = id,
                        title = obj["title"]?.asStringOrNull(),
                        preview = obj["preview"]?.asStringOrNull()?.let { GatewayRedactor.redactString(it) },
                        startedAt = obj["started_at"]?.asDoubleOrNull(),
                        messageCount = obj["message_count"]?.asIntOrNull() ?: 0,
                        source = obj["source"]?.asStringOrNull(),
                        lastActive = obj["last_active"]?.asDoubleOrNull()?.takeIf { it.isFinite() }
                    )
                }
                GatewayResult.Success(SessionListResult(summaries))
            }
        }
    }

    override suspend fun listActiveSessions(): GatewayResult<ActiveSessionListResult> {
        return when (val res = sendRpc("session.active_list", emptyMap())) {
            is GatewayResult.Failure -> res
            is GatewayResult.Success -> {
                val sessionsArray = res.value["sessions"]?.asArrayOrNull()
                    ?: return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("session.active_list missing 'sessions' array"))
                val items = sessionsArray.list.mapNotNull { item ->
                    val obj = item.asObjectOrNull() ?: return@mapNotNull null
                    val id = obj["id"]?.asStringOrNull() ?: return@mapNotNull null
                    ActiveSessionItem(
                        id = id,
                        sessionKey = obj["session_key"]?.asStringOrNull(),
                        model = obj["model"]?.asStringOrNull(),
                        messageCount = obj["message_count"]?.asIntOrNull() ?: 0,
                        status = obj["status"]?.asStringOrNull(),
                        lastActive = obj["last_active"]?.asDoubleOrNull()
                    )
                }
                GatewayResult.Success(ActiveSessionListResult(items))
            }
        }
    }

    override suspend fun createSession(
        title: String?,
        source: String,
        closeOnDisconnect: Boolean,
        profile: String?,
        model: String?,
        reasoningEffort: String?
    ): GatewayResult<SessionCreateResult> {
        val params = mutableMapOf<String, Any?>(
            "source" to source,
            "close_on_disconnect" to closeOnDisconnect
        )
        if (title != null) params["title"] = title
        // Include every non-blank selected profile, including literal `default`.
        // Null and blank values remain legacy unscoped calls.
        val normalizedProfile = profile?.trim()
        if (!normalizedProfile.isNullOrBlank()) {
            params["profile"] = normalizedProfile
        }
        // Explicit per-session create overrides remain supported. Include
        // `model`/`reasoning_effort` only when non-blank; omitted values inherit
        // the host profile default. Values are sent verbatim, and `provider` is
        // never sent (SEC-004); `session.resume` never sends overrides.
        val normalizedModel = model?.trim()
        if (!normalizedModel.isNullOrBlank()) {
            params["model"] = normalizedModel
        }
        val normalizedEffort = reasoningEffort?.trim()
        if (!normalizedEffort.isNullOrBlank()) {
            params["reasoning_effort"] = normalizedEffort
        }

        return when (val res = sendRpc("session.create", params)) {
            is GatewayResult.Failure -> res
            is GatewayResult.Success -> {
                val runtimeSid = res.value["session_id"]?.asStringOrNull()
                    ?: return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("session.create missing 'session_id'"))
                val storedSid = res.value["stored_session_id"]?.asStringOrNull() ?: runtimeSid
                val count = res.value["message_count"]?.asIntOrNull() ?: 0
                val messages = parseMessages(res.value["messages"])
                val info = GatewayRedactor.redactMap(res.value["info"]?.toAny().asStringMap())

                GatewayResult.Success(
                    SessionCreateResult(
                        runtimeSessionId = runtimeSid,
                        durableSessionId = storedSid,
                        messageCount = count,
                        messages = messages,
                        info = info
                    )
                )
            }
        }
    }

    override suspend fun resumeSession(sessionId: String, profile: String?): GatewayResult<SessionResumeResult> {
        // Build params: always include session_id; merge `profile` only when it represents
        // a non-default, non-blank selection (case-insensitive "default" is omitted).
        val params = mutableMapOf<String, Any?>("session_id" to sessionId)
        val normalizedProfile = profile?.trim()
        if (!normalizedProfile.isNullOrBlank() && normalizedProfile.lowercase() != "default") {
            params["profile"] = normalizedProfile
        }

        return resumeWithParams(sessionId, params)
    }

    override fun isScopedConnectionCurrent(origin: CanonicalOrigin, generation: Long): Boolean =
        generation == connectionGeneration && connectedOrigin == origin &&
            _connectionStatus.value == ConnectionStatus.CONNECTED

    override suspend fun resumeSessionScoped(
        sessionId: String, profile: String, origin: CanonicalOrigin, generation: Long
    ): GatewayResult<SessionResumeResult> {
        if (profile.isBlank() || profile != profile.trim() || !isScopedConnectionCurrent(origin, generation))
            return GatewayResult.Failure(GatewayFailure.NotConnected("Scoped resume unavailable"))
        val result = resumeWithParams(sessionId, mapOf("session_id" to sessionId, "profile" to profile))
        return if (isScopedConnectionCurrent(origin, generation)) result
        else GatewayResult.Failure(GatewayFailure.NotConnected("Scoped resume stale"))
    }

    private suspend fun resumeWithParams(sessionId: String, params: Map<String, Any?>): GatewayResult<SessionResumeResult> {
        val approvalRevision = approvalInbox.value.revision
        val approvalGeneration = connectionGeneration
        return when (val res = sendRpc("session.resume", params)) {
            is GatewayResult.Failure -> res
            is GatewayResult.Success -> {
                // Certified Host Incompatibility #2: parse dual payload variants (unpersisted vs persisted)
                val runtimeSid = res.value["session_id"]?.asStringOrNull() ?: sessionId
                if (approvalGeneration == connectionGeneration && approvalRevision == approvalInbox.value.revision &&
                    ApprovalWireDecoder.id(res.value["session_id"]) != null) {
                    restoreApprovals(res.value as? JsonObject ?: JsonObject(), runtimeSid)
                }
                val durableSid = res.value["stored_session_id"]?.asStringOrNull()
                    ?: res.value["session_key"]?.asStringOrNull()
                    ?: res.value["resumed"]?.asStringOrNull()
                    ?: sessionId
                val count = res.value["message_count"]?.asIntOrNull() ?: 0
                val messages = parseMessages(res.value["messages"])
                val info = GatewayRedactor.redactMap(res.value["info"]?.toAny().asStringMap())
                val inflight = GatewayRedactor.redactAny(res.value["inflight"]?.toAny())
                val running = res.value["running"]?.asBooleanOrNull() ?: false
                val startedAt = res.value["started_at"]?.asDoubleOrNull()
                val status = res.value["status"]?.asStringOrNull() ?: "idle"
                // CHAT-004 TASK-002: host truncation signal, parsed only (never invented).
                val messagesOmitted = res.value["messages_omitted"]?.asBooleanOrNull() ?: false
                val unpersistedDraft = isEmptyUnpersistedDraft(res.value)

                GatewayResult.Success(
                    SessionResumeResult(
                        runtimeSessionId = runtimeSid,
                        durableSessionId = durableSid,
                        messageCount = count,
                        messages = messages,
                        info = info,
                        inflight = inflight,
                        running = running,
                        startedAt = startedAt,
                        status = status,
                        messagesOmitted = messagesOmitted,
                        observedRuntimeSessionId = observedIdentity(res.value["session_id"]),
                        observedDurableSessionId = listOf("stored_session_id", "session_key", "resumed")
                            .mapNotNull { observedIdentity(res.value[it]) }.distinct().singleOrNull(),
                        activeProjection = ActiveProjectionDecoder().decode(res.value),
                        isUnpersistedDraft = unpersistedDraft,
                        taskSnapshot = TaskSnapshotDecoder.decode(res.value["todo_state"]),
                        activityEvidence = when {
                            unpersistedDraft -> ResumeActivityEvidence.NON_RUNNING
                            res.value["running"]?.asBooleanOrNull() == true -> ResumeActivityEvidence.RUNNING
                            res.value["running"]?.asBooleanOrNull() == false &&
                                res.value["status"]?.asStringOrNull() == "idle" -> ResumeActivityEvidence.NON_RUNNING
                            else -> ResumeActivityEvidence.UNKNOWN
                        }
                    )
                )
            }
        }
    }

    override suspend fun submitEditScoped(sessionId: String, text: String, rowId: Long,
        origin: CanonicalOrigin, generation: Long): GatewayResult<PromptSubmitResult> {
        if (sessionId.isBlank() || rowId <= 0 || !MessageEditPolicy.plainText(text))
            return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Invalid message edit"))
        return when (val result = sendScopedReadRpc("prompt.submit", mapOf(
            "session_id" to sessionId, "text" to text, "truncate_before_row_id" to rowId,
            "confirm_truncate" to true, "confirm_empty_truncate" to true
        ), origin, generation, write = true)) {
            is GatewayResult.Failure -> result
            is GatewayResult.Success -> if (result.value["status"]?.asStringOrNull() == "streaming")
                GatewayResult.Success(PromptSubmitResult("streaming", result.value["user_row_id"]?.asLongOrNull()?.takeIf { it > 0 }))
            else GatewayResult.Failure(GatewayFailure.SubmissionUnknown("Edit admission unknown"))
        }
    }

    override suspend fun interruptEditScoped(sessionId: String, origin: CanonicalOrigin,
        generation: Long): GatewayResult<InterruptSessionResult> =
        when (val result = sendScopedReadRpc("session.interrupt", mapOf("session_id" to sessionId), origin, generation, write = true)) {
            is GatewayResult.Failure -> result
            is GatewayResult.Success -> GatewayResult.Success(InterruptSessionResult(result.value["ok"]?.asBooleanOrNull() ?: true))
        }

    override suspend fun submitPrompt(sessionId: String, text: String): GatewayResult<PromptSubmitResult> {
        return when (val res = sendRpc("prompt.submit", mapOf("session_id" to sessionId, "text" to text))) {
            is GatewayResult.Failure -> res
            is GatewayResult.Success -> {
                // F-006: Only "streaming" is confirmed admission; any other or missing status maps to SubmissionUnknown
                val status = res.value["status"]?.asStringOrNull()
                if (status == "streaming") {
                    GatewayResult.Success(PromptSubmitResult("streaming", res.value["user_row_id"]?.asLongOrNull()?.takeIf { it > 0 }))
                } else {
                    val safeStatus = status?.let { GatewayRedactor.redactString(it) } ?: "missing"
                    GatewayResult.Failure(
                        GatewayFailure.SubmissionUnknown("Unconfirmed prompt admission status: $safeStatus (expected 'streaming')")
                    )
                }
            }
        }
    }

    override suspend fun attachImageBytes(
        sessionId: String,
        contentBase64: String,
        filename: String?,
        ext: String?
    ): GatewayResult<AttachImageResult> {
        val params = mutableMapOf<String, Any?>(
            "session_id" to sessionId,
            "content_base64" to contentBase64
        )
        if (filename != null) params["filename"] = filename
        if (ext != null) params["ext"] = ext
        return when (val res = sendRpc("image.attach_bytes", params)) {
            is GatewayResult.Failure -> GatewayResult.Failure(mapAttachmentFailure(res.failure))
            is GatewayResult.Success -> {
                val path = res.value["path"]?.asStringOrNull()
                val count = res.value["count"]?.asIntOrNull()
                val text = res.value["text"]?.asStringOrNull()
                val bytes = res.value["bytes"]?.asIntOrNull()
                if (res.value["attached"]?.asBooleanOrNull() != true || path.isNullOrBlank() ||
                    count == null || count < 0 || text == null || bytes == null || bytes < 0
                ) {
                    GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Invalid image.attach_bytes result"))
                } else {
                    GatewayResult.Success(AttachImageResult(path, count, text, bytes))
                }
            }
        }
    }

    override suspend fun attachFile(sessionId: String, dataUrl: String, name: String): GatewayResult<AttachFileResult> {
        return when (val res = sendRpc("file.attach", mapOf(
            "session_id" to sessionId,
            "data_url" to dataUrl,
            "name" to name
        ))) {
            is GatewayResult.Failure -> GatewayResult.Failure(mapAttachmentFailure(res.failure))
            is GatewayResult.Success -> {
                val resultName = res.value["name"]?.asStringOrNull()
                val path = res.value["path"]?.asStringOrNull()
                val refPath = res.value["ref_path"]?.asStringOrNull()
                val refText = res.value["ref_text"]?.asStringOrNull()
                val uploaded = res.value["uploaded"]?.asBooleanOrNull()
                if (res.value["attached"]?.asBooleanOrNull() != true || resultName.isNullOrBlank() ||
                    path.isNullOrBlank() || refPath.isNullOrBlank() || refText.isNullOrBlank() || uploaded == null
                ) {
                    GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Invalid file.attach result"))
                } else {
                    GatewayResult.Success(AttachFileResult(resultName, path, refPath, refText, uploaded))
                }
            }
        }
    }

    override suspend fun detachImage(sessionId: String, path: String): GatewayResult<DetachImageResult> {
        return when (val res = sendRpc("image.detach", mapOf("session_id" to sessionId, "path" to path))) {
            is GatewayResult.Failure -> GatewayResult.Failure(mapAttachmentFailure(res.failure))
            is GatewayResult.Success -> {
                val detached = res.value["detached"]?.asBooleanOrNull()
                val count = res.value["count"]?.asIntOrNull()
                if (detached == null || count == null || count < 0) {
                    GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Invalid image.detach result"))
                } else {
                    GatewayResult.Success(DetachImageResult(detached, count))
                }
            }
        }
    }

    private fun mapAttachmentFailure(failure: GatewayFailure): GatewayFailure {
        if (failure !is GatewayFailure.Rpc) return failure
        val safeMessage = when (failure.code) {
            4015 -> "Attachment request is missing required data"
            4016 -> "Image type is unsupported"
            4017 -> "Image data is invalid"
            4018 -> "Image exceeds the host size limit"
            5027 -> "Host could not store the image"
            5028 -> "Host could not store the file"
            else -> return failure // Preserve the existing unknown-method path.
        }
        return GatewayFailure.Rpc(failure.code, safeMessage, terminal = failure.terminal)
    }

    override suspend fun interruptSession(sessionId: String): GatewayResult<InterruptSessionResult> {
        return when (val res = sendRpc("session.interrupt", mapOf("session_id" to sessionId))) {
            is GatewayResult.Failure -> res
            is GatewayResult.Success -> {
                // A correlated JSON-RPC result (no error) is the host ack for
                // the interrupt. An explicit ok:false ack is preserved verbatim;
                // a bare ack without the field defaults to delivered (true).
                val ok = res.value["ok"]?.asBooleanOrNull() ?: true
                GatewayResult.Success(InterruptSessionResult(ok = ok))
            }
        }
    }

    override suspend fun getReasoningDefault(profile: String?): GatewayResult<ReasoningDefaultResult> {
        // CHAT-011 S1 (SEC-001): the key is HARD-CODED — this method has no key
        // parameter, so no call site can ever request another config key (the
        // `full` key returns the entire config with expanded secrets, host fact
        // H3, and is structurally unreachable from this adapter).
        val params = mutableMapOf<String, Any?>("key" to "reasoning")
        // Include `profile` only for a non-blank, non-default selection — the
        // established omit-when-blank convention (CON-001, create/resume block).
        val normalizedProfile = profile?.trim()
        if (!normalizedProfile.isNullOrBlank() && normalizedProfile.lowercase() != "default") {
            params["profile"] = normalizedProfile
        }

        return when (val res = sendRpc("config.get", params)) {
            is GatewayResult.Failure -> res
            is GatewayResult.Success -> {
                // SEC-002: boundary decode parity — every consumed RPC result
                // map passes through GatewayRedactor.redactMap exactly like the
                // create/resume info maps. Keys `value`/`display` are not
                // credential keys, so they survive redaction unharmed.
                val redacted = GatewayRedactor.redactMap(res.value.toAny().asStringMap())
                // Consume `value` only (H1); `display` is TUI reasoning-trace
                // visibility, deliberately ignored (open decision 5). A blank
                // value maps to a null effort — never a fabricated default.
                val effort = (redacted["value"] as? String)?.takeIf { it.isNotBlank() }
                GatewayResult.Success(ReasoningDefaultResult(effort = effort))
            }
        }
    }

    override suspend fun getProfileReasoningDefault(
        profile: String,
        expectedOrigin: CanonicalOrigin,
        expectedGeneration: Long
    ): GatewayResult<ReasoningDefaultResult> {
        if (!isSafeCapturedProfile(profile) || !isScopedConnectionCurrent(expectedOrigin, expectedGeneration)) {
            return GatewayResult.Failure(GatewayFailure.NotConnected("Profile reasoning unavailable"))
        }

        return when (val result = sendScopedReadRpc(
            "config.get", mapOf("key" to "reasoning", "profile" to profile), expectedOrigin, expectedGeneration
        )) {
            is GatewayResult.Failure -> result
            is GatewayResult.Success -> {
                val redacted = GatewayRedactor.redactMap(result.value.toAny().asStringMap())
                val effort = (redacted["value"] as? String)?.takeIf { it.isNotBlank() }
                GatewayResult.Success(ReasoningDefaultResult(effort))
            }
        }
    }

    // Reuse the reasoning read's correlation, timeout and cancellation ownership.
    // Connect cannot replace the transport during admission/send; never hold the lock while awaiting a response.
    private suspend fun sendScopedReadRpc(
        method: String, params: Map<String, Any>, expectedOrigin: CanonicalOrigin, expectedGeneration: Long,
        timeoutMs: Long = rpcTimeoutMs, write: Boolean = false
    ): GatewayResult<JsonValue> {
        currentCoroutineContext().ensureActive()
        if (!isScopedConnectionCurrent(expectedOrigin, expectedGeneration)) {
            return GatewayResult.Failure(GatewayFailure.NotConnected("Profile reasoning unavailable"))
        }
        val id = nextRequestId()
        val request = JsonObject(
            "jsonrpc" to JsonString("2.0"), "id" to JsonString(id),
            "method" to JsonString(method), "params" to GatewayJson.fromAny(params)
        )
        val deferred = CompletableDeferred<JsonValue>()
        pendingRequests[id] = PendingRpc(method, deferred, expectedGeneration)
        return try {
            val sent = connectionMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!isScopedConnectionCurrent(expectedOrigin, expectedGeneration)) {
                    return GatewayResult.Failure(GatewayFailure.NotConnected("Profile reasoning unavailable"))
                }
                try {
                    transport.send(GatewayJson.serialize(request))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
            if (!sent) {
                GatewayResult.Failure(GatewayFailure.Transport("Profile reasoning transport failure"))
            } else {
                val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
                currentCoroutineContext().ensureActive()
                if (response == null) {
                    GatewayResult.Failure(GatewayFailure.Transport("Profile reasoning RPC timed out"))
                } else if (!isScopedConnectionCurrent(expectedOrigin, expectedGeneration)) {
                    GatewayResult.Failure(if (write) GatewayFailure.SubmissionUnknown("Control outcome unknown") else GatewayFailure.NotConnected("Profile reasoning response stale"))
                } else {
                    GatewayResult.Success(response)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GatewayException) {
            GatewayResult.Failure(error.failure)
        } catch (_: Exception) {
            GatewayResult.Failure(GatewayFailure.Transport("Profile reasoning RPC failed"))
        } finally {
            pendingRequests.remove(id)
        }
    }

    private fun restoreApprovals(root: JsonObject, runtime: String) {
        val open = root["open_requests"] as? JsonArray
        if (open != null && open.list.size <= 128) for (entry in open.list) {
            val obj = entry as? JsonObject ?: continue
            if (obj["method"]?.asStringOrNull() != "approval") continue
            val params = obj["params"] as? JsonObject ?: continue
            val id = ApprovalWireDecoder.id(obj["id"]) ?: continue
            ApprovalWireDecoder.request(params, id, runtime)?.let { approvalStore.receive(it) }
        }
        (root["pending_approval"] as? JsonObject)?.let { params ->
            ApprovalWireDecoder.request(params, expectedRuntime = runtime)?.let { approvalStore.receive(it) }
        }
    }

    override suspend fun refreshApprovals(runtimeId: String, origin: CanonicalOrigin, generation: Long): GatewayResult<Boolean> {
        if (runtimeId.isBlank() || !isScopedConnectionCurrent(origin, generation))
            return GatewayResult.Failure(GatewayFailure.NotConnected("Approvals unavailable"))
        val revision = approvalInbox.value.revision
        val result = sendScopedReadRpc("approval.pending", mapOf("session_id" to runtimeId), origin, generation)
        if (result is GatewayResult.Failure) return result
        result as GatewayResult.Success
        val requests = ApprovalWireDecoder.pending(result.value, runtimeId)
            ?: return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Approval snapshot unavailable"))
        val applied = approvalStore.reconcile(runtimeId, requests, revision)
        if (applied) for (request in requests) {
            if (!isScopedConnectionCurrent(origin, generation)) break
            // Best effort delivery acknowledgement; it never authorizes an approval.
            sendScopedReadRpc("approval.received", mapOf("session_id" to runtimeId, "request_id" to request.requestId), origin, generation)
        }
        return GatewayResult.Success(applied)
    }

    override suspend fun answerApproval(request: ApprovalRequest, choice: ApprovalChoice, origin: CanonicalOrigin,
        generation: Long): ApprovalOutcome {
        val current = approvalInbox.value.requests.find { it.runtimeId == request.runtimeId && it.requestId == request.requestId }
        if (!isScopedConnectionCurrent(origin, generation) || current != request || choice !in current.choices)
            return ApprovalOutcome.UNAVAILABLE
        // Invalidate reads already in flight before the decision can leave this socket.
        approvalStore.changed()
        if (request.serverRequestId != null) {
            return try {
                val sent = connectionMutex.withLock {
                    if (!isScopedConnectionCurrent(origin, generation) ||
                        approvalInbox.value.requests.none { it == request }) return ApprovalOutcome.UNAVAILABLE
                    transport.send(GatewayJson.serialize(JsonObject("jsonrpc" to JsonString("2.0"),
                        "id" to JsonString(request.serverRequestId),
                        "result" to JsonObject("choice" to JsonString(ApprovalWireDecoder.choice(choice))))))
                }
                if (sent && isScopedConnectionCurrent(origin, generation)) ApprovalOutcome.SENT else ApprovalOutcome.UNKNOWN
            } catch (e: CancellationException) { throw e } catch (_: Exception) { ApprovalOutcome.UNKNOWN }
        }
        val result = sendScopedReadRpc("approval.respond", mapOf("session_id" to request.runtimeId,
            "request_id" to request.requestId, "choice" to ApprovalWireDecoder.choice(choice), "all" to false),
            origin, generation, timeoutMs = 330_000L, write = true)
        return when (result) {
            is GatewayResult.Success -> if (result.value["resolved"]?.asIntOrNull() != null) ApprovalOutcome.SENT else ApprovalOutcome.UNKNOWN
            is GatewayResult.Failure -> if (result.failure is GatewayFailure.Rpc) ApprovalOutcome.REJECTED else ApprovalOutcome.UNKNOWN
        }
    }

    override suspend fun listSubagents(runtimeId: String, origin: CanonicalOrigin, generation: Long): GatewayResult<com.hermes.bot.data.subagent.SubagentRoster> =
        when (val result = sendScopedReadRpc("subagent.list", mapOf("session_id" to runtimeId), origin, generation)) {
            is GatewayResult.Failure -> result
            is GatewayResult.Success -> SubagentWireDecoder.roster(result.value)?.let { GatewayResult.Success(it) }
                ?: GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Subagent roster unavailable"))
        }

    override suspend fun subagentTail(runtimeId: String, childId: String, origin: CanonicalOrigin, generation: Long): GatewayResult<com.hermes.bot.data.subagent.SubagentTail> =
        when (val result = sendScopedReadRpc("subagent.tail", mapOf("session_id" to runtimeId, "subagent_id" to childId), origin, generation)) {
            is GatewayResult.Failure -> result
            is GatewayResult.Success -> if (result.value["subagent_id"]?.asStringOrNull() == childId)
                SubagentWireDecoder.tail(result.value)?.let { GatewayResult.Success(it) }
                    ?: GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Transcript unavailable"))
                else GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Transcript unavailable"))
        }

    override suspend fun controlSubagent(runtimeId: String, childId: String, text: String?, origin: CanonicalOrigin, generation: Long): com.hermes.bot.data.subagent.SubagentControl {
        if (runtimeId.isBlank() || childId.isBlank() || (text != null && text.isBlank())) return com.hermes.bot.data.subagent.SubagentControl.UNAVAILABLE
        // Reuse single-send scoped admission/correlation; this helper never retries a write.
        val params = mutableMapOf<String, Any>("session_id" to runtimeId, "subagent_id" to childId)
        if (text != null) params["text"] = text
        val result = sendScopedReadRpc(if (text == null) "subagent.interrupt" else "subagent.steer", params, origin, generation, write = true)
        return when (result) {
            is GatewayResult.Failure -> when (result.failure) {
                is GatewayFailure.Rpc -> if (result.failure.code == -32601) com.hermes.bot.data.subagent.SubagentControl.UNAVAILABLE else com.hermes.bot.data.subagent.SubagentControl.REJECTED
                is GatewayFailure.NotConnected, is GatewayFailure.IncompatibleProtocol -> com.hermes.bot.data.subagent.SubagentControl.UNAVAILABLE
                else -> com.hermes.bot.data.subagent.SubagentControl.UNKNOWN
            }
            is GatewayResult.Success -> when {
                result.value["subagent_id"]?.asStringOrNull() != childId -> com.hermes.bot.data.subagent.SubagentControl.UNKNOWN
                text != null && result.value["status"]?.asStringOrNull() == "queued" -> com.hermes.bot.data.subagent.SubagentControl.QUEUED
                text != null && result.value["status"]?.asStringOrNull() == "rejected" -> com.hermes.bot.data.subagent.SubagentControl.REJECTED
                text == null && result.value["found"]?.asBooleanOrNull() == true -> com.hermes.bot.data.subagent.SubagentControl.STOP_REQUESTED
                text == null && result.value["found"]?.asBooleanOrNull() == false -> com.hermes.bot.data.subagent.SubagentControl.REJECTED
                else -> com.hermes.bot.data.subagent.SubagentControl.UNKNOWN
            }
        }
    }

    override suspend fun sessionContextBreakdown(
        runtimeId: String, profile: String, origin: CanonicalOrigin, generation: Long
    ): GatewayResult<com.hermes.bot.data.context.ContextBreakdown> {
        if (runtimeId.isBlank() || profile.isBlank()) return slashInvalid()
        return decodeSlash(sendScopedReadRpc("session.context_breakdown",
            mapOf("session_id" to runtimeId, "profile" to profile), origin, generation), ContextUsageWireDecoder::breakdown)
    }

    override suspend fun sessionUsage(
        runtimeId: String, profile: String, origin: CanonicalOrigin, generation: Long
    ): GatewayResult<com.hermes.bot.data.context.SessionUsage> {
        if (runtimeId.isBlank() || profile.isBlank()) return slashInvalid()
        return decodeSlash(sendScopedReadRpc("session.usage",
            mapOf("session_id" to runtimeId, "profile" to profile), origin, generation), ContextUsageWireDecoder::usage)
    }

    override suspend fun slashCatalog(runtimeId: String, origin: CanonicalOrigin, generation: Long): GatewayResult<com.hermes.bot.data.commands.SlashCatalog> =
        decodeSlash(sendScopedReadRpc("commands.catalog", mapOf("session_id" to runtimeId), origin, generation), SlashWireDecoder::catalog)

    override suspend fun slashComplete(runtimeId: String, text: String, origin: CanonicalOrigin, generation: Long): GatewayResult<com.hermes.bot.data.commands.SlashCompletion> {
        if (com.hermes.bot.data.commands.SlashCommandPolicy.query(text) == null) return slashInvalid()
        return decodeSlash(sendScopedReadRpc("complete.slash", mapOf("session_id" to runtimeId, "text" to text), origin, generation), SlashWireDecoder::completion)
    }

    override suspend fun slashExecute(runtimeId: String, command: com.hermes.bot.data.commands.SlashInvocation, origin: CanonicalOrigin, generation: Long): GatewayResult<com.hermes.bot.data.commands.SlashDirective> {
        val parsed = com.hermes.bot.data.commands.SlashCommandPolicy.parse(command.text)
        if (runtimeId.isBlank() || parsed?.name != command.name || parsed.argument != command.argument) return slashInvalid()
        if (command.name == "yolo") {
            val value = when (command.argument) { "on" -> "1"; "off" -> "0"; else -> return slashInvalid() }
            // Explicit session target/scope: never reach the backend's global fallback.
            return decodeSlash(sendScopedReadRpc("config.set", mapOf(
                "session_id" to runtimeId, "key" to "yolo", "scope" to "session", "value" to value
            ), origin, generation)) { root ->
                if (root["key"]?.asStringOrNull() == "yolo" &&
                    root["scope"]?.asStringOrNull() == "session" &&
                    root["value"]?.asStringOrNull() == value) com.hermes.bot.data.commands.SlashDirective.YoloChanged()
                else null
            }
        }
        val params = mutableMapOf<String, Any>("session_id" to runtimeId)
        val method = when (command.name) {
            "status" -> "session.status"
            "title" -> if (command.argument.isNotBlank()) { params["title"] = command.argument; "session.title" } else "slash.exec"
            "compress" -> { if (command.argument.isNotBlank()) params["focus_topic"] = command.argument; "session.compress" }
            else -> "slash.exec"
        }
        if (method == "slash.exec") params["command"] = command.text.drop(1)
        var response = sendScopedReadRpc(method, params, origin, generation, if (method == "session.compress") 660_000L else rpcTimeoutMs)
        // A definite absent handler is safe to route elsewhere. Transport ambiguity is never retried.
        if (method == "slash.exec" && response is GatewayResult.Failure &&
            (response.failure as? GatewayFailure.Rpc)?.code == -32601) {
            response = sendScopedReadRpc("command.dispatch", mapOf("session_id" to runtimeId, "name" to command.name, "arg" to command.argument), origin, generation)
        }
        return when (method) {
            "session.compress" -> decodeSlash(response, SlashWireDecoder::compressed)
            "session.title" -> decodeSlash(response) { root ->
                if (root["title"]?.asStringOrNull() != null) com.hermes.bot.data.commands.SlashDirective.Output("Session title updated.") else null
            }
            else -> decodeSlash(response, SlashWireDecoder::directive)
        }
    }

    private fun <T> slashInvalid(): GatewayResult<T> = GatewayResult.Failure(GatewayFailure.Transport("Invalid slash command response"))
    private fun <T> decodeSlash(response: GatewayResult<JsonValue>, decode: (JsonValue) -> T?): GatewayResult<T> = when (response) {
        is GatewayResult.Failure -> response
        is GatewayResult.Success -> decode(response.value)?.let { GatewayResult.Success(it) } ?: slashInvalid()
    }

    override suspend fun listBotProfiles(
        expectedOrigin: CanonicalOrigin, expectedGeneration: Long
    ): GatewayResult<List<BotProfile>> {
        val response = sendScopedReadRpc("profiles.list", mapOf("include_sessions" to false), expectedOrigin, expectedGeneration)
        if (response is GatewayResult.Failure) {
            return GatewayResult.Failure(GatewayFailure.Transport("Bots unavailable"))
        }
        val malformed = GatewayResult.Failure(GatewayFailure.Transport("Invalid bot roster"))
        val root = (response as GatewayResult.Success).value as? JsonObject ?: return malformed
        val profiles = root["profiles"] as? JsonArray ?: return malformed
        val names = mutableSetOf<String>()
        val rows = profiles.list.map { value ->
            val row = value as? JsonObject ?: return malformed
            val name = row["name"]?.asStringOrNull()?.takeIf { it.isNotBlank() } ?: return malformed
            if (!names.add(name)) return malformed
            val ui = row["ui_meta"] as? JsonObject
            val bots = ui?.get("hermes-bots") as? JsonObject
            val display = listOf(bots?.get("title")?.asStringOrNull(), row["display_name"]?.asStringOrNull(), name)
                .firstNotNullOf { it?.trim()?.takeIf(String::isNotBlank) }
            BotProfile(name, display, row["has_avatar"]?.toAny() == true)
        }
        return GatewayResult.Success(rows)
    }

    override suspend fun getBotAvatar(
        name: String, expectedOrigin: CanonicalOrigin, expectedGeneration: Long
    ): BotAvatar? {
        if (name.isBlank()) return null
        val response = sendScopedReadRpc("profiles.get_asset", mapOf("name" to name, "asset" to "avatar"), expectedOrigin, expectedGeneration)
        val root = (response as? GatewayResult.Success)?.value as? JsonObject ?: return null
        if (root["found"]?.toAny() != true) return null
        val data = root["data"]?.asStringOrNull() ?: return null
        val declaredMime = root["mime"]?.asStringOrNull()
        val mime: String
        val encoded: String
        if (data.startsWith("data:")) {
            val comma = data.indexOf(',')
            if (comma < 0) return null
            val header = data.substring(5, comma)
            if (!header.endsWith(";base64")) return null
            mime = header.removeSuffix(";base64")
            if (declaredMime != null && declaredMime != mime) return null
            encoded = data.substring(comma + 1)
        } else {
            mime = declaredMime ?: return null
            encoded = data
        }
        if (mime !in setOf("image/png", "image/jpeg", "image/webp") || encoded.length > 2_666_668) return null
        val bytes = try { java.util.Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
        if (bytes.isEmpty() || bytes.size > 2_000_000) return null
        return BotAvatar(bytes, mime)
    }

    private fun isSafeCapturedProfile(profile: String): Boolean =
        profile.isNotBlank() && profile == profile.trim() && profile.length <= 80 &&
            profile.none { it.isISOControl() }

    private fun screenLease(value: JsonValue?): ScreenLease? {
        val root = value as? JsonObject ?: return null
        val holder = when (root["holder"]?.asStringOrNull()) {
            "agent" -> ScreenLease.Holder.BOT
            "human" -> ScreenLease.Holder.HUMAN
            else -> return null
        }
        val epoch = root["epoch"]?.asLongOrNull()?.takeIf { it >= 0 } ?: return null
        val hash = root["viewer_hash"]?.asStringOrNull()?.takeIf { it.matches(Regex("[0-9a-f]{12}")) }
        return ScreenLease(holder, hash, epoch)
    }

    private fun screenStatusValue(value: JsonValue?): ScreenStatus? {
        val root = value as? JsonObject ?: return null
        return ScreenStatus(
            supported = root["supported"]?.asBooleanOrNull() ?: return null,
            installed = root["installed"]?.asBooleanOrNull() ?: return null,
            running = root["running"]?.asBooleanOrNull() ?: return null,
            blocker = root["blocker"]?.asStringOrNull()?.take(160),
            geometry = root["geometry"]?.asStringOrNull()?.take(40) ?: return null,
            lease = screenLease(root["lease"]) ?: return null
        )
    }

    private fun <T> screenInvalid(): GatewayResult<T> = GatewayResult.Failure(GatewayFailure.Transport("Invalid Bot Screen response"))

    private suspend fun screenRpc(method: String, profile: String, origin: CanonicalOrigin, generation: Long,
                                  extra: Map<String, Any> = emptyMap(), write: Boolean = false): GatewayResult<JsonValue> {
        if (!isSafeCapturedProfile(profile)) return GatewayResult.Failure(GatewayFailure.NotConnected("Bot Screen profile unavailable"))
        return sendScopedReadRpc(method, mapOf("profile" to profile) + extra, origin, generation, write = write)
    }

    override suspend fun screenStatus(profile: String, origin: CanonicalOrigin, generation: Long): GatewayResult<ScreenStatus> =
        when (val result = screenRpc("display.status", profile, origin, generation)) {
            is GatewayResult.Failure -> result
            is GatewayResult.Success -> screenStatusValue(result.value)?.let { GatewayResult.Success(it) } ?: screenInvalid()
        }

    override suspend fun screenStart(profile: String, origin: CanonicalOrigin, generation: Long): GatewayResult<ScreenStatus> =
        when (val result = screenRpc("display.start", profile, origin, generation, write = true)) {
            is GatewayResult.Failure -> result
            is GatewayResult.Success -> screenStatusValue(result.value)?.let { GatewayResult.Success(it) } ?: screenInvalid()
        }

    override suspend fun screenStop(profile: String, origin: CanonicalOrigin, generation: Long): GatewayResult<ScreenStatus> =
        when (val result = screenRpc("display.stop", profile, origin, generation, write = true)) {
            is GatewayResult.Failure -> result
            is GatewayResult.Success -> {
                val stopped = result.value["stopped"]?.asBooleanOrNull()
                val status = screenStatusValue(result.value)
                if (stopped == null || status == null) screenInvalid() else GatewayResult.Success(status)
            }
        }

    override suspend fun screenObserve(profile: String, origin: CanonicalOrigin, generation: Long, viewerId: String?): GatewayResult<ScreenObservation> =
        when (val result = screenRpc("display.observe", profile, origin, generation,
            viewerId?.let { mapOf("viewer_id" to it) } ?: emptyMap())) {
            is GatewayResult.Failure -> result
            is GatewayResult.Success -> {
                val root = result.value as? JsonObject
                val status = screenStatusValue(root)
                val ticket = root?.get("ticket")?.asStringOrNull()?.takeIf { it.length in 16..512 && it.none(Char::isWhitespace) }
                val path = root?.get("path")?.asStringOrNull()
                val minted = root?.get("viewer_id")?.asStringOrNull()?.takeIf { it.length in 8..128 && it.none(Char::isWhitespace) }
                if (status == null || ticket == null || path != "/api/display/ws" || minted == null) screenInvalid()
                else GatewayResult.Success(ScreenObservation(status, ticket, minted))
            }
        }

    override suspend fun screenAcquire(profile: String, viewerId: String, origin: CanonicalOrigin, generation: Long): GatewayResult<ScreenLease> =
        screenLeaseRpc("display.lease.acquire", profile, viewerId, origin, generation)

    override suspend fun screenRelease(profile: String, viewerId: String, origin: CanonicalOrigin, generation: Long): GatewayResult<ScreenLease> =
        screenLeaseRpc("display.lease.release", profile, viewerId, origin, generation)

    private suspend fun screenLeaseRpc(method: String, profile: String, viewerId: String, origin: CanonicalOrigin,
                                       generation: Long): GatewayResult<ScreenLease> {
        if (viewerId.length !in 8..128 || viewerId.any(Char::isWhitespace)) return screenInvalid()
        return when (val result = screenRpc(method, profile, origin, generation, mapOf("viewer_id" to viewerId), write = true)) {
            is GatewayResult.Failure -> result
            is GatewayResult.Success -> screenLease(result.value["lease"])?.let { GatewayResult.Success(it) } ?: screenInvalid()
        }
    }

    override suspend fun listSessionModelOptions(runtimeSessionId: String): GatewayResult<List<SessionModelOption>> {
        if (runtimeSessionId.isBlank()) return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Missing runtime session"))
        return listPickerOptions(runtimeSessionId, null, null)
    }

    override suspend fun listSessionModelOptionsScoped(runtime: String, origin: CanonicalOrigin, generation: Long) =
        listPickerOptions(runtime, origin, generation)

    private suspend fun listPickerOptions(runtimeSessionId: String, origin: CanonicalOrigin?, generation: Long?): GatewayResult<List<SessionModelOption>> {
        if (runtimeSessionId.isBlank()) return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Missing runtime session"))
        val params = mapOf(
            "session_id" to runtimeSessionId, "explicit_only" to true,
            "include_unconfigured" to false, "refresh" to false
        )
        return when (val response = if (origin == null) sendRpc("model.options", params)
            else sendScopedReadRpc("model.options", params, origin, checkNotNull(generation))) {
            is GatewayResult.Failure -> response
            is GatewayResult.Success -> {
                val safe = GatewayRedactor.redactMap(response.value.toAny().asStringMap())
                val rows = safe["providers"] as? List<*>
                    ?: return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Model catalog unavailable"))
                val options = rows.flatMap { raw ->
                    val row = raw as? Map<*, *> ?: return@flatMap emptyList<SessionModelOption>()
                    val slug = row["slug"] as? String ?: return@flatMap emptyList<SessionModelOption>()
                    if (row["authenticated"] != true || !safeModelToken(slug)) return@flatMap emptyList<SessionModelOption>()
                    (row["models"] as? List<*>)?.mapNotNull { model ->
                        (model as? String)?.takeIf(::safeModelToken)?.let { SessionModelOption(slug, it) }
                    } ?: emptyList()
                }.distinct()
                GatewayResult.Success(options)
            }
        }
    }

    override suspend fun setSessionModel(runtimeSessionId: String, option: SessionModelOption): GatewayResult<SessionPickerSetResult> {
        if (runtimeSessionId.isBlank() || !safeModelToken(option.model) || !safeModelToken(option.providerSlug))
            return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Unsafe model selection"))
        return setSessionPicker("model", "${option.model} --provider ${option.providerSlug} --session", runtimeSessionId)
    }

    override suspend fun setSessionReasoning(runtimeSessionId: String, effort: String): GatewayResult<SessionPickerSetResult> {
        if (runtimeSessionId.isBlank() || effort !in SESSION_REASONING_LEVELS)
            return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Unsafe reasoning selection"))
        return setSessionPicker("reasoning", effort, runtimeSessionId, reasoning = true)
    }

    override suspend fun setSessionPickerScoped(runtime: String, option: SessionModelOption?, effort: String?, origin: CanonicalOrigin, generation: Long): GatewayResult<SessionPickerSetResult> {
        if (runtime.isBlank() || (option == null) == (effort == null) ||
            option?.let { !safeModelToken(it.model) || !safeModelToken(it.providerSlug) } == true ||
            (effort != null && effort !in SESSION_REASONING_LEVELS))
            return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Unsafe selection"))
        return setSessionPicker(if (option != null) "model" else "reasoning",
            option?.let { "${it.model} --provider ${it.providerSlug} --session" } ?: checkNotNull(effort),
            runtime, option == null, origin, generation)
    }

    private suspend fun setSessionPicker(key: String, value: String, runtimeSessionId: String, reasoning: Boolean = false, origin: CanonicalOrigin? = null, generation: Long? = null): GatewayResult<SessionPickerSetResult> {
        val params = mutableMapOf<String, Any>("key" to key, "value" to value, "session_id" to runtimeSessionId)
        if (reasoning) params["scope"] = "session"
        return when (val response = if (origin == null) sendRpc("config.set", params)
            else sendScopedReadRpc("config.set", params, origin, checkNotNull(generation), write = true)) {
            is GatewayResult.Failure -> response
            is GatewayResult.Success -> {
                val safe = GatewayRedactor.redactMap(response.value.toAny().asStringMap())
                if (response.value !is JsonObject ||
                    listOf("ok", "deferred", "confirm_required").none { safe[it] is Boolean } ||
                    listOf("ok", "deferred", "confirm_required").any { safe.containsKey(it) && safe[it] !is Boolean } || safe["ok"] == false)
                    GatewayResult.Failure(GatewayFailure.SubmissionUnknown("Invalid settings acknowledgment"))
                else GatewayResult.Success(SessionPickerSetResult(safe["deferred"] == true, safe["confirm_required"] == true))
            }
        }
    }

    override suspend fun replayEventsSince(
        sessionId: String, lastSeen: Long, durableSessionId: String?
    ): GatewayResult<ReplayEventsResult> = replayEventsSinceOwned(sessionId, lastSeen, durableSessionId, null)

    override suspend fun replayEventsSinceScoped(
        sessionId: String, lastSeen: Long, durableSessionId: String?,
        origin: com.hermes.bot.data.origin.CanonicalOrigin, generation: Long,
        isCurrent: suspend () -> Boolean
    ): GatewayResult<ReplayEventsResult> = replayEventsSinceOwned(sessionId, lastSeen, durableSessionId) {
        isScopedConnectionCurrent(origin, generation) && isCurrent() && isScopedConnectionCurrent(origin, generation)
    }

    private suspend fun replayEventsSinceOwned(
        sessionId: String,
        lastSeen: Long,
        durableSessionId: String?,
        isCurrent: (suspend () -> Boolean)?
    ): GatewayResult<ReplayEventsResult> {
        suspend fun current(): Boolean {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return isCurrent?.invoke() ?: true
        }
        fun stale() = GatewayResult.Failure(GatewayFailure.NotConnected("Scoped replay unavailable"))
        if (!current()) return stale()
        val sessionMutex = sessionReplayMutexes.computeIfAbsent(sessionId) { Mutex() }
        return sessionMutex.withLock {
            if (!current()) return stale()
            val sessionLock = sessionLocks.computeIfAbsent(sessionId) { Any() }
            val currentWatermark = replayReducer.getWatermark(sessionId) ?: 0L
            val effectiveFloor = maxOf(lastSeen, currentWatermark)

            // Hold live events for this session while replay is in-flight
            val held = mutableListOf<GatewayEvent>()
            if (!current()) return stale()
            synchronized(sessionLock) {
                replayingSessions.add(sessionId)
                heldLiveEvents[sessionId] = held
            }

            var completedSuccessfully = false
            var fallbackAlreadyEmitted = false

            try {
                val res = sendRpc(
                    "session.events.since",
                    mapOf("session_id" to sessionId, "last_seen" to effectiveFloor)
                )

                if (!current()) return stale()
                when (res) {
                    is GatewayResult.Failure -> {
                        if (!current()) return stale()
                        fallbackAlreadyEmitted = true
                        val shouldEmit = synchronized(sessionLock) {
                            val wasReplaying = if (heldLiveEvents[sessionId] === held) replayingSessions.remove(sessionId) else false
                            val heldList = if (heldLiveEvents[sessionId] === held) heldLiveEvents.remove(sessionId) else null
                            replayReducer.clearWatermark(sessionId)
                            replayReducer.markAwaitingHistoryRehydration(sessionId)
                            wasReplaying || !heldList.isNullOrEmpty()
                        }
                        if (shouldEmit && isCurrent == null) {
                            _historyFallbackSignals.tryEmit(
                                HistoryFallbackSignal(
                                    durableSessionId = durableSessionId,
                                    runtimeSessionId = sessionId,
                                    reason = FallbackReason.INVALID_ORDER_OR_GAP,
                                    details = "Replay RPC failed: ${res.failure}; held events dropped, session requires rehydration"
                                )
                            )
                        }
                        return res
                    }
                    is GatewayResult.Success -> {
                        val count = res.value["count"]?.asIntOrNull() ?: 0
                        val epoch = res.value["epoch"]?.asStringOrNull()
                        val latestSeq = res.value["latest_seq"]?.asLongOrNull()
                        val truncated = res.value["truncated"]?.asBooleanOrNull() ?: false

                        val rawEvents = res.value["events"]?.asArrayOrNull()?.list
                        if (rawEvents == null) {
                            if (!current()) return stale()
                            fallbackAlreadyEmitted = true
                            synchronized(sessionLock) {
                                if (heldLiveEvents[sessionId] === held) replayingSessions.remove(sessionId) else false
                                if (heldLiveEvents[sessionId] === held) heldLiveEvents.remove(sessionId) else null
                                replayReducer.clearWatermark(sessionId)
                                replayReducer.markAwaitingHistoryRehydration(sessionId)
                                if (isCurrent == null) _historyFallbackSignals.tryEmit(
                                    HistoryFallbackSignal(
                                        durableSessionId = durableSessionId,
                                        runtimeSessionId = sessionId,
                                        reason = FallbackReason.DECODE_ERROR,
                                        details = "session.events.since response missing 'events' array; session requires rehydration"
                                    )
                                )
                            }
                            return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("session.events.since missing 'events' array"))
                        }

                        if (rawEvents.size > MAX_REPLAY_BATCH_COUNT) {
                            if (!current()) return stale()
                            fallbackAlreadyEmitted = true
                            synchronized(sessionLock) {
                                if (heldLiveEvents[sessionId] === held) replayingSessions.remove(sessionId) else false
                                if (heldLiveEvents[sessionId] === held) heldLiveEvents.remove(sessionId) else null
                                replayReducer.clearWatermark(sessionId)
                                replayReducer.markAwaitingHistoryRehydration(sessionId)
                                if (isCurrent == null) _historyFallbackSignals.tryEmit(
                                    HistoryFallbackSignal(
                                        durableSessionId = durableSessionId,
                                        runtimeSessionId = sessionId,
                                        reason = FallbackReason.TRUNCATED,
                                        details = "Replay batch size ${rawEvents.size} exceeds maximum allowed $MAX_REPLAY_BATCH_COUNT"
                                    )
                                )
                            }
                            return GatewayResult.Success(
                                ReplayEventsResult(
                                    count = count,
                                    epoch = epoch,
                                    latestSeq = latestSeq,
                                    truncated = true,
                                    events = emptyList()
                                )
                            )
                        }

                        val parsedEvents = mutableListOf<GatewayEvent>()
                        for (item in rawEvents) {
                            val obj = item.asObjectOrNull()
                            val seqNum = (obj?.get("seq") as? JsonNumber)?.asLongOrNull()
                            val typeStr = obj?.get("type")?.asStringOrNull()
                            if (obj == null || typeStr == null || seqNum == null) {
                                parsedEvents.add(
                                    GatewayEvent.Unknown(
                                        type = typeStr ?: "malformed",
                                        sessionId = sessionId,
                                        seq = null
                                    )
                                )
                            } else {
                                parsedEvents.add(parseEventFromParams(obj))
                            }
                        }

                        val replayResult = ReplayEventsResult(
                            count = count,
                            epoch = epoch,
                            latestSeq = latestSeq,
                            truncated = truncated,
                            events = parsedEvents
                        )

                        if (!current()) return stale()
                        when (val outcome = replayReducer.reduceReplayBatch(sessionId, durableSessionId, replayResult, lastSeenFloor = effectiveFloor)) {
                            is ReplayBatchOutcome.Fallback -> {
                                if (!current()) return stale()
                                fallbackAlreadyEmitted = true
                                synchronized(sessionLock) {
                                    if (heldLiveEvents[sessionId] === held) replayingSessions.remove(sessionId) else false
                                    if (heldLiveEvents[sessionId] === held) heldLiveEvents.remove(sessionId) else null
                                    if (isCurrent == null) _historyFallbackSignals.tryEmit(outcome.signal)
                                }
                                if (isCurrent != null && !replayResult.truncated)
                                    return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Scoped replay rejected"))
                                return GatewayResult.Success(replayResult.copy(events = emptyList()))
                            }
                            is ReplayBatchOutcome.Accepted -> {
                                // Emit accepted replay events first
                                for (ev in outcome.events) {
                                    if (!current()) return stale()
                                    emitEventObservably(ev, durableSessionId)
                                }

                                // Drain and process held live events atomically in strict order
                                // Session remains in hold state until all held events are drained and applied
                                while (true) {
                                    if (!current()) return stale()
                                    val liveToProcess: List<GatewayEvent> = synchronized(sessionLock) {
                                        val currentHeld = heldLiveEvents[sessionId]
                                        if (currentHeld !== held) {
                                            emptyList()
                                        } else if (currentHeld.isEmpty()) {
                                            if (heldLiveEvents[sessionId] === held) replayingSessions.remove(sessionId) else false
                                            if (heldLiveEvents[sessionId] === held) heldLiveEvents.remove(sessionId) else null
                                            emptyList()
                                        } else {
                                            val copy = ArrayList(currentHeld)
                                            currentHeld.clear()
                                            copy
                                        }
                                    }

                                    if (liveToProcess.isEmpty()) {
                                        break
                                    }

                                    for (liveEvent in liveToProcess) {
                                        if (!current()) return stale()
                                        if (liveEvent.sessionId == sessionId) {
                                            when (val acceptedLive = replayReducer.evaluateLiveEvent(liveEvent, durableSessionId)) {
                                                is LiveEventOutcome.Accepted -> {
                                                    if (!current()) return stale()
                                                    emitEventObservably(acceptedLive.event, durableSessionId)
                                                }
                                                is LiveEventOutcome.Fallback -> {
                                                    if (!current()) return stale()
                                                    _historyFallbackSignals.tryEmit(acceptedLive.signal)
                                                }
                                                is LiveEventOutcome.Deduplicated -> {}
                                                is LiveEventOutcome.Rejected -> {}
                                            }
                                        }
                                    }
                                }

                                completedSuccessfully = true
                                return GatewayResult.Success(replayResult.copy(events = outcome.events))
                            }
                        }
                    }
                }
            } finally {
                val stillCurrent = if (isCurrent == null) true else try { current() } catch (_: kotlinx.coroutines.CancellationException) { false }
                synchronized(sessionLock) {
                    if (heldLiveEvents[sessionId] === held) {
                        replayingSessions.remove(sessionId)
                        heldLiveEvents.remove(sessionId)
                        if (!completedSuccessfully && stillCurrent) {
                            replayReducer.clearWatermark(sessionId)
                            replayReducer.markAwaitingHistoryRehydration(sessionId)
                            if (!fallbackAlreadyEmitted && isCurrent == null) _historyFallbackSignals.tryEmit(
                                HistoryFallbackSignal(durableSessionId, sessionId,
                                    FallbackReason.INVALID_ORDER_OR_GAP,
                                    "Replay aborted or cancelled before completion; held events dropped, session requires rehydration"))
                        }
                    }
                }
            }
        }
    }

    override suspend fun getCapabilities(): GatewayResult<GatewayCapabilities> {
        return getCapabilities(allowHandshakeRpc = false)
    }

    private suspend fun getCapabilities(allowHandshakeRpc: Boolean): GatewayResult<GatewayCapabilities> {
        return when (val res = sendRpc("gateway.capabilities", emptyMap(), allowHandshakeRpc = allowHandshakeRpc)) {
            is GatewayResult.Failure -> res
            is GatewayResult.Success -> {
                val exclusiveSubmit = res.value["per_session_exclusive_submit"]?.asBooleanOrNull() ?: false
                val raw = GatewayRedactor.redactMap(res.value.toAny().asStringMap())
                GatewayResult.Success(
                    GatewayCapabilities(
                        perSessionExclusiveSubmit = exclusiveSubmit,
                        rawCapabilities = raw
                    )
                )
            }
        }
    }

    override suspend fun ping(): GatewayResult<GatewayPingResult> {
        val status = _connectionStatus.value
        if (status != ConnectionStatus.CONNECTED && status != ConnectionStatus.CONNECTING) {
            return GatewayResult.Failure(GatewayFailure.NotConnected())
        }

        val gen = connectionGeneration
        val id = nextRequestId()
        val requestJson = JsonObject(
            "jsonrpc" to JsonString("2.0"),
            "id" to JsonString(id),
            "method" to JsonString("gateway.ping"),
            "params" to JsonObject()
        )
        val rawWire = GatewayJson.serialize(requestJson)

        val rpcDef = CompletableDeferred<JsonValue>()
        val rawPingDef = CompletableDeferred<Boolean>()
        pendingRequests[id] = PendingRpc("gateway.ping", rpcDef, gen)
        pingDeferred = Pair(gen, rawPingDef)

        val sent = try {
            transport.send(rawWire)
        } catch (e: Exception) {
            pendingRequests.remove(id)
            pingDeferred = null
            return GatewayResult.Failure(
                GatewayFailure.Transport(
                    GatewayRedactor.redactString(e.message ?: "Failed sending ping frame"),
                    e.javaClass.simpleName
                )
            )
        }

        if (!sent) {
            pendingRequests.remove(id)
            pingDeferred = null
            return GatewayResult.Failure(GatewayFailure.Transport("Failed sending ping frame"))
        }

        return try {
            val result = withTimeoutOrNull(rpcTimeoutMs) {
                kotlinx.coroutines.selects.select<GatewayResult<GatewayPingResult>> {
                    rawPingDef.onAwait { ok ->
                        GatewayResult.Success(GatewayPingResult(ok = ok))
                    }
                    rpcDef.onAwait { jsonVal ->
                        val ok = jsonVal["ok"]?.asBooleanOrNull() ?: true
                        GatewayResult.Success(GatewayPingResult(ok = ok))
                    }
                }
            }
            result ?: GatewayResult.Failure(GatewayFailure.Transport("RPC call 'gateway.ping' timed out"))
        } catch (e: GatewayException) {
            GatewayResult.Failure(e.failure)
        } catch (e: Exception) {
            GatewayResult.Failure(
                GatewayFailure.Transport(
                    GatewayRedactor.redactString(e.message ?: "Ping execution exception"),
                    e.javaClass.simpleName
                )
            )
        } finally {
            pendingRequests.remove(id)
            pingDeferred = null
        }
    }

    /**
     * CHG-005-S1 (TASK-001/002): tolerant history message parsing.
     * Content extraction accepts, in precedence order:
     *   1. `content` as a JSON string;
     *   2. `content` as a JSON array — concatenates the `text` string fields of its
     *      block objects in order (non-text blocks contribute nothing);
     *   3. a top-level `text` string field as fallback.
     * Every extracted body passes through [GatewayRedactor.redactString].
     * Role is read verbatim: a missing/non-string role stays "" at the boundary
     * (no silent "user" default; interpretation belongs to the chat reducer).
     */
    private fun parseMessages(jsonVal: JsonValue?): List<SessionMessage> {
        val arr = jsonVal?.asArrayOrNull() ?: return emptyList()
        return arr.list.mapNotNull { item ->
            val obj = item.asObjectOrNull() ?: return@mapNotNull null
            val role = obj["role"]?.asStringOrNull() ?: ""
            val content = when (val contentVal = obj["content"]) {
                is JsonString -> GatewayRedactor.redactString(contentVal.value)
                is JsonArray -> {
                    val joined = contentVal.list.mapNotNull { block ->
                        (block as? JsonObject)?.get("text")?.asStringOrNull()
                    }.joinToString("")
                    GatewayRedactor.redactString(joined)
                }
                else -> obj["text"]?.asStringOrNull()?.let { GatewayRedactor.redactString(it) } ?: ""
            }
            // F007-S1: host role:"tool" history rows (tui_gateway/session_history.py)
            // carry `name`/`context` with NO content/text. Preserve them verbatim
            // (redacted) as raw boundary data; all other rows keep them null.
            // F-007 polish: role matching is case-insensitive, aligned with the
            // chat reducer (ChatViewModel.loadSession uses ignoreCase) so a
            // casing variant can never degrade to a generic unknown-role row.
            val isToolRole = role.equals("tool", ignoreCase = true)
            val toolName = if (isToolRole) {
                obj["name"]?.asStringOrNull()?.let { GatewayRedactor.redactString(it) }
            } else {
                null
            }
            val toolContext = if (isToolRole) {
                obj["context"]?.asStringOrNull()?.let { GatewayRedactor.redactString(it) }
            } else {
                null
            }
            // F-007 polish (deferred from F007-S4): assistant history rows may
            // carry reasoning detail keys (session_history.py forwards
            // _HISTORY_ASSISTANT_DETAIL_KEYS). Preserve the first present
            // string value (redacted) as raw boundary data; blank stays null —
            // never a fabricated empty reasoning body. Tool rows never carry
            // reasoning detail.
            val reasoning = if (!isToolRole) {
                (obj["reasoning"]?.asStringOrNull() ?: obj["reasoning_content"]?.asStringOrNull())
                    ?.let { GatewayRedactor.redactString(it) }
                    ?.takeUnless { it.isBlank() }
            } else {
                null
            }
            // CHAT-009 (FZ-9): tolerant history-row timestamp read. The host
            // sends fractional epoch SECONDS (e.g. 1789609565.33); parse to
            // epoch MILLIS (rounded toward zero via double->long cast).
            // Missing/non-number/null stays null — never fabricated, never
            // logged (a timestamp is not secret-bearing but is not diagnostics
            // material either).
            val createdAtMillis = (obj["timestamp"] as? JsonNumber)
                ?.let { (it.value * 1000.0).toLong() }
            val sourceSeconds = (obj["timestamp"] as? JsonNumber)?.value?.takeIf { it.isFinite() && it >= 0 }
            SessionMessage(role, content, toolName, toolContext, reasoning, createdAtMillis, sourceSeconds,
                if (role == "user") MessageEditPolicy.target(obj.map, obj["row_id"]?.asLongOrNull(), content) else null,
                obj["row_id"]?.asLongOrNull()?.takeIf { it > 0 })
        }
    }

    private fun parseEventFromParams(params: JsonObject): GatewayEvent {
        val type = params["type"]?.asStringOrNull() ?: "unknown"
        val sid = params["session_id"]?.asStringOrNull()
        val seq = (params["seq"] as? JsonNumber)?.asLongOrNull()
        val rawPayload = params["payload"] as? JsonObject ?: JsonObject()
        val payloadMap = GatewayRedactor.redactMap(rawPayload.toAny().asStringMap())
        val payload = GatewayJson.fromAny(payloadMap) as? JsonObject ?: JsonObject()

        return decodeEvent(type, sid, seq, if (type in SubagentWireDecoder.eventTypes || type == "request.cancel") rawPayload else payload)
    }

    /**
     * Test seam (BLD-006 S4 / TASK-015): decodes a raw `{"type": ...,
     * "payload": {...}}` JSON body through the same typed event decoding used
     * for live frames, so absent/non-string/blank terminal statuses are
     * provably distinguishable at the boundary without fabricating defaults.
     * Performs no redaction round-trip beyond the payload itself.
     */
    fun parseEventForTest(rawJson: String): GatewayEvent {
        val parsed = GatewayJson.parse(rawJson).asObjectOrNull() ?: JsonObject()
        val type = parsed["type"]?.asStringOrNull() ?: "unknown"
        val rawPayload = parsed["payload"] as? JsonObject ?: JsonObject()
        return decodeEvent(type, parsed["session_id"]?.asStringOrNull(), null, rawPayload)
    }

    private fun decodeEvent(
        type: String,
        sid: String?,
        seq: Long?,
        payload: JsonObject
    ): GatewayEvent {
        if (type in SubagentWireDecoder.eventTypes) {
            return GatewayEvent.SubagentUpdate(sid, seq, type, SubagentWireDecoder.child(payload, type))
        }
        return when (type) {
            "request.cancel" -> GatewayEvent.ApprovalCancelled(sid, seq,
                ApprovalWireDecoder.id(payload["id"]) ?: "", when(payload["reason"]?.asStringOrNull()) {
                    "timeout" -> "timeout"; "interrupted" -> "interrupted"; else -> "resolved"
                })
            "gateway.ready" -> {
                GatewayEvent.Ready(
                    heartbeat = payload["heartbeat"]?.asBooleanOrNull() ?: false,
                    changeEvents = payload["change_events"]?.asBooleanOrNull() ?: false,
                    replayEpoch = payload["replay_epoch"]?.asStringOrNull(),
                    skin = payload["skin"]?.toAny().asStringMap()
                )
            }
            "message.start" -> GatewayEvent.MessageStart(sessionId = sid, seq = seq)
            "message.delta" -> GatewayEvent.MessageDelta(
                sessionId = sid,
                seq = seq,
                text = GatewayRedactor.redactString(payload["text"]?.asStringOrNull() ?: ""),
                rendered = payload["rendered"]?.asStringOrNull()?.let { GatewayRedactor.redactString(it) }
            )
            "message.interim" -> GatewayEvent.MessageInterim(
                sessionId = sid,
                seq = seq,
                text = GatewayRedactor.redactString(payload["text"]?.asStringOrNull() ?: ""),
                alreadyStreamed = payload["already_streamed"]?.asBooleanOrNull()
            )
            "message.complete" -> GatewayEvent.MessageComplete(
                sessionId = sid,
                seq = seq,
                text = payload["text"]?.asStringOrNull()?.let { GatewayRedactor.redactString(it) },
                // BLD-006 S4 (TASK-015 / D-03): no fabricated "complete" — an
                // absent or non-string status stays null and decodes as
                // UNKNOWN in the reducer; blank/whitespace strings pass
                // through unchanged and also map to UNKNOWN there.
                status = payload["status"]?.asStringOrNull(),
                usage = parseUsage(payload["usage"]),
                // F007-S4: string reasoning is preserved (redacted like
                // message text); absent/non-string stays null — no
                // fabrication, no crash.
                reasoning = payload["reasoning"]?.asStringOrNull()
                    ?.let { GatewayRedactor.redactString(it) }
            )
            "error" -> GatewayEvent.Error(
                sessionId = sid,
                seq = seq,
                message = GatewayRedactor.redactString(payload["message"]?.asStringOrNull() ?: "Unknown error")
            )
            "tool.generating" -> GatewayEvent.ToolGenerating(
                sessionId = sid,
                seq = seq,
                name = payload["name"]?.asStringOrNull() ?: ""
            )
            "tool.start" -> GatewayEvent.ToolStart(
                sessionId = sid,
                seq = seq,
                toolId = payload["tool_id"]?.asStringOrNull() ?: "",
                name = payload["name"]?.asStringOrNull() ?: "",
                context = payload["context"]?.asStringOrNull()?.let { GatewayRedactor.redactString(it) },
                args = payload["args"]?.toAny().asStringMap()
            )
            "tool.complete" -> GatewayEvent.ToolComplete(
                sessionId = sid,
                seq = seq,
                toolId = payload["tool_id"]?.asStringOrNull() ?: "",
                name = payload["name"]?.asStringOrNull() ?: "",
                args = payload["args"]?.toAny().asStringMap(),
                durationSeconds = payload["duration_s"]?.asDoubleOrNull(),
                result = payload["result"]?.toAny(),
                summary = payload["summary"]?.asStringOrNull()?.let { GatewayRedactor.redactString(it) },
                taskSnapshot = if (TaskSnapshotDecoder.isTaskTool(payload["name"]?.asStringOrNull()) &&
                    (payload["error"] == null || payload["error"] == JsonNull || payload["error"] == JsonBoolean(false)))
                    TaskSnapshotDecoder.decode(payload["result"]) else null
            )
            "todo.updated" -> GatewayEvent.TasksUpdated(sid, seq, TaskSnapshotDecoder.decode(payload))
            "reasoning.delta" -> GatewayEvent.ReasoningDelta(
                sessionId = sid,
                seq = seq,
                kind = ReasoningKind.REASONING,
                // F007-S2: non-string/absent text decodes safely as empty —
                // no crash, no fabricated text. Redacted like message text.
                text = GatewayRedactor.redactString(payload["text"]?.asStringOrNull() ?: "")
            )
            "thinking.delta" -> GatewayEvent.ReasoningDelta(
                sessionId = sid,
                seq = seq,
                kind = ReasoningKind.THINKING,
                text = GatewayRedactor.redactString(payload["text"]?.asStringOrNull() ?: "")
            )
            "session.usage" -> GatewayEvent.SessionUsageUpdated(sid, seq, ContextUsageWireDecoder.live(payload))
            "session.info" -> GatewayEvent.SessionInfo(
                sessionId = sid,
                seq = seq,
                // CHAT-010: boundary parity with the create/resume info maps —
                // the event map is redacted at the boundary too. Harmless to
                // the consumed keys (model, reasoning_effort are not
                // credential keys); credential-keyed entries (stored_session_id
                // -style) become "[REDACTED]", which nothing consumes.
                info = GatewayRedactor.redactMap(payload.toAny().asStringMap())
            )
            "status.update" -> GatewayEvent.StatusUpdate(
                sessionId = sid,
                seq = seq,
                status = payload["status"]?.asStringOrNull() ?: "",
                kind = payload["kind"]?.asStringOrNull()?.takeIf { it in setOf("compacted", "ready") }
            )
            "session.title" -> GatewayEvent.SessionTitle(
                sessionId = sid,
                seq = seq,
                title = payload["title"]?.asStringOrNull() ?: ""
            )
            "sessions.changed" -> GatewayEvent.SessionsChanged(sessionId = sid, seq = seq)
            else -> {
                // Certified Host Incompatibility #5: tool.progress, reasoning.available, etc.
                if (type == "tool.progress" || type == "reasoning.available" ||
                    type == "moa.progress"
                ) {
                    GatewayEvent.ToolProgress(
                        sessionId = sid,
                        seq = seq,
                        type = type,
                        payload = payload.toAny().asStringMap()
                    )
                } else {
                    GatewayEvent.Unknown(
                        type = type,
                        sessionId = sid,
                        seq = seq,
                        payload = payload.toAny().asStringMap()
                    )
                }
            }
        }
    }

    private fun parseUsage(jsonVal: JsonValue?): GatewayUsage? {
        val obj = jsonVal?.asObjectOrNull() ?: return null
        return GatewayUsage(
            promptTokens = obj["prompt_tokens"]?.asLongOrNull(),
            completionTokens = obj["completion_tokens"]?.asLongOrNull(),
            totalTokens = obj["total_tokens"]?.asLongOrNull()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asStringMap(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()

    private class GatewayException(val failure: GatewayFailure) : RuntimeException()
}

private fun observedIdentity(value: JsonValue?): String? = (value as? JsonString)?.value?.takeIf {
    it.isNotBlank() && it == it.trim() && it.length <= 256 && it.none { c -> c.isISOControl() }
}

/** The certified live-unpersisted variant omits activity fields, but explicitly reports a lazy empty draft. */
private fun isEmptyUnpersistedDraft(root: JsonValue): Boolean {
    val runtime = observedIdentity(root["session_id"]) ?: return false
    val durable = observedIdentity(root["stored_session_id"]) ?: return false
    if (runtime.isBlank() || listOf("session_key", "resumed").any {
            root[it] != null && observedIdentity(root[it]) != durable
        }) return false
    return root["info"]?.get("lazy")?.asBooleanOrNull() == true &&
        (root["message_count"] as? JsonNumber)?.longValue == 0L &&
        root["messages"]?.asArrayOrNull()?.list?.isEmpty() == true &&
        listOf("running", "status", "inflight", "queued", "turn_started_at", "started_at", "messages_omitted")
            .all { root[it] == null }
}

/** Text-only host projection. No toAny/raw stringify, executable parts or media retention. */
private class ActiveProjectionDecoder {
    private var bytes = 0
    fun decode(root: JsonValue): ActiveSessionProjection = ActiveSessionProjection(
        property(root, "running", ::boolean), text(root, "status", 128),
        property(root, "turn_started_at", ::seconds),
        property(root, "inflight") { value -> (value as? JsonObject)?.let { obj ->
            ActiveInflightProjection(
                text(obj, "user"), text(obj, "assistant"), property(obj, "streaming", ::boolean),
                text(obj, "display_kind", 128), property(obj, "display_metadata", ::metadata),
                array(obj, "corrections") { (it as? JsonString)?.value?.let(::boundedText) },
                array(obj, "correction_offsets", ::integer), text(obj, "error"), text(obj, "status", 128),
                property(obj, "recoverable", ::boolean), property(obj, "error_surface") { surface ->
                    (surface as? JsonObject)?.let {
                        ActiveErrorSurface(text(it, "layer", 128), text(it, "code", 128), property(it, "retryable", ::boolean))
                    }
                })
        } },
        property(root, "queued") { (it as? JsonObject)?.let { obj -> ActiveQueuedProjection(text(obj, "user")) } },
        bytes
    )

    private fun seconds(value: JsonValue): Double? = (value as? JsonNumber)?.value?.takeIf { it.isFinite() && it >= 0 }
    private fun integer(value: JsonValue): Long? = (value as? JsonNumber)?.takeIf { it.isInteger }?.longValue?.takeIf { it >= 0 }
    private fun boolean(value: JsonValue): Boolean? = value.asBooleanOrNull()
    private fun <T> property(obj: JsonValue, key: String, decode: (JsonValue) -> T?): ObservedProperty<T> {
        val raw = obj[key] ?: return ObservedProperty()
        if (raw === com.hermes.bot.data.gateway.json.JsonNull) return ObservedProperty(WirePresence.NULL)
        val result = decode(raw)
        return ObservedProperty(if (result == null) WirePresence.INVALID else WirePresence.VALUE, result)
    }
    private fun text(obj: JsonValue, key: String, points: Int = Int.MAX_VALUE): ObservedProperty<String> {
        val raw = obj[key]
        if (raw !is JsonString) return property(obj, key) { null }
        val redacted = GatewayRedactor.redactString(raw.value)
        val retained = boundedText(redacted, points)
        return ObservedProperty(WirePresence.VALUE, retained, retained != redacted)
    }
    private fun boundedText(text: String, points: Int = Int.MAX_VALUE): String {
        val safe = GatewayRedactor.redactString(text)
        val output = StringBuilder(); var index = 0; var size = 0; var count = 0
        val limit = minOf(16 * 1024, 8 * 1024 * 1024 - bytes)
        while (index < safe.length && count < points) {
            val cp = safe.codePointAt(index)
            val n = when { cp <= 127 -> 1; cp <= 2047 -> 2; cp <= 65535 -> 3; else -> 4 }
            if (size + n > limit) break
            output.appendCodePoint(cp); size += n; count++; index += Character.charCount(cp)
        }
        bytes += size
        return output.toString()
    }
    private fun <T> array(obj: JsonValue, key: String, decode: (JsonValue) -> T?): ObservedProperty<List<T>> {
        val raw = obj[key]
        if (raw !is JsonArray) return property(obj, key) { null }
        var invalid = false; var truncated = raw.list.size > 256
        val retained = raw.list.take(256).mapNotNull {
            val result = decode(it)
            if (result == null) invalid = true
            if (it is JsonString && result is String && result != GatewayRedactor.redactString(it.value)) truncated = true
            result
        }
        bytes += retained.size * 8
        return ObservedProperty(if (invalid) WirePresence.INVALID else WirePresence.VALUE, retained, truncated)
    }
    private fun metadata(value: JsonValue): ActiveDisplayMetadata? {
        val obj = value as? JsonObject ?: return null
        // methods_prompt title_preview and session_notifications async delegation producer, pinned host.
        val fields = linkedMapOf<String, ObservedProperty<*>>()
        for (key in listOf("title_preview", "display_text", "delegation_id")) {
            if (obj.map.containsKey(key)) fields[key] = text(obj, key, if (key == "delegation_id") 128 else Int.MAX_VALUE)
        }
        for (key in listOf("task_count", "completed_count", "failed_count")) {
            if (obj.map.containsKey(key)) fields[key] = property(obj, key, ::integer)
        }
        if (obj.map.containsKey("duration_seconds")) fields["duration_seconds"] = property(obj, "duration_seconds", ::seconds)
        return ActiveDisplayMetadata(fields.toMap())
    }
}
