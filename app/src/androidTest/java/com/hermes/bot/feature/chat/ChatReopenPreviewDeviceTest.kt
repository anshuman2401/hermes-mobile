package com.hermes.bot.feature.chat

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.bot.data.auth.*
import com.hermes.bot.data.cache.VaultConversationCacheRepository
import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.data.history.*
import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.*
import com.hermes.bot.design.HermesBotTheme
import com.hermes.bot.feature.connect.*
import com.hermes.bot.feature.sessions.SessionsViewModel
import com.hermes.bot.navigation.HermesNavShell
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Real shell, route ownership, ViewModels, repositories and Keystore; synthetic peer, zero network. */
class ChatReopenPreviewDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fiveBackAndReopenTransitionsDisplayMemoryWhileBothHostReadsWait() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".streamprofile")) { "Use the isolated synthetic package" }
        compose.runOnUiThread {
            // Synthetic test surface only; neither unlocks the device nor changes its settings.
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                compose.activity.setShowWhenLocked(true)
                compose.activity.setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val nonce = UUID.randomUUID().toString()
        val folder = File(context.cacheDir, "synthetic-chat-preview-$nonce").apply { mkdirs() }
        val alias = "synthetic-chat-preview-$nonce"
        val origin = CanonicalOrigin.parse("http://example.test:9711").getOrThrow()
        val peer = SyntheticChatPeer()
        val vault = EncryptedVault(folder, AesGcmCipherEngine(AndroidKeyStoreMasterKeyProvider(alias)))
        val gateway = DefaultGatewayProtocolAdapter(peer)
        var memory = ChatPreviewRepository(vault)
        var sessions: SessionsViewModel? = null
        val visibleModel = java.util.concurrent.atomic.AtomicReference<ChatViewModel>()
        val modelJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
        try {
            runBlocking {
                vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
                vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-cookie"), origin.originUrl))
                assertTrue(vault.prepareLocalLogin(origin.originUrl))
                vault.saveSelectedContextProfile("default")
                assertTrue(gateway.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)))
            }
            val http = object : StoredHistoryHttpTransport {
                override fun open(origin: CanonicalOrigin, path: String, profile: String, limit: Int, offset: Int,
                    headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<StoredHistoryResponse> {
                    val gate = peer.hold
                    val revision = peer.revision
                    return object : AuthenticatedHttpCall<StoredHistoryResponse> {
                        override fun cancel() {}
                        override suspend fun awaitHeaders(): StoredHistoryResponse = object : StoredHistoryResponse {
                            override val statusCode = 200
                            override val headers = emptyMap<String, List<String>>()
                            override suspend fun readBoundedBody(): ByteArray {
                                peer.restEntered = true
                                gate?.await()
                                return storedBody(revision).toByteArray()
                            }
                            override fun close() {}
                        }
                    }
                }
            }
            val actual = DefaultSessionRepository(gateway, vault,
                storedHistoryAdapter = DefaultStoredHistoryProtocolAdapter(DefaultAuthRepository(vault), http))
            // List also stays synthetic; no production HTTP transport can be invoked by the shell.
            val repository = object : SessionRepository by actual {
                override suspend fun listSessions(forceRefresh: Boolean) = Result.success(SessionListOutcome(
                    listOf(SessionSummary("synthetic-session", "Synthetic preview chat", "Synthetic", 1000.0, 100, "android")), false))
                override suspend fun activeSessionModels() = Result.success(emptyMap<String, String>())
            }
            val baseCache = VaultConversationCacheRepository(vault)
            val writeResult = java.util.concurrent.atomic.AtomicReference("not-attempted")
            val cache = object : com.hermes.bot.data.cache.ConversationCacheRepository by baseCache {
                override suspend fun replaceRemoteSnapshotScoped(storedSessionId: String, resume: SessionResumeResult,
                    nowMillis: Long, expected: AuthCredentialLease, selectedContext: String?): com.hermes.bot.data.cache.ConversationCacheWriteResult {
                    return baseCache.replaceRemoteSnapshotScoped(storedSessionId, resume, nowMillis, expected, selectedContext)
                        .also { writeResult.set(it.javaClass.simpleName) }
                }
            }
            compose.runOnUiThread { sessions = SessionsViewModel(repository) }
            compose.setContent {
                HermesBotTheme {
                    HermesNavShell(
                        themePreference = com.hermes.bot.design.AppThemePreference.SYSTEM,
                        onThemeChanged = {}, onServerNameChanged = {},
                        connectUiState = ConnectUiState(startupPresentation = StartupPresentation.SESSIONS,
                            state = ConnectState.CONNECTED_COMPATIBLE),
                        onHostChanged = {}, onPortChanged = {}, onTestConnection = {}, onAuthenticate = {},
                        onUsernameChanged = {}, onPasswordChanged = {}, onLogin = {}, onOpenSessions = {},
                        onRetry = {}, onToggleDiagnostics = {}, onClearOfflineCache = {}, onClearLocalData = {},
                        sessionsViewModel = sessions,
                        chatViewModelProvider = { id -> ChatViewModel(id, sessionRepository = repository,
                            gatewayProtocolAdapter = gateway, vaultStorage = vault,
                            conversationCacheRepository = cache, chatPreviewRepository = memory).also {
                                visibleModel.set(it); modelJobs.add(it.viewModelScope.coroutineContext[Job]!!)
                            } })
                }
            }
            compose.waitForIdle()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic preview chat").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText("Synthetic preview chat").performClick()
            compose.waitUntil(10_000) { isDisplayed(marker(0)) }
            compose.onNodeWithText(marker(0)).assertIsDisplayed()
            // Allow ancillary save/seed work to complete before the first Back.
            compose.waitUntil(5000) { memory.publishIfPresent("synthetic-session") {} }
            compose.waitForIdle()
            val timings = mutableListOf<Long>()
            repeat(5) { attempt ->
                compose.onNodeWithContentDescription("Back to sessions").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText("Synthetic preview chat").fetchSemanticsNodes().isNotEmpty() }
                peer.hold = CompletableDeferred()
                peer.revision = attempt + 1
                peer.restEntered = false
                peer.resumeEntered = false
                val started = SystemClock.elapsedRealtime()
                compose.onNodeWithText("Synthetic preview chat").performClick()
                compose.waitUntil(2000) { isDisplayed(marker(attempt)) }
                compose.onNodeWithText(marker(attempt)).assertIsDisplayed()
                timings.add(SystemClock.elapsedRealtime() - started)
                compose.onNodeWithText("Syncing").assertIsDisplayed()
                compose.waitUntil(5000) { peer.restEntered && peer.resumeEntered }
                Thread.sleep(5000)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("synthetic_test_interactive", (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isInteractive.toString())
                    putString("synthetic_test_activity_visible", compose.activity.window.decorView.isShown.toString())
                    putString("synthetic_preview_rows", visibleModel.get().uiState.value.timeline.size.toString())
                    putString("synthetic_preview_loading", visibleModel.get().uiState.value.isInitialLoading.toString())
                    putString("synthetic_preview_cached", visibleModel.get().uiState.value.isCachedHistoryPreview.toString())
                })
                compose.waitUntil(2000) { isDisplayed(marker(attempt)) }
                compose.onNodeWithText(marker(attempt)).assertIsDisplayed()
                peer.hold!!.complete(Unit)
                compose.waitUntil(10_000) { isDisplayed(marker(attempt + 1)) }
                compose.onNodeWithText(marker(attempt + 1)).assertIsDisplayed()
                compose.waitUntil(5000) {
                    var ready = false
                    memory.publishIfPresent("synthetic-session") { snapshot ->
                        ready = snapshot.timeline.filterIsInstance<AssistantMessage>().any { it.text == marker(attempt + 1) }
                    }
                    ready
                }
            }
            // Warm cycles intentionally leave as soon as memory is accepted. The separate cold
            // check needs the final owned disk write to finish before its Back cancels the load.
            compose.waitUntil(10_000) { !visibleModel.get().uiState.value.isHistoryReadInFlight }
            compose.onNodeWithContentDescription("Back to sessions").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Synthetic preview chat").fetchSemanticsNodes().isNotEmpty() }
            // A fresh repository models process-local memory loss without touching the encrypted vault.
            memory.close()
            memory = ChatPreviewRepository(vault)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("synthetic_warm_chat_visible_ms", timings.joinToString(","))
                putString("synthetic_disk_write_result", writeResult.get())
                putString("synthetic_runtime_attached", (visibleModel.get().uiState.value.runtimeSessionId != null).toString())
                putString("synthetic_history_error_present", (visibleModel.get().uiState.value.errorMessage != null).toString())
            })
            val coldSnapshot = runBlocking { cache.load("synthetic-session") as com.hermes.bot.data.cache.ConversationCacheReadResult.Snapshot }
            val coldMarker = coldSnapshot.value.rows.last().content
            peer.hold = CompletableDeferred()
            peer.restEntered = false; peer.resumeEntered = false
            val coldStarted = SystemClock.elapsedRealtime()
            compose.onNodeWithText("Synthetic preview chat").performClick()
            compose.waitUntil(5000) { isDisplayed(coldMarker) }
            compose.onNodeWithText(coldMarker).assertIsDisplayed()
            val coldMillis = SystemClock.elapsedRealtime() - coldStarted
            compose.waitUntil(5000) { peer.restEntered && peer.resumeEntered }
            assertEquals(0, peer.prompts)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("synthetic_warm_chat_visible_ms", timings.joinToString(","))
                putString("synthetic_warm_chat_target_met", timings.all { it <= 500 }.toString())
                putString("synthetic_cold_chat_visible_ms", coldMillis.toString())
                putString("synthetic_cold_snapshot_rows", coldSnapshot.value.rows.size.toString())
            })
        } finally {
            // Detach navigation before clearing its owner, so recomposition cannot recreate a child.
            compose.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            compose.runOnUiThread {
                compose.activity.viewModelStore.clear()
                sessions?.let { androidx.lifecycle.ViewModelStore().apply { put("synthetic-sessions", it); clear() } }
            }
            runBlocking { withTimeout(5000) { modelJobs.joinAll() } }
            gateway.disconnect()
            peer.close()
            memory.close()
            folder.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    private fun isDisplayed(text: String): Boolean = runCatching {
        compose.onNodeWithText(text).assertIsDisplayed()
    }.isSuccess

    companion object {
        private fun marker(revision: Int) = "Synthetic answer 99 revision $revision"
        private fun storedBody(revision: Int): String {
            val rows = (0 until 100).map { i -> mapOf("id" to i + 1, "session_id" to "synthetic-session",
                "role" to if (i % 2 == 0) "user" else "assistant",
                "content" to if (i == 99) marker(revision) else "Synthetic message $i") }
            return GatewayJson.serialize(GatewayJson.fromAny(mapOf("session_id" to "synthetic-session",
                "profile" to "default", "messages" to rows,
                "pagination" to mapOf("limit" to 120, "offset" to 0, "order" to "latest", "returned" to 100))))
        }
    }
}

