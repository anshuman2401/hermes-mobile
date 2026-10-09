package io.github.anshuman2401.hermesmobile.data.kanban

import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.HttpResponse
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonValue
import io.github.anshuman2401.hermesmobile.data.gateway.okhttp.OkHttpGatewayTransport
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val MAX_RESPONSE = 8 * 1024 * 1024
private class ResponseTooLarge : IOException()

/** Isolated no-retry, no-redirect transport; PATCH bodies are supported. */
class KanbanHttpTransport : AuthHttpTransport {
    private val client = OkHttpGatewayTransport.defaultClient().newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        withContext(Dispatchers.IO) {
            val parsed = url.toHttpUrl()
            val origin = CanonicalOrigin.parse("${parsed.scheme}://${parsed.host}:${parsed.port}").getOrThrow()
            require(origin.scheme.value == parsed.scheme && parsed.fragment == null)
            val payload = if (method in setOf("POST", "PUT", "PATCH"))
                (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()) else null
            val request = Request.Builder().url(parsed).apply { headers.forEach { (k, v) -> header(k, v) } }
                .method(method, payload).build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) throw SecurityException("Redirect rejected")
                val source = response.body
                if ((source?.contentLength() ?: -1) > MAX_RESPONSE) throw ResponseTooLarge()
                val bytes = source?.byteStream()?.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        if (out.size() + count > MAX_RESPONSE) throw ResponseTooLarge()
                        out.write(chunk, 0, count)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                HttpResponse(response.code, body = String(bytes, Charsets.UTF_8))
            }
        }
}

