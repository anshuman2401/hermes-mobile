package com.hermes.bot.data.cron

import com.hermes.bot.data.auth.AuthHttpTransport
import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder

/** No raw errors, paths, response bodies or task content are logged. */
class CronHttpAdapter(private val vault: VaultStorage, private val transport: AuthHttpTransport) : CronRepository {
    private class Access(val credential: AuthCredential, val partition: String)
    @Volatile private var listedAccess: Access? = null
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun path(job: CronJob, suffix: String = "") =
        "/api/cron/jobs/${encode(job.id)}$suffix?profile=${encode(job.profile)}"

    private suspend fun request(method: String, path: String, body: JsonObject? = null): CronResult<JsonValue> {
        val write = method != "GET"
        var attempted = false
        return try {
            val partition = vault.getActiveProfileId() ?: return CronResult.Failure(CronFailure.AUTH_REQUIRED)
            val credential = vault.getAuthCredential(partition) ?: return CronResult.Failure(CronFailure.AUTH_REQUIRED)
            if (path != "/api/cron/jobs?profile=all") {
                val access = listedAccess
                if (access == null || access.partition != partition || access.credential != credential) return CronResult.Failure(CronFailure.STALE)
            }
            val origin = CanonicalOrigin.parse(credential.canonicalOrigin).getOrNull()
                ?: return CronResult.Failure(CronFailure.AUTH_REQUIRED)
            val selectedOrigin = vault.getConnectionProfile(partition)?.normalizedOrigin?.let { CanonicalOrigin.parse(it).getOrNull() }
            if (selectedOrigin == null || !origin.isSameOrigin(selectedOrigin)) return CronResult.Failure(CronFailure.STALE)
            if (credential.cookies.isEmpty()) return CronResult.Failure(CronFailure.AUTH_REQUIRED)
            val headers = mapOf("Cookie" to credential.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }, "Accept" to "application/json")
            attempted = true
            val response = transport.execute(method, origin.resolveHttp(path), headers, body?.let(GatewayJson::serialize))
            val currentOrigin = vault.getConnectionProfile(partition)?.normalizedOrigin?.let { CanonicalOrigin.parse(it).getOrNull() }
            if (vault.getActiveProfileId() != partition || vault.getAuthCredential(partition) != credential || currentOrigin == null || !origin.isSameOrigin(currentOrigin)) {
                return CronResult.Failure(CronFailure.STALE)
            }
            when (response.statusCode) {
                in 200..299 -> {
                    val parsed = GatewayJson.parseOrNull(response.body)
                    if (parsed == null) CronResult.Failure(if (write) CronFailure.UNKNOWN else CronFailure.INVALID)
                    else {
                        if (path == "/api/cron/jobs?profile=all" && parsed.asArrayOrNull()?.list?.all { parseJob(it) != null } == true) listedAccess = Access(credential, partition)
                        CronResult.Success(parsed)
                    }
                }
                401, 403 -> CronResult.Failure(CronFailure.AUTH_REQUIRED)
                404 -> CronResult.Failure(if (path.startsWith("/api/cron/jobs?")) CronFailure.UNSUPPORTED else CronFailure.NOT_FOUND)
                400, 422 -> CronResult.Failure(CronFailure.INVALID)
                405, 501 -> CronResult.Failure(CronFailure.UNSUPPORTED)
                else -> CronResult.Failure(if (write && response.statusCode >= 500) CronFailure.UNKNOWN else CronFailure.UNAVAILABLE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CronResult.Failure(if (write && attempted) CronFailure.UNKNOWN else CronFailure.UNAVAILABLE)
        }
    }

    override suspend fun list(): CronResult<List<CronJob>> = when (val result = request("GET", "/api/cron/jobs?profile=all")) {
        is CronResult.Failure -> result
        is CronResult.Success -> {
            val rows = result.value.asArrayOrNull()?.list
            val jobs = rows?.map { parseJob(it) }
            if (jobs == null || jobs.any { it == null } || jobs.filterNotNull().distinctBy { it.profile to it.id }.size != jobs.size) CronResult.Failure(CronFailure.INVALID)
            else CronResult.Success(jobs.filterNotNull())
        }
    }

    override suspend fun runs(job: CronJob): CronResult<List<CronRun>> {
        val ownership = checkOwnership(job)
        if (ownership is CronResult.Failure) return ownership
        return when (val result = request("GET", path(job, "/runs") + "&limit=20")) {
            is CronResult.Failure -> result
            is CronResult.Success -> {
                val rows = result.value["runs"]?.asArrayOrNull()?.list
                if (rows == null || rows.any { it !is JsonObject }) CronResult.Failure(CronFailure.INVALID)
                else if (rows.any { it["profile"]?.asStringOrNull()?.let { owner -> owner != job.profile } == true }) CronResult.Failure(CronFailure.STALE)
                else CronResult.Success(rows.take(20).map { CronRun(it["started_at"]?.asDoubleOrNull(), it["is_active"]?.asBooleanOrNull() == true) })
            }
        }
    }

    override suspend fun save(original: CronJob?, profile: String, name: String, prompt: String, schedule: String): CronResult<Unit> {
        if (profile.isBlank() || profile.equals("all", true) || name.isBlank() || schedule.isBlank() || (original == null && prompt.isBlank())) {
            return CronResult.Failure(CronFailure.INVALID)
        }
        if (original != null && original.profile != profile) return CronResult.Failure(CronFailure.INVALID)
        if (original != null) {
            val ownership = checkOwnership(original)
            if (ownership is CronResult.Failure) return ownership
        }
        val fields = mutableMapOf<String, JsonValue>()
        if (original == null || original.name != name) fields["name"] = JsonString(name)
        if (original == null || original.prompt != prompt) fields["prompt"] = JsonString(prompt)
        if (original == null || original.schedule != schedule) fields["schedule"] = JsonString(schedule)
        if (original == null) fields["deliver"] = JsonString("local")
        if (fields.isEmpty()) return CronResult.Success(Unit)
        val result = if (original == null) request("POST", "/api/cron/jobs?profile=${encode(profile)}", JsonObject(fields))
        else request("PUT", path(original), JsonObject("updates" to JsonObject(fields)))
        return result.unitResult(expected = original, owner = profile)
    }

    override suspend fun act(job: CronJob, action: CronAction): CronResult<Unit> {
        val ownership = checkOwnership(job)
        if (ownership is CronResult.Failure) return ownership
        return when (action) {
            CronAction.DELETE -> request("DELETE", path(job))
            CronAction.PAUSE -> request("POST", path(job, "/pause"))
            CronAction.RESUME -> request("POST", path(job, "/resume"))
        }.unitResult(expected = job, owner = job.profile, deletion = action == CronAction.DELETE)
    }

    private suspend fun checkOwnership(job: CronJob): CronResult<Unit> = when (val result = request("GET", path(job))) {
        is CronResult.Failure -> result
        is CronResult.Success -> {
            val current = parseJob(result.value)
            if (current == null) CronResult.Failure(CronFailure.INVALID)
            else if (current.id != job.id || current.profile != job.profile) CronResult.Failure(CronFailure.STALE)
            else CronResult.Success(Unit)
        }
    }

    private fun CronResult<JsonValue>.unitResult(expected: CronJob?, owner: String, deletion: Boolean = false): CronResult<Unit> = when (this) {
        is CronResult.Failure -> this
        is CronResult.Success -> {
            val current = if (deletion) null else parseJob(value)
            val confirmed = if (deletion) value["ok"]?.asBooleanOrNull() == true
                else current != null && current.profile == owner && (expected == null || current.id == expected.id)
            if (confirmed) CronResult.Success(Unit) else CronResult.Failure(CronFailure.UNKNOWN)
        }
    }

    private fun parseJob(value: JsonValue): CronJob? {
        val obj = value.asObjectOrNull() ?: return null
        fun string(key: String) = obj[key]?.asStringOrNull()
        val id = string("id")?.takeIf { it.isNotBlank() && it !in listOf("unknown", ".", "..") } ?: return null
        val profile = string("profile")?.takeIf { it.isNotBlank() } ?: return null
        val schedule = obj["schedule"]
        val editable = schedule?.asStringOrNull() ?: schedule?.get("expr")?.asStringOrNull()
            ?: schedule?.get("display")?.asStringOrNull() ?: string("schedule_display") ?: ""
        val enabled = obj["enabled"]?.asBooleanOrNull() ?: return null
        return CronJob(id, profile, string("name")?.ifBlank { "Unnamed cron" } ?: "Unnamed cron",
            string("prompt") ?: "", editable, string("schedule_display") ?: editable,
            enabled && string("state") != "paused" && string("paused_at").isNullOrBlank(),
            string("next_run_at"), string("last_status"), obj["no_agent"]?.asBooleanOrNull() == true)
    }
}
