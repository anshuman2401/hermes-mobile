package com.hermes.bot.data.insights

import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.StaleAuthenticatedResponseException
import com.hermes.bot.data.gateway.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Only this layer knows endpoints and host response shapes. */
class HostInsightsAdapter(private val auth: AuthRepository, private val transport: HostInsightsTransport = OkHttpHostInsightsTransport()) {
    private suspend fun <T> read(scope: InsightsScope, route: String, query: Map<String, String> = emptyMap(), scoped: Boolean = true, decode: (JsonObject) -> T): InsightsRead<T> {
        return try {
            val url = scope.origin.resolveHttp(route).toHttpUrl().newBuilder().apply {
                if (scoped) addQueryParameter("profile", scope.profile)
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            val exchange = auth.exchangeAuthenticated(scope.origin, path, scope.receipt) { headers -> transport.open(scope.origin, path, headers) }.getOrThrow()
            scope.receipt = exchange.receipt
            exchange.response.use { response ->
                when (response.statusCode) {
                    in 300..399 -> return InsightsRead.Failed(InsightsFailure.ORIGIN)
                    401, 403 -> return InsightsRead.Failed(InsightsFailure.AUTH)
                    404, 405, 501 -> return InsightsRead.Failed(InsightsFailure.UNSUPPORTED)
                    !in 200..299 -> return InsightsRead.Failed(InsightsFailure.UNAVAILABLE)
                }
                val bytes = response.body()
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                val root = GatewayJson.parseOrNull(text) as? JsonObject ?: return InsightsRead.Failed(InsightsFailure.MALFORMED)
                val result = decode(root)
                currentCoroutineContext().ensureActive()
                if (!auth.isResponseCurrent(exchange.receipt)) InsightsRead.Failed(InsightsFailure.STALE) else InsightsRead.Ready(result)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: StaleAuthenticatedResponseException) { InsightsRead.Failed(InsightsFailure.STALE) }
          catch (_: SecurityException) { InsightsRead.Failed(InsightsFailure.AUTH) }
          catch (failure: IllegalStateException) {
              InsightsRead.Failed(if (failure.message == "Authenticated redirect rejected") InsightsFailure.ORIGIN else InsightsFailure.UNAVAILABLE)
          }
          catch (_: IllegalArgumentException) { InsightsRead.Failed(InsightsFailure.MALFORMED) }
          catch (_: java.nio.charset.CharacterCodingException) { InsightsRead.Failed(InsightsFailure.MALFORMED) }
          catch (_: Exception) { InsightsRead.Failed(InsightsFailure.UNAVAILABLE) }
    }
    suspend fun status(scope: InsightsScope): InsightsRead<HostStatusOverview> = read(scope, "/api/status") { root ->
        require(root.map.containsKey("version") || root.map.containsKey("gateway_running"))
        val components = root["components"]?.asObjectOrNull()?.map?.mapNotNull { (name, value) ->
            val status = value["status"]?.asStringOrNull()?.takeIf { it in setOf("ok", "degraded", "unavailable", "error", "warning") }
            if (status == null) null else safeName(name) to status
        } ?: emptyList()
        HostStatusOverview(root["version"]?.asStringOrNull()?.let(::safeName),
            root["gateway_state"]?.asStringOrNull()?.takeIf { it in setOf("running", "stopped", "starting", "failed", "connected", "disconnected") }
                ?: root["gateway_running"]?.asBooleanOrNull()?.let { if (it) "Running" else "Stopped" },
            root["overall"]?.asStringOrNull()?.takeIf { it in setOf("ok", "degraded") }, root.number("active_sessions"), root.number("active_agents"), components)
    }
    suspend fun system(scope: InsightsScope): InsightsRead<HostSystemOverview> = read(scope, "/api/system/stats", scoped = false) { root ->
        require(root.map.containsKey("hermes_version") || root.map.containsKey("cpu_count") || root.map.containsKey("uptime_seconds"))
        HostSystemOverview(listOf(Metric("Host uptime", root.number("uptime_seconds"), "seconds"), Metric("CPU", root.number("cpu_percent"), "%"),
            Metric("Memory used", root["memory"]?.asObjectOrNull()?.number("used"), "bytes"), Metric("Memory total", root["memory"]?.asObjectOrNull()?.number("total"), "bytes"),
            Metric("Disk used", root["disk"]?.asObjectOrNull()?.number("used"), "bytes"), Metric("Disk total", root["disk"]?.asObjectOrNull()?.number("total"), "bytes")))
    }
    suspend fun usage(scope: InsightsScope, days: Int): InsightsRead<HostUsage> = read(scope, "/api/analytics/usage", mapOf("days" to days.toString())) { root ->
        val totals = root["totals"]?.asObjectOrNull() ?: throw IllegalArgumentException()
        require(root.number("period_days") == days.toDouble())
        val metrics = listOf("Sessions" to "total_sessions", "API calls" to "total_api_calls", "Input tokens" to "total_input", "Output tokens" to "total_output",
            "Cache read tokens" to "total_cache_read", "Reasoning tokens" to "total_reasoning", "Estimated cost" to "total_estimated_cost", "Actual cost" to "total_actual_cost")
            .map { (label, key) -> Metric(label, totals.number(key), if (key.contains("cost")) "USD" else "") }
        val daily = root.array("daily", required = true).map { value ->
            val obj = value.asObjectOrNull() ?: throw IllegalArgumentException()
            val day = obj["day"]?.asStringOrNull()?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: throw IllegalArgumentException()
            DailyUsage(day, obj.number("input_tokens"), obj.number("output_tokens"))
        }
        HostUsage(days, metrics, daily, rows(root.array("by_model", true), "model", tokens = true),
            rows(root.array("by_task"), "task", tokens = true), rows(root["skills"]?.asObjectOrNull()?.array("top_skills") ?: emptyList(), "skill", "total_count"),
            rows(root.array("tools"), "tool", "count"))
    }
    suspend fun logs(scope: InsightsScope, file: HostLogFile, level: HostLogLevel): InsightsRead<HostLogTail> = read(scope, "/api/logs", buildMap {
        put("file", file.wire); put("lines", "200"); if (level != HostLogLevel.ALL) put("level", level.wire)
    }) { root ->
        require(root["file"]?.asStringOrNull() == file.wire)
        val lines = root.array("lines", true)
        require(lines.size <= 200)
        HostLogTail(lines.map { (it as? JsonString)?.value ?: throw IllegalArgumentException() })
    }
    private fun rows(values: List<JsonValue>, key: String, count: String = "", tokens: Boolean = false): List<UsageRow> = values.map { value ->
        val row = value.asObjectOrNull() ?: throw IllegalArgumentException()
        val name = row[key]?.asStringOrNull() ?: throw IllegalArgumentException()
        val amount = if (tokens) {
            val input = row.number("input_tokens"); val output = row.number("output_tokens")
            if (input == null && output == null) null else (input ?: 0.0) + (output ?: 0.0)
        } else row.number(count)
        UsageRow(safeName(name), amount, row.number("estimated_cost"))
    }
    private fun safeName(value: String) = value.take(160).filter { it.isLetterOrDigit() || it in " ._:/-" }
    private fun JsonObject.number(key: String): Double? {
        val value = this[key]
        if (value == null || value == JsonNull) return null
        val number = (value as? JsonNumber)?.value ?: throw IllegalArgumentException()
        require(number.isFinite() && number >= 0)
        return number
    }
    private fun JsonObject.array(key: String, required: Boolean = false): List<JsonValue> {
        val value = this[key]
        if (!required && (value == null || value == JsonNull)) return emptyList()
        return (value as? JsonArray)?.list ?: throw IllegalArgumentException()
    }
}