class KanbanHttpAdapter(private val vault: VaultStorage, private val transport: AuthHttpTransport,
    private val auth: AuthRepository? = null) : KanbanRepository {
    private val root = "/api/plugins/kanban"
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun selected(slug: String) = "?board=${enc(slug)}"
    private fun task(slug: String, id: String) = "$root/tasks/${enc(id)}${selected(slug)}"

    override suspend fun rememberedBoard(): String? {
        val id = vault.getActiveProfileId() ?: return null
        val origin = vault.getConnectionProfile(id)?.normalizedOrigin ?: return null
        return vault.getKanbanBoardSelection(id, origin)
    }

    override suspend fun rememberBoard(slug: String): Boolean {
        val id = vault.getActiveProfileId() ?: return false
        val origin = vault.getConnectionProfile(id)?.normalizedOrigin ?: return false
        return vault.saveKanbanBoardSelection(id, origin, slug)
    }

    override fun events(slug: String, cursor: Long): Flow<Long> = callbackFlow {
        val id = vault.getActiveProfileId()
        val profile = id?.let { vault.getConnectionProfile(it) }
        val credential = id?.let { vault.getAuthCredential(it) }
        val origin = credential?.canonicalOrigin?.let { CanonicalOrigin.parse(it).getOrNull() }
        val selectedOrigin = profile?.normalizedOrigin?.let { CanonicalOrigin.parse(it).getOrNull() }
        if (auth == null || id == null || profile == null || credential == null || origin == null ||
            selectedOrigin == null || !origin.isSameOrigin(selectedOrigin)) {
            close(); return@callbackFlow
        }
        val ticket = auth.mintWebSocketTicket(origin).getOrNull()
        if (ticket == null || vault.getActiveProfileId() != id || vault.getAuthCredential(id) != credential) {
            close(); return@callbackFlow
        }
        val url = origin.resolveWs("$root/events?board=${enc(slug)}&since=$cursor&ticket=${enc(ticket.value)}")
        val client = OkHttpGatewayTransport.defaultClient().newBuilder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 64 * 1024) { webSocket.close(1009, null); return }
                val next = GatewayJson.parseOrNull(text)?.get("cursor")?.asLongOrNull()
                if (next != null && next > cursor) trySend(next)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        awaitClose { socket.close(1000, null); client.dispatcher.executorService.shutdown() }
    }

    private suspend fun request(method: String, path: String, payload: Map<String, Any?>? = null): KanbanResult<JsonValue> {
        var sent = false
        return try {
            val partition = vault.getActiveProfileId() ?: return KanbanResult.Failure(KanbanFailure.AUTH_REQUIRED)
            val profile = vault.getConnectionProfile(partition) ?: return KanbanResult.Failure(KanbanFailure.AUTH_REQUIRED)
            val credential = vault.getAuthCredential(partition) ?: return KanbanResult.Failure(KanbanFailure.AUTH_REQUIRED)
            val origin = CanonicalOrigin.parse(credential.canonicalOrigin).getOrNull()
                ?: return KanbanResult.Failure(KanbanFailure.AUTH_REQUIRED)
            val currentOrigin = CanonicalOrigin.parse(profile.normalizedOrigin).getOrNull()
            if (currentOrigin == null || !origin.isSameOrigin(currentOrigin)) return KanbanResult.Failure(KanbanFailure.STALE)
            if (credential.cookies.isEmpty()) return KanbanResult.Failure(KanbanFailure.AUTH_REQUIRED)
            val headers = mapOf("Cookie" to credential.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
                "Accept" to "application/json", "Content-Type" to "application/json")
            sent = true
            val response = transport.execute(method, origin.resolveHttp(path), headers,
                payload?.let { GatewayJson.serialize(GatewayJson.fromAny(it)) })
            val still = vault.getConnectionProfile(partition)?.normalizedOrigin?.let { CanonicalOrigin.parse(it).getOrNull() }
            if (vault.getActiveProfileId() != partition || vault.getAuthCredential(partition) != credential ||
                still == null || !origin.isSameOrigin(still)) return KanbanResult.Failure(KanbanFailure.STALE)
            when (response.statusCode) {
                in 200..299 -> GatewayJson.parseOrNull(response.body)?.let { KanbanResult.Success(it) }
                    ?: KanbanResult.Failure(if (method == "GET") KanbanFailure.INVALID else KanbanFailure.UNKNOWN)
                401, 403 -> KanbanResult.Failure(KanbanFailure.AUTH_REQUIRED)
                404 -> KanbanResult.Failure(if (path.endsWith("/boards") || path.contains("/board?")) KanbanFailure.UNSUPPORTED else KanbanFailure.NOT_FOUND)
                400, 409, 422 -> KanbanResult.Failure(KanbanFailure.INVALID)
                405, 501 -> KanbanResult.Failure(KanbanFailure.UNSUPPORTED)
                else -> KanbanResult.Failure(if (method == "GET") KanbanFailure.UNAVAILABLE else KanbanFailure.UNKNOWN)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: ResponseTooLarge) { KanbanResult.Failure(KanbanFailure.TOO_LARGE) }
        catch (_: Exception) { KanbanResult.Failure(if (method != "GET" && sent) KanbanFailure.UNKNOWN else KanbanFailure.UNAVAILABLE) }
    }

    private fun card(value: JsonValue?): KanbanCard? {
        val row = value?.asObjectOrNull() ?: return null
        val id = row["id"]?.asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val title = row["title"]?.asStringOrNull() ?: return null
        val status = row["status"]?.asStringOrNull()?.takeIf { it in KanbanLanes.all || it == "archived" } ?: return null
        return KanbanCard(id, title, row["body"]?.asStringOrNull(), status,
            row["priority"]?.asIntOrNull() ?: 0, row["assignee"]?.asStringOrNull(),
            row["created_at"]?.asLongOrNull(), row["comment_count"]?.asIntOrNull() ?: 0,
            row["latest_summary"]?.asStringOrNull(), row["warnings"]?.get("count")?.asIntOrNull() ?: 0,
            row["model_override"]?.asStringOrNull(), row["provider_override"]?.asStringOrNull(),
            row["reasoning_effort"]?.asStringOrNull())
    }

    override suspend fun boards(): KanbanResult<Pair<List<KanbanBoardMeta>, String>> = when (val result = request("GET", "$root/boards")) {
        is KanbanResult.Failure -> result
        is KanbanResult.Success -> {
            val rows = result.value["boards"]?.asArrayOrNull()?.list
            val current = result.value["current"]?.asStringOrNull()
            val list = rows?.map { row ->
                val slug = row["slug"]?.asStringOrNull()?.takeIf { it.isNotBlank() } ?: return@map null
                KanbanBoardMeta(slug, row["name"]?.asStringOrNull()?.takeIf { it.isNotBlank() } ?: slug,
                    row["total"]?.asIntOrNull() ?: 0, row["is_current"]?.asBooleanOrNull() ?: false,
                    row["default_workspace_kind"]?.asStringOrNull(), row["default_workdir"]?.asStringOrNull())
            }
            if (list == null || list.any { it == null } || current == null) KanbanResult.Failure(KanbanFailure.INVALID)
            else KanbanResult.Success(list.filterNotNull() to current)
        }
    }

    override suspend fun board(slug: String): KanbanResult<KanbanBoard> = when (val result = request("GET", "$root/board${selected(slug)}")) {
        is KanbanResult.Failure -> result
        is KanbanResult.Success -> {
            val rows = result.value["columns"]?.asArrayOrNull()?.list
            val lanes = rows?.map { row ->
                val name = row["name"]?.asStringOrNull() ?: return@map null
                val cards = row["tasks"]?.asArrayOrNull()?.list?.map(::card) ?: return@map null
                if (name !in KanbanLanes.all && name != "archived" || cards.any { it == null }) null
                else KanbanLane(name, cards.filterNotNull())
            }
            val cursor = result.value["latest_event_id"]?.asLongOrNull()
            if (lanes == null || lanes.any { it == null } || cursor == null) KanbanResult.Failure(KanbanFailure.INVALID)
            else KanbanResult.Success(KanbanBoard(lanes.filterNotNull(), cursor))
        }
    }

    override suspend fun detail(slug: String, id: String): KanbanResult<KanbanDetail> = when (val result = request("GET", task(slug, id))) {
        is KanbanResult.Failure -> result
        is KanbanResult.Success -> {
            val value = card(result.value["task"])
            val comments = result.value["comments"]?.asArrayOrNull()?.list?.map { row ->
                val body = row["body"]?.asStringOrNull() ?: return@map null
                KanbanComment(row["author"]?.asStringOrNull() ?: "Hermes", body, row["created_at"]?.asLongOrNull())
            }
            if (value == null || value.id != id || comments == null || comments.any { it == null })
                KanbanResult.Failure(KanbanFailure.INVALID)
            else KanbanResult.Success(KanbanDetail(value, comments.filterNotNull()))
        }
    }

    override suspend fun catalog(slug: String): KanbanResult<KanbanCatalog> {
        val roster = request("GET", "$root/profiles")
        val models = request("GET", "$root/model-options")
        if (roster is KanbanResult.Failure) return roster
        if (models is KanbanResult.Failure) return models
        val profiles = (roster as KanbanResult.Success).value["profiles"]?.asArrayOrNull()?.list?.mapNotNull { it["name"]?.asStringOrNull() } ?: emptyList()
        val providers = (models as KanbanResult.Success).value["providers"]?.asArrayOrNull()?.list ?: emptyList()
        return KanbanResult.Success(KanbanCatalog(profiles, providers.mapNotNull { row ->
            val name = row["slug"]?.asStringOrNull() ?: return@mapNotNull null
            name to (row["models"]?.asArrayOrNull()?.list?.mapNotNull { it.asStringOrNull() } ?: emptyList())
        }.toMap()))
    }

    override suspend fun create(slug: String, draft: KanbanCreate): KanbanResult<KanbanCard> {
        if (draft.title.isBlank()) return KanbanResult.Failure(KanbanFailure.INVALID)
        val payload = linkedMapOf<String, Any?>("title" to draft.title.trim(), "body" to draft.body,
            "priority" to draft.priority, "triage" to true,
            "workspace_kind" to draft.workspaceKind, "workspace_path" to draft.workspacePath,
            "skills" to draft.skills, "parents" to listOfNotNull(draft.parent),
            "model_override" to draft.model, "provider_override" to draft.provider,
            "reasoning_effort" to draft.reasoningEffort, "goal_mode" to draft.goalMode,
            "idempotency_key" to draft.idempotencyKey)
        if (draft.assignee != null) payload["assignee"] = draft.assignee
        return when (val result = request("POST", "$root/tasks${selected(slug)}", payload)) {
            is KanbanResult.Failure -> result
            is KanbanResult.Success -> card(result.value["task"])?.let { KanbanResult.Success(it) }
                ?: KanbanResult.Failure(KanbanFailure.UNKNOWN)
        }
    }

    override suspend fun patch(slug: String, id: String, fields: Map<String, Any?>): KanbanResult<KanbanCard> {
        if (fields.isEmpty() || fields.keys.any { it !in setOf("title", "body", "priority", "assignee", "status",
                "block_reason", "summary", "model_override", "provider_override", "reasoning_effort",
                "clear_model_override", "clear_reasoning_effort") }) return KanbanResult.Failure(KanbanFailure.INVALID)
        return when (val result = request("PATCH", task(slug, id), fields)) {
            is KanbanResult.Failure -> result
            is KanbanResult.Success -> card(result.value["task"])?.takeIf { it.id == id }?.let { KanbanResult.Success(it) }
                ?: KanbanResult.Failure(KanbanFailure.UNKNOWN)
        }
    }

    override suspend fun comment(slug: String, id: String, body: String): KanbanResult<Unit> {
        if (body.isBlank()) return KanbanResult.Failure(KanbanFailure.INVALID)
        return when (val result = request("POST", "$root/tasks/${enc(id)}/comments${selected(slug)}",
            mapOf("author" to "android", "body" to body.trim()))) {
            is KanbanResult.Failure -> result
            is KanbanResult.Success -> KanbanResult.Success(Unit)
        }
    }

    override suspend fun delete(slug: String, id: String): KanbanResult<Unit> =
        when (val result = request("DELETE", task(slug, id))) {
            is KanbanResult.Failure -> result
            is KanbanResult.Success -> KanbanResult.Success(Unit)
        }
}
