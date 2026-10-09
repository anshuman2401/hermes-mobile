package io.github.anshuman2401.hermesmobile.data.context

import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayRedactor
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonArray
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonBoolean
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonString
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonValue
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartitionResult
import io.github.anshuman2401.hermesmobile.data.local.CacheReadResult
import io.github.anshuman2401.hermesmobile.data.local.CachedContextProfile
import io.github.anshuman2401.hermesmobile.data.local.ContextProfileCacheV2
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import io.github.anshuman2401.hermesmobile.data.local.AuthCredentialLease
import io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult
import kotlinx.coroutines.CancellationException
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticEvent
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticRing
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticSanitizer
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin

/**
 * Repository interface for read-only retrieval of Hermes Context Profiles (workspaces).
 * Enforces authenticated GET reads only, zero RPC/POST mutations, and fail-closed error handling.
 */
interface ContextProfileRepository {
    suspend fun readSelectedProfileAuthoritative(): SelectedContextReadResult = SelectedContextReadResult.Failed
    suspend fun getScopedSessionsCaptured(profile: String, expectedLease: AuthCredentialLease, limit: Int = 100): ScopedSessionsResult =
        ScopedSessionsResult.Error(IllegalStateException("Captured workspace fetch unsupported"))
    suspend fun getProfiles(): ContextProfilesResult
    /** Persists [name] as this app's selection without making a network request. */
    suspend fun selectProfile(name: String): ContextProfileSelectionResult
    suspend fun getSelectedProfile(): String?
    suspend fun setSelectedProfile(name: String?)
    suspend fun getScopedSessions(profile: String, limit: Int = 100): Result<List<SessionSummary>>

    /**
     * Profile-scoped fetch with typed self-heal semantics: returns
     * [ScopedSessionsResult.StaleSelection] when the host 404s because the
     * named profile is absent from its roster (detail indicates profile
     * not found). Host-controlled 'detail' text is only parsed into a
     * boolean signal — it is never surfaced raw in any result variant.
     */
    suspend fun getScopedSessionsTyped(profile: String, limit: Int = 100): ScopedSessionsResult
}