private class SyntheticChatPeer : GatewayTransport {
    private val frames = Channel<GatewayTransportFrame>(Channel.UNLIMITED)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val incoming = flow { for (frame in frames) emit(frame) }
    @Volatile var hold: CompletableDeferred<Unit>? = null
    @Volatile var revision = 0
    @Volatile var restEntered = false
    @Volatile var resumeEntered = false
    @Volatile var prompts = 0
    override suspend fun connect(config: GatewayConnectConfig): GatewayConnectResult {
        // The real adapter closes an old transport before connecting its next generation.
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        frames.send(GatewayTransportFrame.Text("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":false,"change_events":false,"replay_epoch":"synthetic-epoch"}}}"""))
        return GatewayConnectResult.Success
    }
    override suspend fun send(text: String): Boolean {
        val request = GatewayJson.parse(text) as JsonObject
        val id = request["id"]?.asStringOrNull() ?: return true
        when (request["method"]?.asStringOrNull()) {
            "gateway.capabilities" -> respond(id, """{"per_session_exclusive_submit":true}""")
            "client.capabilities" -> respond(id, """{"server_requests":["approval"]}""")
            "approval.pending" -> respond(id, """{"approvals":[]}""")
            "gateway.ping" -> respond(id, """{"ok":true}""")
            "session.resume" -> {
                val gate = hold
                val current = revision
                resumeEntered = true
                scope.launch {
                    gate?.await()
                    val messages = (0 until 100).map { i -> mapOf("role" to if (i % 2 == 0) "user" else "assistant",
                        "content" to if (i == 99) "Synthetic compact revision $current" else "Synthetic compact message $i") }
                    respond(id, GatewayJson.serialize(GatewayJson.fromAny(mapOf("session_id" to "synthetic-runtime",
                        "stored_session_id" to "synthetic-session", "message_count" to 100, "messages" to messages,
                        "running" to false, "status" to "idle"))))
                }
            }
            "session.events.since" -> respond(id, """{"count":0,"epoch":"synthetic-epoch","latest_seq":0,"truncated":false,"events":[]}""")
            "config.get" -> respond(id, """{"key":"reasoning","value":"none"}""")
            "prompt.submit" -> prompts++
            else -> frames.trySend(GatewayTransportFrame.Text("""{"jsonrpc":"2.0","id":"$id","error":{"code":-32601,"message":"Synthetic unsupported"}}"""))
        }
        return true
    }
    private fun respond(id: String, result: String) {
        frames.trySend(GatewayTransportFrame.Text("""{"jsonrpc":"2.0","id":"$id","result":$result}"""))
    }
    override fun close(code: Int, reason: String?) { scope.cancel() }
    fun close() { scope.cancel(); frames.close() }
}
