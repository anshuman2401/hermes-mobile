package com.hermes.bot.data.update

import com.hermes.bot.data.auth.AuthOriginPolicyRejectedException
import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.AuthSessionRejectedException
import com.hermes.bot.data.auth.StaleAuthenticatedResponseException
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonArray
import com.hermes.bot.data.gateway.json.JsonObject
import com.hermes.bot.data.insights.InsightsResponse
import com.hermes.bot.data.insights.InsightsScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Host update wire contract. No raw server text or action logs reach presentation. */
class HostUpdateAdapter(
    private val auth: AuthRepository,
    private val transport: HostUpdateTransport = OkHttpHostUpdateTransport()
) {
    suspend fun check(scope: InsightsScope): HostUpdateResult<HostUpdateCheck> {
        val url = scope.origin.resolveHttp("/api/hermes/update/check").toHttpUrl().newBuilder()
            .addQueryParameter("force", "true").addQueryParameter("profile", scope.profile).build()
        return request(scope, url.encodedPath + "?" + url.encodedQuery, "GET", ::decodeCheck)
    }

    suspend fun start(scope: InsightsScope): HostUpdateResult<HostUpdateStart> =
        request(scope, "/api/hermes/update", "POST", ::decodeStart)

    suspend fun status(scope: InsightsScope): HostUpdateResult<HostUpdateStatus> =
        request(scope, "/api/actions/hermes-update/status?lines=1", "GET", ::decodeStatus)

    private suspend fun <T> request(
        scope: InsightsScope,
        path: String,
        method: String,
        decode: (JsonObject) -> T
    ): HostUpdateResult<T> = try {
        val exchange = auth.exchangeAuthenticated(scope.origin, path, scope.receipt) { headers ->
            transport.open(scope.origin, path, method, headers)
        }.getOrThrow()
        scope.receipt = exchange.receipt
        exchange.response.use { response: InsightsResponse ->
            when (response.statusCode) {
                in 300..399 -> return HostUpdateResult.Failed(HostUpdateFailure.ORIGIN)
                401, 403 -> return HostUpdateResult.Failed(HostUpdateFailure.AUTH)
                404, 405, 501 -> return HostUpdateResult.Failed(HostUpdateFailure.UNSUPPORTED)
                !in 200..299 -> return HostUpdateResult.Failed(if (method == "POST" && response.statusCode in 400..499)
                    HostUpdateFailure.REFUSED else if (method == "POST") HostUpdateFailure.UNKNOWN else HostUpdateFailure.UNAVAILABLE)
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val root = GatewayJson.parseOrNull(decoder.decode(ByteBuffer.wrap(response.body())).toString()) as? JsonObject
                ?: return HostUpdateResult.Failed(if (method == "POST") HostUpdateFailure.UNKNOWN else HostUpdateFailure.MALFORMED)
            val value = decode(root)
            currentCoroutineContext().ensureActive()
            if (!auth.isResponseCurrent(exchange.receipt)) HostUpdateResult.Failed(if (method == "POST") HostUpdateFailure.UNKNOWN else HostUpdateFailure.STALE)
            else HostUpdateResult.Ready(value)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: HostUpdateRefusedException) { HostUpdateResult.Failed(HostUpdateFailure.REFUSED) }
      catch (_: AuthOriginPolicyRejectedException) { HostUpdateResult.Failed(HostUpdateFailure.ORIGIN) }
      catch (_: StaleAuthenticatedResponseException) { HostUpdateResult.Failed(if (method == "POST") HostUpdateFailure.UNKNOWN else HostUpdateFailure.STALE) }
      catch (_: AuthSessionRejectedException) { HostUpdateResult.Failed(HostUpdateFailure.AUTH) }
      catch (_: SecurityException) { HostUpdateResult.Failed(HostUpdateFailure.AUTH) }
      catch (_: Exception) { HostUpdateResult.Failed(if (method == "POST") HostUpdateFailure.UNKNOWN else HostUpdateFailure.UNAVAILABLE) }

    private fun decodeCheck(root: JsonObject): HostUpdateCheck {
        val method = root["install_method"]?.asStringOrNull()?.takeIf {
            it in setOf("apt", "git", "docker", "nix", "nixos", "unknown", "desktop-app", "managed-runtime")
        } ?: throw IllegalArgumentException()
        val version = root["current_version"]?.asStringOrNull()?.safeLabel(64) ?: throw IllegalArgumentException()
        val available = root["update_available"]?.asBooleanOrNull() ?: throw IllegalArgumentException()
        val canApply = root["can_apply"]?.asBooleanOrNull() ?: throw IllegalArgumentException()
        val behind = root["behind"]?.let { value ->
            if (value == com.hermes.bot.data.gateway.json.JsonNull) null
            else value.asIntOrNull()?.takeIf { it >= -1 } ?: throw IllegalArgumentException()
        }
        val changes = when (val value = root["commits"]) {
            null, com.hermes.bot.data.gateway.json.JsonNull -> emptyList()
            is JsonArray -> {
                require(value.list.size <= 20)
                value.list.map { (it as? JsonObject)?.get("summary")?.asStringOrNull()?.safeLabel(180) ?: throw IllegalArgumentException() }
            }
            else -> throw IllegalArgumentException()
        }
        return HostUpdateCheck(version, available, behind, canApply, method, changes)
    }

    private fun decodeStart(root: JsonObject): HostUpdateStart {
        if (root["ok"]?.asBooleanOrNull() == false) throw HostUpdateRefusedException()
        require(root["ok"]?.asBooleanOrNull() == true)
        val id = root["action_id"]?.asStringOrNull() ?: throw IllegalArgumentException()
        require(id.matches(Regex("[0-9a-f]{32}")))
        return HostUpdateStart(id)
    }

    private fun decodeStatus(root: JsonObject): HostUpdateStatus {
        require(root["name"]?.asStringOrNull() == "hermes-update")
        val running = root["running"]?.asBooleanOrNull() ?: throw IllegalArgumentException()
        val id = root["action_id"]?.asStringOrNull()?.also { require(it.matches(Regex("[0-9a-f]{32}"))) }
        val outcome = root["receipt"]?.asObjectOrNull()?.get("outcome")?.asStringOrNull()
            ?.also { require(it in setOf("running", "success", "partial", "failed", "refused")) }
        return HostUpdateStatus(running, id, outcome)
    }

    private fun String.safeLabel(max: Int): String = trim().take(max).map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().also { require(it.isNotBlank()) }
}

private class HostUpdateRefusedException : Exception()