class DefaultContextProfileRepository(
    private val vaultStorage: VaultStorage,
    private val httpTransport: AuthHttpTransport = OkHttpAuthTransport(),
    private val diagnosticRing: DiagnosticRing? = null
) : ContextProfileRepository {

    override suspend fun readSelectedProfileAuthoritative(): SelectedContextReadResult = vaultStorage.readSelectedContextProfileAuthoritative()

    override suspend fun getSelectedProfile(): String? {
        return vaultStorage.getSelectedContextProfile()
    }

    override suspend fun setSelectedProfile(name: String?) {
        if (name.isNullOrBlank()) {
            vaultStorage.clearSelectedContextProfile()
            diagnosticRing?.record(DiagnosticEvent.Switch.PreferenceCleared())
        } else {
            val sanitized = sanitizeName(name) ?: return
            vaultStorage.saveSelectedContextProfile(sanitized)
            diagnosticRing?.record(DiagnosticEvent.Switch.PreferencePersisted(sanitized))
        }
    }

    override suspend fun getScopedSessions(profile: String, limit: Int): Result<List<SessionSummary>> {
        val sanitized = sanitizeName(profile)
            ?: return Result.failure(IllegalArgumentException("Invalid profile name format"))

        val credential = vaultStorage.getAuthCredential()
            ?: return Result.failure(IllegalStateException("No active connection available"))

        val origin = CanonicalOrigin.parse(credential.canonicalOrigin).getOrElse {
            return Result.failure(IllegalStateException("Invalid connection origin"))
        }

        diagnosticRing?.record(DiagnosticEvent.Switch.ScopedFetchStarted(profile = sanitized))

        val cookieHeader = credential.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val headers = mapOf(
            "Cookie" to cookieHeader,
            "Accept" to "application/json"
        )
        val url = origin.resolveHttp("/api/profiles/sessions?profile=$sanitized&limit=$limit")

        return try {
            val response = httpTransport.execute(
                method = "GET",
                url = url,
                headers = headers
            )
            when (response.statusCode) {
                in 200..299 -> {
                    val root = GatewayJson.parseOrNull(response.body)
                        ?: return Result.failure(IllegalStateException("Invalid scoped sessions response format"))
                    val sessionsArray = (root as? JsonObject)?.get("sessions")?.asArrayOrNull()
                        ?: return Result.failure(IllegalStateException("Scoped sessions response missing 'sessions' array"))

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
                    diagnosticRing?.record(
                        DiagnosticEvent.Switch.ScopedFetchOutcome(
                            profile = sanitized,
                            count = summaries.size,
                            isOfflineCache = false,
                            error = null
                        )
                    )
                    Result.success(summaries)
                }
                401, 403 -> {
                    diagnosticRing?.record(
                        DiagnosticEvent.Switch.ScopedFetchOutcome(
                            profile = sanitized,
                            count = null,
                            isOfflineCache = false,
                            error = "Authentication expired or unauthorized (${response.statusCode})"
                        )
                    )
                    Result.failure(SecurityException("Authentication expired or unauthorized (${response.statusCode})"))
                }
                else -> {
                    diagnosticRing?.record(
                        DiagnosticEvent.Switch.ScopedFetchOutcome(
                            profile = sanitized,
                            count = null,
                            isOfflineCache = false,
                            error = "Context profiles service unavailable (HTTP ${response.statusCode})"
                        )
                    )
                    Result.failure(IllegalStateException("Context profiles service unavailable (HTTP ${response.statusCode})"))
                }
            }
        } catch (e: SecurityException) {
            // Fail-closed: raw transport exceptions (e.g. OkHttp "Failed to
            // connect to /<ip>:<port>") embed host values, so the catch block
            // converts them to fixed safe typed errors mirroring the
            // activation path — the raw exception never propagates to the UI.
            // Origin-policy rejections (transport URL canonical-origin check)
            // get a DISTINCT fixed message so they are no longer
            // indistinguishable from auth failures; raw text stays redacted.
            val isOriginPolicy = e.message?.contains("origin is not canonical") == true
            val safeError = if (isOriginPolicy) {
                "Request rejected by origin policy"
            } else {
                "Authentication rejected"
            }
            diagnosticRing?.record(
                DiagnosticEvent.Switch.ScopedFetchOutcome(
                    profile = sanitized,
                    count = null,
                    isOfflineCache = false,
                    error = safeError
                )
            )
            Result.failure(SecurityException(safeError))
        } catch (_: Exception) {
            diagnosticRing?.record(
                DiagnosticEvent.Switch.ScopedFetchOutcome(
                    profile = sanitized,
                    count = null,
                    isOfflineCache = false,
                    error = "Context profiles service unavailable"
                )
            )
            Result.failure(IllegalStateException("Context profiles service unavailable"))
        }
    }

    // 404 self-heal parsing. Host 'detail' text is host-controlled:
    // it is only ever reduced to a boolean presence signal, never
    // embedded in any exception message, diagnostic string, or UI string.

    /**
     * Matches the host's FastAPI 404 body shape
     * {"detail":"Profile 'X' does not exist."} without ever echoing X or
     * any other part of the host-controlled text. Pure boolean extraction.
     */
    private fun detailIndicatesProfileNotFound(detail: String?): Boolean {
        if (detail == null) return false
        val normalized = detail.trim().lowercase()
        if (normalized.isEmpty()) return false
        val mentionsProfile = normalized.contains("profile")
        val indicatesAbsent = normalized.contains("does not exist") ||
            normalized.contains("doesn't exist") ||
            normalized.contains("not found") ||
            normalized.contains("unknown") ||
            normalized.contains("no such")
        return mentionsProfile && indicatesAbsent
    }

    private fun parseHostErrorDetail(body: String): String? {
        if (body.isBlank()) return null
        val root = GatewayJson.parseOrNull(body) as? JsonObject ?: return null
        return (root["detail"] as? JsonString)?.value
    }

    override suspend fun getScopedSessionsTyped(
        profile: String,
        limit: Int
    ): ScopedSessionsResult {
        val credential = vaultStorage.getAuthCredential()
            ?: return ScopedSessionsResult.Error(IllegalStateException("No active connection available"))
        return fetchScopedSessions(profile, limit, credential)
    }

    override suspend fun getScopedSessionsCaptured(profile: String, expectedLease: AuthCredentialLease, limit: Int): ScopedSessionsResult {
        val actual = vaultStorage.readAuthCredentialLease(expectedLease.origin)
        if (actual == null || actual.profileLocalId != expectedLease.profileLocalId || actual.origin != expectedLease.origin ||
            actual.epoch != expectedLease.epoch || actual.credential != expectedLease.credential)
            return ScopedSessionsResult.Error(IllegalStateException("Workspace owner changed"))
        return fetchScopedSessions(profile, limit, expectedLease.credential)
    }

    private suspend fun fetchScopedSessions(profile: String, limit: Int, credential: io.github.anshuman2401.hermesmobile.data.local.AuthCredential): ScopedSessionsResult {
        val sanitized = sanitizeName(profile)
            ?: return ScopedSessionsResult.Error(
                IllegalArgumentException("Invalid profile name format")
            )

        val origin = CanonicalOrigin.parse(credential.canonicalOrigin).getOrElse {
            return ScopedSessionsResult.Error(
                IllegalStateException("Invalid connection origin")
            )
        }

        diagnosticRing?.record(DiagnosticEvent.Switch.ScopedFetchStarted(profile = sanitized))

        val cookieHeader = credential.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val headers = mapOf(
            "Cookie" to cookieHeader,
            "Accept" to "application/json"
        )
        val url = origin.resolveHttp("/api/profiles/sessions?profile=$sanitized&limit=$limit")

        return try {
            val response = httpTransport.execute(
                method = "GET",
                url = url,
                headers = headers
            )
            when (response.statusCode) {
                in 200..299 -> {
                    val root = GatewayJson.parseOrNull(response.body)
                        ?: return ScopedSessionsResult.Error(
                            IllegalStateException("Invalid scoped sessions response format")
                        )
                    val sessionsArray = (root as? JsonObject)?.get("sessions")?.asArrayOrNull()
                        ?: return ScopedSessionsResult.Error(
                            IllegalStateException("Scoped sessions response missing 'sessions' array")
                        )

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
                    diagnosticRing?.record(
                        DiagnosticEvent.Switch.ScopedFetchOutcome(
                            profile = sanitized,
                            count = summaries.size,
                            isOfflineCache = false,
                            error = null
                        )
                    )
                    ScopedSessionsResult.Success(summaries)
                }
                404 -> {
                    // Scoped-session 404 self-heal (deleg_8333ba4d): parse the
                    // host-controlled 'detail' body field but only reduce it to
                    // a boolean signal. The raw text is never recorded, logged,
                    // or surfaced — a hostile detail value (IPs, cookie-like
                    // tokens) can never reach the UI or diagnostics from here.
                    val detail = parseHostErrorDetail(response.body)
                    val profileGone = detailIndicatesProfileNotFound(detail)
                    if (profileGone) {
                        diagnosticRing?.record(
                            DiagnosticEvent.Switch.ScopedFetchOutcome(
                                profile = sanitized,
                                count = null,
                                isOfflineCache = false,
                                error = "Workspace no longer exists"
                            )
                        )
                        ScopedSessionsResult.StaleSelection(
                            notice = ScopedSessionsResult.PROFILE_GONE_NOTICE
                        )
                    } else {
                        // 404 without the profile-not-exist shape keeps the
                        // existing fail-closed generic mapping.
                        diagnosticRing?.record(
                            DiagnosticEvent.Switch.ScopedFetchOutcome(
                                profile = sanitized,
                                count = null,
                                isOfflineCache = false,
                                error = "Context profiles service unavailable (HTTP 404)"
                            )
                        )
                        ScopedSessionsResult.Error(
                            IllegalStateException("Context profiles service unavailable (HTTP 404)")
                        )
                    }
                }
                400 -> {
                    // 'detail' is parsed for contract parity only; it stays a
                    // boolean-only signal and the fixed generic fail-closed
                    // mapping is preserved.
                    parseHostErrorDetail(response.body)
                    diagnosticRing?.record(
                        DiagnosticEvent.Switch.ScopedFetchOutcome(
                            profile = sanitized,
                            count = null,
                            isOfflineCache = false,
                            error = "Context profiles service unavailable (HTTP 400)"
                        )
                    )
                    ScopedSessionsResult.Error(
                        IllegalStateException("Context profiles service unavailable (HTTP 400)")
                    )
                }
                401, 403 -> {
                    diagnosticRing?.record(
                        DiagnosticEvent.Switch.ScopedFetchOutcome(
                            profile = sanitized,
                            count = null,
                            isOfflineCache = false,
                            error = "Authentication expired or unauthorized (${response.statusCode})"
                        )
                    )
                    ScopedSessionsResult.Error(
                        SecurityException("Authentication expired or unauthorized (${response.statusCode})")
                    )
                }
                else -> {
                    diagnosticRing?.record(
                        DiagnosticEvent.Switch.ScopedFetchOutcome(
                            profile = sanitized,
                            count = null,
                            isOfflineCache = false,
                            error = "Context profiles service unavailable (HTTP ${response.statusCode})"
                        )
                    )
                    ScopedSessionsResult.Error(
                        IllegalStateException("Context profiles service unavailable (HTTP ${response.statusCode})")
                    )
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: SecurityException) {
            // Fail-closed: raw transport exceptions (e.g. OkHttp "Failed to
            // connect to /<ip>:<port>") embed host values, so the catch block
            // converts them to fixed safe typed errors mirroring the
            // activation path — the raw exception never propagates to the UI.
            // Origin-policy rejections (transport URL canonical-origin check)
            // get a DISTINCT fixed message so they are no longer
            // indistinguishable from auth failures; raw text stays redacted.
            val isOriginPolicy = e.message?.contains("origin is not canonical") == true
            val safeError = if (isOriginPolicy) {
                "Request rejected by origin policy"
            } else {
                "Authentication rejected"
            }
            diagnosticRing?.record(
                DiagnosticEvent.Switch.ScopedFetchOutcome(
                    profile = sanitized,
                    count = null,
                    isOfflineCache = false,
                    error = safeError
                )
            )
            ScopedSessionsResult.Error(SecurityException(safeError))
        } catch (_: Exception) {
            diagnosticRing?.record(
                DiagnosticEvent.Switch.ScopedFetchOutcome(
                    profile = sanitized,
                    count = null,
                    isOfflineCache = false,
                    error = "Context profiles service unavailable"
                )
            )
            ScopedSessionsResult.Error(IllegalStateException("Context profiles service unavailable"))
        }
    }

    override suspend fun selectProfile(name: String): ContextProfileSelectionResult {
        val sanitized = sanitizeName(name)
            ?: return ContextProfileSelectionResult.Error(
                ContextProfileErrorReason.MALFORMED,
                "Invalid profile name format"
            )
        val previous = try { readSelectedProfileAuthoritative() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SelectedContextReadResult.Failed }
        if (previous !is SelectedContextReadResult.Selected && previous != SelectedContextReadResult.Absent)
            return ContextProfileSelectionResult.Error(ContextProfileErrorReason.UNAVAILABLE, "Local workspace selection failed")
        return try {
            vaultStorage.saveSelectedContextProfile(sanitized)
            val actual = readSelectedProfileAuthoritative()
            if ((actual as? SelectedContextReadResult.Selected)?.name != sanitized)
                throw IllegalStateException("Workspace selection not established")
            ContextProfileSelectionResult.Success(sanitized)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            try {
                when (previous) {
                    is SelectedContextReadResult.Selected -> vaultStorage.saveSelectedContextProfile(previous.name)
                    SelectedContextReadResult.Absent -> vaultStorage.clearSelectedContextProfile()
                    else -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Caller reads authoritative outcome, never guesses rollback. */ }
            ContextProfileSelectionResult.Error(ContextProfileErrorReason.UNAVAILABLE, "Local workspace selection failed")
        }
    }

    override suspend fun getProfiles(): ContextProfilesResult {
        val credential = vaultStorage.getAuthCredential()
            ?: return ContextProfilesResult.Error(
                ContextProfileErrorReason.NO_ACTIVE_CONNECTION,
                "No active connection available"
            )

        val origin = CanonicalOrigin.parse(credential.canonicalOrigin).getOrElse {
            return ContextProfilesResult.Error(
                ContextProfileErrorReason.UNAVAILABLE,
                "Invalid connection origin"
            )
        }

        val cookieHeader = credential.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val headers = mapOf(
            "Cookie" to cookieHeader,
            "Accept" to "application/json"
        )

        // PR-2 REQ-02: resolve the access partition ONCE, after the
        // credential/origin gate and before the live fetch. Null means this
        // load neither captures nor reads the roster (fail-closed scope).
        val partition = try {
            (vaultStorage.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition
        } catch (_: Exception) {
            null
        }

        return when (val outcome = fetchRoster(origin, headers)) {
            is RosterFetchOutcome.Parsed -> {
                // PR-2 REQ-02: capture ONLY after both authenticated reads
                // parsed. Best-effort: a write failure never fails the live
                // result, adds no diagnostics and no telemetry (SEC-04).
                if (partition != null) {
                    try {
                        val capturedAt = System.currentTimeMillis()
                        val draft = ContextProfileCacheV2(
                            recordId = "",
                            profileLocalId = partition.profileLocalId,
                            originSha256 = partition.originSha256,
                            cacheEpoch = partition.cacheEpoch,
                            workspaceScopeTag = partition.workspaceScopeTag,
                            createdAtMillis = capturedAt,
                            updatedAtMillis = capturedAt,
                            capturedAtEpochMillis = capturedAt,
                            selectedWorkspaceName = getSelectedProfile(),
                            entries = outcome.profiles.map {
                                CachedContextProfile(
                                    name = it.name,
                                    isRunning = it.isRunning,
                                    isActive = it.isActive
                                )
                            }
                        )
                        vaultStorage.saveContextProfileCache(
                            draft.copy(recordId = draft.recordId()),
                            partition
                        )
                    } catch (_: Exception) {
                        // Best-effort capture (REQ-02): swallowed by design.
                    }
                }
                ContextProfilesResult.Success(
                    profiles = outcome.profiles,
                    activeProfileName = outcome.activeProfileName,
                    source = RosterSource.LIVE,
                    capturedAtMillis = null
                )
            }
            is RosterFetchOutcome.Failed -> {
                // PR-2 REQ-03: offline fallback with the SAME partition
                // resolved before the fetch — never a re-resolve, never a
                // guess, never a cross-scope read. Available serves; every
                // other outcome keeps the original live error verbatim.
                val cached = if (partition != null) {
                    try {
                        vaultStorage.readContextProfileCache(partition)
                    } catch (_: Exception) {
                        CacheReadResult.Corrupt
                    }
                } else {
                    CacheReadResult.Absent
                }
                if (cached is CacheReadResult.Available) {
                    try {
                        val record = cached.value
                        // PR-2 REQ-04: local selection label preserved —
                        // persisted selection first, else the captured active
                        // entry; isRunning/isActive are recomputed from that name.
                        val persistedSelected = getSelectedProfile()
                        val activeName = persistedSelected
                            ?: record.entries.firstOrNull { it.isActive }?.name
                        return ContextProfilesResult.Success(
                            profiles = record.entries.map { entry ->
                                ContextProfile(
                                    name = entry.name,
                                    isRunning = entry.isRunning,
                                    isActive = entry.name == activeName
                                )
                            },
                            activeProfileName = activeName,
                            source = RosterSource.CACHED,
                            capturedAtMillis = record.capturedAtEpochMillis
                        )
                    } catch (_: Exception) {
                        // Fall through: any failure keeps the original live error.
                    }
                }
                ContextProfilesResult.Error(outcome.reason, outcome.safeMessage)
            }
        }
    }

    /** PR-2 TASK-06: outcome of the live read-only roster fetch. */
    private sealed interface RosterFetchOutcome {
        /** Both reads parsed: sanitized roster + the active name used to mark rows. */
        data class Parsed(
            val profiles: List<ContextProfile>,
            val activeProfileName: String?
        ) : RosterFetchOutcome

        /** Pre-PR-2 fail-closed branch, byte-identical reason + safe message. */
        data class Failed(
            val reason: ContextProfileErrorReason,
            val safeMessage: String
        ) : RosterFetchOutcome
    }

    /**
     * The authenticated read-only roster fetch (active profile + roster list).
     * PR-2 TASK-06 extraction: branch order, reasons and safe messages are
     * byte-for-byte the pre-PR-2 code — only the return type changed so
     * getProfiles can attempt the offline fallback after any failure.
     */
    private suspend fun fetchRoster(
        origin: CanonicalOrigin,
        headers: Map<String, String>
    ): RosterFetchOutcome {
        // 1. Fetch active profile from GET /api/profiles/active
        val hostActiveProfileName: String?
        try {
            val activeResponse = httpTransport.execute(
                method = "GET",
                url = origin.resolveHttp("/api/profiles/active"),
                headers = headers
            )
            when {
                activeResponse.statusCode in setOf(401, 403) -> {
                    return RosterFetchOutcome.Failed(
                        ContextProfileErrorReason.UNAUTHORIZED,
                        "Authentication expired or unauthorized"
                    )
                }
                activeResponse.statusCode in 200..299 -> {
                    val root = GatewayJson.parseOrNull(activeResponse.body)
                        ?: return RosterFetchOutcome.Failed(
                            ContextProfileErrorReason.MALFORMED,
                            "Invalid active profile response format"
                        )
                    hostActiveProfileName = parseActiveProfile(root)
                }
                else -> {
                    return RosterFetchOutcome.Failed(
                        ContextProfileErrorReason.UNAVAILABLE,
                        "Context profiles service unavailable (HTTP ${activeResponse.statusCode})"
                    )
                }
            }
        } catch (_: SecurityException) {
            return RosterFetchOutcome.Failed(
                ContextProfileErrorReason.UNAUTHORIZED,
                "Authentication rejected"
            )
        } catch (_: Exception) {
            return RosterFetchOutcome.Failed(
                ContextProfileErrorReason.UNAVAILABLE,
                "Context profiles service unavailable"
            )
        }

        val persistedSelected = getSelectedProfile()
        val effectiveActiveProfileName = persistedSelected ?: hostActiveProfileName

        // 2. Fetch profiles list from GET /api/profiles
        val profiles: List<ContextProfile>
        try {
            val profilesResponse = httpTransport.execute(
                method = "GET",
                url = origin.resolveHttp("/api/profiles"),
                headers = headers
            )
            when {
                profilesResponse.statusCode in setOf(401, 403) -> {
                    return RosterFetchOutcome.Failed(
                        ContextProfileErrorReason.UNAUTHORIZED,
                        "Authentication expired or unauthorized"
                    )
                }
                profilesResponse.statusCode in 200..299 -> {
                    val root = GatewayJson.parseOrNull(profilesResponse.body)
                        ?: return RosterFetchOutcome.Failed(
                            ContextProfileErrorReason.MALFORMED,
                            "Invalid profile list response format"
                        )
                    profiles = parseProfilesList(root, effectiveActiveProfileName)
                        ?: return RosterFetchOutcome.Failed(
                            ContextProfileErrorReason.MALFORMED,
                            "Invalid profile list schema"
                        )
                }
                else -> {
                    return RosterFetchOutcome.Failed(
                        ContextProfileErrorReason.UNAVAILABLE,
                        "Context profiles service unavailable (HTTP ${profilesResponse.statusCode})"
                    )
                }
            }
        } catch (_: SecurityException) {
            return RosterFetchOutcome.Failed(
                ContextProfileErrorReason.UNAUTHORIZED,
                "Authentication rejected"
            )
        } catch (_: Exception) {
            return RosterFetchOutcome.Failed(
                ContextProfileErrorReason.UNAVAILABLE,
                "Context profiles service unavailable"
            )
        }

        return RosterFetchOutcome.Parsed(
            profiles = profiles,
            activeProfileName = effectiveActiveProfileName
        )
    }

    private fun parseActiveProfile(root: JsonValue): String? {
        val raw = when (root) {
            is JsonObject -> {
                (root["active"] as? JsonString)?.value
                    ?: (root["current"] as? JsonString)?.value
                    ?: (root["name"] as? JsonString)?.value
            }
            is JsonString -> root.value
            else -> null
        }
        return raw?.let(::sanitizeName)
    }

    private fun parseProfilesList(root: JsonValue, activeProfileName: String?): List<ContextProfile>? {
        val items: List<JsonValue> = when (root) {
            is JsonArray -> root.list
            is JsonObject -> (root["profiles"] as? JsonArray)?.list
            else -> null
        } ?: return null

        val result = mutableListOf<ContextProfile>()
        for (item in items) {
            when (item) {
                is JsonString -> {
                    val sanitized = sanitizeName(item.value) ?: continue
                    result.add(
                        ContextProfile(
                            name = sanitized,
                            isRunning = false,
                            isActive = (sanitized == activeProfileName)
                        )
                    )
                }
                is JsonObject -> {
                    val rawName = (item["name"] as? JsonString)?.value
                        ?: (item["id"] as? JsonString)?.value
                        ?: continue
                    val sanitized = sanitizeName(rawName) ?: continue
                    val running = (item["running"] as? JsonBoolean)?.value
                        ?: (item["gateway_running"] as? JsonBoolean)?.value
                        ?: (item["status"] as? JsonString)?.value?.equals("running", ignoreCase = true)
                        ?: (item["gateway"] as? JsonString)?.value?.equals("running", ignoreCase = true)
                        ?: (item["gateway_state"] as? JsonString)?.value?.equals("running", ignoreCase = true)
                        ?: false

                    result.add(
                        ContextProfile(
                            name = sanitized,
                            isRunning = running,
                            isActive = (sanitized == activeProfileName)
                        )
                    )
                }
                else -> continue
            }
        }
        return result
    }

    private fun sanitizeName(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > 80) return null
        if (!trimmed.matches(Regex("[A-Za-z0-9._-]+"))) return null
        return trimmed
    }
}
