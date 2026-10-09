package com.hermes.bot.data.history

import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.StaleAuthenticatedResponseException
import com.hermes.bot.data.gateway.json.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

interface StoredHistoryProtocolAdapter {
    suspend fun fetchPage(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int = 0): Result<StoredHistoryPage>
    suspend fun fetchPageForOpen(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int = 0): Result<StoredHistoryCandidate> =
        Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE))
}

class DefaultStoredHistoryProtocolAdapter(
    private val auth: AuthRepository,
    private val transport: StoredHistoryHttpTransport
) : StoredHistoryProtocolAdapter {
    override suspend fun fetchPage(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int): Result<StoredHistoryPage> =
        fetch(scope, budget, offset, holdCandidate = false)

    override suspend fun fetchPageForOpen(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int): Result<StoredHistoryCandidate> =
        fetch(scope, budget, offset, holdCandidate = true).map { StoredHistoryCandidate(it) }

    private suspend fun fetch(scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int, holdCandidate: Boolean): Result<StoredHistoryPage> {
        synchronized(budget) {
            if (budget.inUse || budget.terminal || budget.scope !== scope || budget.successfulPages >= 5)
                return Result.failure(StoredHistoryException(StoredHistoryFailure.BUDGET))
            budget.inUse = true
        }
        var decision: StoredHistoryHeaderDecision? = null
        try {
            check(offset >= 0 && offset <= Int.MAX_VALUE - 120)
            checkIdentity(scope, budget)
            val path = scope.origin.resolveHttp("/api/sessions").toHttpUrl().newBuilder()
                .addPathSegment(scope.requestedSessionId).addPathSegment("messages").build().encodedPath
            val remaining = budget.remainingMillis()
            if (remaining <= 0) throw StoredHistoryException(StoredHistoryFailure.DEADLINE)
            return withTimeout(remaining) {
                while (true) {
                    checkIdentity(scope, budget)
                    if (budget.remainingMillis() <= 0) throw StoredHistoryException(StoredHistoryFailure.DEADLINE)
                    val limit = budget.acceptedLimit
                    decision = budget.beginHeaders()
                    val exchange = auth.exchangeAuthenticated(scope.origin, path, budget.receipt) { headers ->
                        if (budget.remainingMillis() <= 0) throw StoredHistoryException(StoredHistoryFailure.DEADLINE)
                        budget.httpAttempts++
                        transport.open(scope.origin, path, scope.remoteProfile, limit, offset, headers, budget.remainingMillis())
                    }.getOrThrow()
                    // Only this exchange's accepted successor can authorize a fallback or later page.
                    budget.receipt = exchange.receipt
                    exchange.response.use { response ->
                        if (response.statusCode !in 200..299) {
                            decision!!.complete(StoredHistoryHeaderState.DENIED)
                            throw StoredHistoryException(StoredHistoryFailure.UNAVAILABLE)
                        }
                        decision!!.complete(StoredHistoryHeaderState.ACCEPTED, exchange.receipt)
                        val bytes = try { response.readBoundedBody() } catch (failure: StoredHistoryException) {
                            checkIdentity(scope, budget)
                            if (failure.failure != StoredHistoryFailure.OVERSIZE || limit == 1) throw failure
                            budget.acceptedLimit = if (limit == 120) 30 else 1
                            return@use
                        }
                        val page = withContext(Dispatchers.Default) { decode(bytes, scope, budget, limit, offset, holdCandidate) }
                        checkIdentity(scope, budget)
                        currentCoroutineContext().ensureActive()
                        if (budget.remainingMillis() <= 0) throw StoredHistoryException(StoredHistoryFailure.DEADLINE)
                        budget.successfulPages++
                        return@withTimeout Result.success(page)
                    }
                }
                @Suppress("UNREACHABLE_CODE") Result.failure<StoredHistoryPage>(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE))
            }
        } catch (failure: TimeoutCancellationException) {
            decision?.complete(StoredHistoryHeaderState.DEADLINE)
            budget.terminal = true
            currentCoroutineContext().ensureActive()
            return Result.failure(StoredHistoryException(StoredHistoryFailure.DEADLINE))
        } catch (cancelled: CancellationException) {
            decision?.complete(StoredHistoryHeaderState.CANCELLED)
            budget.terminal = true
            throw cancelled
        } catch (failure: Exception) {
            decision?.complete(if (failure is SecurityException || failure is StaleAuthenticatedResponseException)
                StoredHistoryHeaderState.DENIED else StoredHistoryHeaderState.FAILED)
            budget.terminal = true
            return Result.failure(when (failure) {
                is StoredHistoryException -> failure
                is StaleAuthenticatedResponseException -> StoredHistoryException(StoredHistoryFailure.STALE)
                else -> StoredHistoryException(StoredHistoryFailure.UNAVAILABLE)
            })
        } finally {
            decision?.complete(StoredHistoryHeaderState.FAILED)
            synchronized(budget) { budget.inUse = false }
        }
    }

    private suspend fun checkIdentity(scope: StoredHistoryScope, budget: StoredHistoryBudget) {
        if (!scope.current || !auth.isResponseCurrent(budget.receipt))
            throw StoredHistoryException(StoredHistoryFailure.STALE)
    }

    private suspend fun decode(bytes: ByteArray, scope: StoredHistoryScope, budget: StoredHistoryBudget, limit: Int, offset: Int, holdCandidate: Boolean): StoredHistoryPage {
        if (holdCandidate && bytes.size > OkHttpStoredHistoryTransport.MAX_BODY_BYTES)
            throw StoredHistoryException(StoredHistoryFailure.OVERSIZE)
        val context = currentCoroutineContext()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = StoredJsonReader(text) {
            context.ensureActive()
            if (budget.remainingMillis() <= 0) throw StoredHistoryException(StoredHistoryFailure.DEADLINE)
        }.read() as? JsonObject ?: integrity()
        if (root["profile"]?.asStringOrNull() != scope.remoteProfile) integrity()
        val resolved = root["session_id"]?.asStringOrNull() ?: integrity()
        if (holdCandidate && (resolved.isBlank() || resolved != resolved.trim() || resolved.length > 256 ||
            resolved == "." || resolved.contains("..") || resolved.any { it.isISOControl() || it in "\\/" || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' })) integrity()
        if (!holdCandidate && resolved != scope.requestedSessionId)
            throw StoredHistoryException(StoredHistoryFailure.UNBOUND_DESCENDANT, scope.requestedSessionId, resolved)
        val rows = root["messages"] as? JsonArray ?: integrity()
        val pagination = root["pagination"] as? JsonObject ?: integrity()
        if (number(pagination["limit"]) != limit.toLong() || number(pagination["offset"]) != offset.toLong() ||
            pagination["order"]?.asStringOrNull() != "latest" || number(pagination["returned"]) != rows.list.size.toLong() ||
            rows.list.size > limit) integrity()
        val ids = HashSet<Long>()
        val decoded = rows.list.map { value ->
            val row = value as? JsonObject ?: integrity()
            val id = number(row["id"])?.takeIf { it > 0 && ids.add(it) } ?: integrity()
            if (row["session_id"]?.asStringOrNull() != resolved) integrity()
            val role = row["role"]?.asStringOrNull()?.takeIf { it.isNotBlank() && it.codePointCount(0, it.length) <= 128 } ?: integrity()
            val retained = linkedMapOf<String, JsonValue>()
            var truncated = false
            for (name in ROW_FIELDS) if (row.map.containsKey(name)) {
                val bounded = BoundedStoredValue(if (name == "args") 4096 else 16384, name == "tool_calls")
                retained[name] = bounded.copy(row.map.getValue(name), field = name)
                truncated = truncated || bounded.truncated
            }
            val canonical = JsonObject(linkedMapOf("id" to JsonNumber(id), "role" to JsonString(role)) + retained)
            StoredHistoryRow(id, role, retained.toMap(), GatewayJson.serialize(canonical).toByteArray(Charsets.UTF_8).size, truncated,
                if (role == "user") com.hermes.bot.data.gateway.MessageEditPolicy.target(row.map, id,
                    retained["content"]?.asStringOrNull().orEmpty(), truncated) else null)
        }
        if (holdCandidate && (decoded.size > 4096 || decoded.sumOf { it.canonicalBytes.toLong() } > 8 * 1024 * 1024))
            throw StoredHistoryException(StoredHistoryFailure.OVERSIZE)
        return StoredHistoryPage(scope, scope.requestedSessionId, resolved, offset, limit, decoded)
    }

    private fun number(value: JsonValue?): Long? = (value as? JsonNumber)?.takeIf { it.isInteger }?.longValue
    private fun integrity(): Nothing = throw StoredHistoryException(StoredHistoryFailure.INTEGRITY)

    companion object {
        private val ROW_FIELDS = setOf("content", "result", "args", "tool_calls", "tool_call_id", "tool_name",
            "timestamp", "reasoning", "reasoning_content", "reasoning_details", "codex_reasoning_items",
            "codex_message_items", "display_kind", "display_content", "display_metadata", "tool_call_labels",
            "finish_reason", "effect_disposition", "_compressed_summary")
    }
}

/** Canonical retained payload for future window accounting; never a raw-wire cache. */
private class BoundedStoredValue(private val stringBytes: Int, private val callIds: Boolean) {
    var truncated = false
    private var values = 0
    fun copy(value: JsonValue, depth: Int = 0, field: String = ""): JsonValue {
        if (++values > 256 || depth > 8) { truncated = true; return JsonString("[Truncated]") }
        return when (value) {
            is JsonString -> {
                // Correlation identity is opaque: display truncation must never create a shared prefix ID.
                if ((field == "tool_call_id" && depth == 0) || (callIds && field == "id" && depth == 2)) return value
                val output = StringBuilder(); var bytes = 0
                var index = 0; var points = 0
                val pointLimit = when (field) { "name", "tool_name" -> 128; "context" -> 512; else -> Int.MAX_VALUE }
                val byteLimit = if (field == "args" || field == "arguments") 4096 else stringBytes
                while (index < value.value.length) {
                    val cp = value.value.codePointAt(index)
                    val size = when { cp <= 127 -> 1; cp <= 2047 -> 2; cp <= 65535 -> 3; else -> 4 }
                    if (bytes + size > byteLimit || points >= pointLimit) { truncated = true; break }
                    output.appendCodePoint(cp); bytes += size; points++; index += Character.charCount(cp)
                }
                JsonString(output.toString())
            }
            is JsonArray -> {
                val children = ArrayList<JsonValue>()
                for (child in value.list) {
                    if (values >= 256) { truncated = true; break }
                    children.add(copy(child, depth + 1, field))
                }
                JsonArray(children)
            }
            is JsonObject -> {
                val children = linkedMapOf<String, JsonValue>()
                for ((key, child) in value.map.entries.take(256)) {
                    if (values >= 256) { truncated = true; break }
                    if (key.codePointCount(0, key.length) > 128) { truncated = true; continue }
                    children[key] = copy(child, depth + 1, key)
                }
                if (value.map.size > 256) truncated = true
                JsonObject(children)
            }
            else -> value
        }
    }
}

/** Strict bounded parser: depth checked before descent, duplicate properties and invalid JSON rejected. */
private class StoredJsonReader(private val source: String, private val checkpoint: () -> Unit) {
    private var index = 0
    private fun bad(): Nothing = throw StoredHistoryException(StoredHistoryFailure.INTEGRITY)
    private fun whitespace() {
        while (index < source.length && source[index] in " \r\n\t") {
            index++; if (index % 1024 == 0) checkpoint()
        }
    }
    private fun eat(char: Char): Boolean { whitespace(); if (index < source.length && source[index] == char) { index++; return true }; return false }
    fun read(): JsonValue { val value = value(0); whitespace(); if (index != source.length) bad(); return value }
    private fun value(depth: Int): JsonValue {
        checkpoint(); whitespace(); if (index >= source.length) bad()
        return when (source[index]) {
            '{', '[' -> {
                if (depth >= 32) throw StoredHistoryException(StoredHistoryFailure.DEPTH)
                if (eat('{')) {
                    val fields = linkedMapOf<String, JsonValue>()
                    if (!eat('}')) do {
                        whitespace(); val key = string()
                        if (fields.containsKey(key) || !eat(':')) bad()
                        fields[key] = value(depth + 1)
                        if (eat('}')) break
                        if (!eat(',')) bad()
                    } while (true)
                    JsonObject(fields)
                } else {
                    if (!eat('[')) bad()
                    val values = ArrayList<JsonValue>()
                    if (!eat(']')) do {
                        values.add(value(depth + 1))
                        if (eat(']')) break
                        if (!eat(',')) bad()
                    } while (true)
                    JsonArray(values)
                }
            }
            '"' -> JsonString(string())
            't' -> literal("true", JsonBoolean(true))
            'f' -> literal("false", JsonBoolean(false))
            'n' -> literal("null", JsonNull)
            else -> {
                val start = index
                while (index < source.length && source[index] in "-+0123456789.eE") index++
                val raw = source.substring(start, index)
                if (!NUMBER.matches(raw)) bad()
                val numeric = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: bad()
                JsonNumber(numeric, raw)
            }
        }
    }
    private fun literal(token: String, value: JsonValue): JsonValue {
        if (!source.startsWith(token, index)) bad(); index += token.length; return value
    }
    private fun string(): String {
        if (index >= source.length || source[index++] != '"') bad()
        val output = StringBuilder()
        while (index < source.length) {
            if (index % 1024 == 0) checkpoint()
            val char = source[index++]
            if (char == '"') return output.toString()
            if (char.code < 32) bad()
            if (char != '\\') { output.append(char); continue }
            if (index >= source.length) bad()
            output.append(when (val escaped = source[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                'u' -> {
                    if (index + 4 > source.length) bad()
                    val hex = source.substring(index, index + 4)
                    if (!hex.all { it in "0123456789abcdefABCDEF" }) bad()
                    index += 4; hex.toInt(16).toChar()
                }
                else -> bad()
            })
        }
        bad()
    }
    companion object { private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") }
}
