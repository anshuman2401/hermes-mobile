package io.github.anshuman2401.hermesmobile.data.auth

import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticEvent
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticRing
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayRedactor
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.anshuman2401.hermesmobile.data.local.AuthCredentialLease
import io.github.anshuman2401.hermesmobile.data.local.AuthCredentialMutationResult
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.CacheEpochRotationReason
import io.github.anshuman2401.hermesmobile.data.local.SignOutCacheChoice
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.origin.OriginCookie

/**
 * Default implementation of AuthRepository supporting the INT-001 certified REST session-cookie route.
 * Follows ADR-0003, tui-gateway-client-contract.md, and RPR-003.
 *
 * Enforces:
 * - CanonicalOrigin validation on all network operations
 * - Non-secret preflight check before password credential exposure
 * - 2xx response without valid session cookie is treated as failure
 * - Origin-bound Secure cookie jar with strict domain matching (zero cross-origin leakage)
 * - Stored session material strictly bound to CanonicalOrigin
 * - Single-use ticket minting per connection attempt (zero caching or reuse)
 * - Immediate in-memory wiping of mutable password buffers
 * - Secret redaction across all errors, logs, and diagnostic strings
 */
class DefaultAuthRepository(
    private val vaultStorage: VaultStorage,
    private val httpTransport: AuthHttpTransport = DefaultAuthHttpTransport(),
    private val diagnosticRing: DiagnosticRing? = null
) : AuthRepository {

    private val headerOwner = Mutex()
    private val recoveryOwner = Mutex()
    private val localRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override val localAccessRevision: kotlinx.coroutines.flow.StateFlow<Long> = localRevision
    override fun invalidatePendingAuthentication() { vaultStorage.invalidateLoginOwners() }
    private val rejections = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val sessionRejections: kotlinx.coroutines.flow.Flow<Unit> = rejections
    override suspend fun prepareLocalLogin(origin: CanonicalOrigin): Boolean = vaultStorage.prepareLocalLogin(origin.originUrl)

    override suspend fun recoverSession(origin: CanonicalOrigin): Result<Unit> {
        val observed = vaultStorage.getAuthCredential()
        return recoveryOwner.withLock {
            try {
                // Another waiter already recovered this exact origin.
                val current = vaultStorage.getAuthCredential()
                if (current != null && current != observed && current.canonicalOrigin == origin.originUrl)
                    return@withLock Result.success(Unit)
                val owner = vaultStorage.captureLoginOwner(origin.originUrl)
                    ?: return@withLock Result.failure(AuthSessionRejectedException())
                val remembered = vaultStorage.readRememberedLogin(owner)
                    ?: return@withLock Result.failure(AuthSessionRejectedException())
                remembered.use {
                    val result = loginOwned(origin, it.username, it.password, it.provider, owner)
                    if (result.exceptionOrNull() is SavedPasswordRejectedException)
                        vaultStorage.rejectRememberedLogin(owner)
                    result.map { Unit }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) { Result.failure(exception) }
        }
    }

    override suspend fun isResponseCurrent(receipt: AuthCredentialLease): Boolean =
        vaultStorage.mutateAuthCredentialIfCurrent(receipt, null) is AuthCredentialMutationResult.Accepted

    override suspend fun <T : AuthenticatedHttpHeaders> exchangeAuthenticated(
        origin: CanonicalOrigin,
        requestPath: String,
        call: (Map<String, String>) -> AuthenticatedHttpCall<T>
    ): Result<AuthenticatedExchange<T>> = exchangeOwned(origin, requestPath, null, call)

    override suspend fun <T : AuthenticatedHttpHeaders> exchangeAuthenticated(
        origin: CanonicalOrigin,
        requestPath: String,
        expected: AuthCredentialLease,
        call: (Map<String, String>) -> AuthenticatedHttpCall<T>
    ): Result<AuthenticatedExchange<T>> = exchangeOwned(origin, requestPath, expected, call)

    private suspend fun <T : AuthenticatedHttpHeaders> exchangeOwned(
        origin: CanonicalOrigin,
        requestPath: String,
        expected: AuthCredentialLease?,
        call: (Map<String, String>) -> AuthenticatedHttpCall<T>
    ): Result<AuthenticatedExchange<T>> = headerOwner.withLock {
        var pending: AuthenticatedHttpCall<T>? = null
        try {
            try { origin.resolveHttp(requestPath) } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid authenticated request path")
            }
            val lease = vaultStorage.readAuthCredentialLease(origin.originUrl)
                ?: return@withLock Result.failure(if (expected != null) StaleAuthenticatedResponseException()
                    else AuthSessionRejectedException())
            if (expected != null && (expected.origin != origin.originUrl ||
                lease.profileLocalId != expected.profileLocalId || lease.origin != expected.origin ||
                lease.epoch != expected.epoch || lease.credential != expected.credential)) {
                throw StaleAuthenticatedResponseException()
            }
            pending = call(mapOf("Cookie" to lease.credential.cookies.entries.joinToString("; ") {
                "${it.key}=${it.value}"
            }, "Accept" to "application/json"))
            val response = pending.awaitHeaders()
            if (response.statusCode in 300..399) throw AuthOriginPolicyRejectedException()
            // Provider uncertainty cannot replace or delete the current jar.
            val updates = if (response.statusCode == 503) emptyList() else
                parseCookieUpdates(response.headers, origin, requestPath)
            val jar = lease.credential.cookies.toMutableMap()
            var deleted = false
            for (update in updates) {
                if (update.isExpired()) {
                    deleted = jar.remove(update.name) != null || deleted
                } else jar[update.name] = update.value
            }
            val terminal = response.statusCode == 401 || response.statusCode == 403 || deleted
            val replacement = if (!terminal && updates.isNotEmpty()) lease.credential.copy(
                cookies = jar,
                expiryEpochSeconds = updates.mapNotNull { it.expiresEpochSeconds }.minOrNull()
                    ?: lease.credential.expiryEpochSeconds
            ) else null
            // Once headers arrive, finish the local commit even if the caller is cancelled.
            val mutation = withContext(NonCancellable) {
                try { vaultStorage.mutateAuthCredentialIfCurrent(lease, replacement, terminal) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { AuthCredentialMutationResult.Failed }
            }
            when (mutation) {
                AuthCredentialMutationResult.Stale -> throw StaleAuthenticatedResponseException()
                AuthCredentialMutationResult.Failed -> {
                    throw IllegalStateException("Authenticated persistence unavailable")
                }
                is AuthCredentialMutationResult.Accepted -> {
                    if (terminal) {
                        diagnosticRing?.record(DiagnosticEvent.Auth.Expired())
                        rejections.tryEmit(Unit)
                        throw AuthSessionRejectedException(response.statusCode)
                    }
                    val receipt = mutation.receipt ?: throw StaleAuthenticatedResponseException()
                    Result.success(AuthenticatedExchange(response, receipt))
                }
            }
        } catch (cancelled: CancellationException) {
            pending?.cancel()
            throw cancelled
        } catch (exception: Exception) {
            pending?.cancel()
            Result.failure(exception)
        }
    }

    override suspend fun checkStatus(origin: CanonicalOrigin): Result<HostStatus> {
        val url = origin.resolveHttp("/api/status")

        val response = try {
            httpTransport.execute(
                method = "GET",
                url = url,
                headers = mapOf("Accept" to "application/json")
            )
        } catch (e: Exception) {
            return Result.failure(e)
        }

        if (response.statusCode !in 200..299) {
            return Result.failure(
                IllegalStateException("Status preflight failed with HTTP ${response.statusCode}: ${GatewayRedactor.redactString(response.body)}")
            )
        }

        val parsed = GatewayJson.parseOrNull(response.body)?.asObjectOrNull()
            ?: return Result.failure(IllegalStateException("Malformed status JSON"))

        val version = parsed["version"]?.asStringOrNull() ?: "unknown"
        val authRequired = parsed["auth_required"]?.asBooleanOrNull() ?: true
        val gatewayRunning = parsed["gateway_running"]?.asBooleanOrNull()
            ?: (parsed["gateway_state"]?.asStringOrNull() == "running")

        // Sanitize the advertised provider IDs from auth_providers; never inject a guessed default.
        val authProviders: List<String> = parsed["auth_providers"]
            ?.asArrayOrNull()
            ?.list
            ?.mapNotNull { it.asStringOrNull()?.takeIf { id -> id.isNotBlank() } }
            ?: emptyList()

        diagnosticRing?.updateHostVersion(version)
        diagnosticRing?.updateConnectionOrigin(origin.originUrl)
        return Result.success(HostStatus(version, authRequired, gatewayRunning, response.body, authProviders))
    }

    override suspend fun checkStatus(origin: String): Result<HostStatus> =
        CanonicalOrigin.parse(origin).fold(
            onSuccess = { checkStatus(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun hasValidSession(): Boolean = hasValidSession(origin = null as CanonicalOrigin?)

    override suspend fun hasValidSession(origin: CanonicalOrigin?): Boolean {
        val cred = vaultStorage.getAuthCredential() ?: return false

        // Cross-origin session isolation check
        if (origin != null && cred.canonicalOrigin != origin.originUrl) {
            return false
        }

        val nowSec = System.currentTimeMillis() / 1000
        return cred.expiryEpochSeconds == null || cred.expiryEpochSeconds > nowSec
    }

    override suspend fun hasValidSession(origin: String): Boolean =
        CanonicalOrigin.parse(origin).getOrNull()?.let { hasValidSession(it) } ?: false

    override suspend fun login(
        origin: CanonicalOrigin,
        username: String,
        password: CharArray,
        provider: String
    ): Result<AuthCredential> {
        try {
            val owner = vaultStorage.captureLoginOwner(origin.originUrl)
                ?: return Result.failure(IllegalStateException("Credential storage unavailable"))
            val previous = vaultStorage.activeAccessPartition()
            val result = loginOwned(origin, username, password, provider, owner)
            if (result.isSuccess && previous != vaultStorage.activeAccessPartition()) localRevision.update { it + 1 }
            return result
        } finally { password.fill('0') }
    }

    private suspend fun loginOwned(origin: CanonicalOrigin, username: String, password: CharArray,
        provider: String, owner: io.github.anshuman2401.hermesmobile.data.local.LoginOwner): Result<AuthCredential> {
        val retainedPassword = password.copyOf()
        try { return performPasswordLogin(origin, username, password, provider, owner, retainedPassword) }
        finally { retainedPassword.fill('\u0000'); password.fill('0') }
    }

    private suspend fun performPasswordLogin(origin: CanonicalOrigin, username: String, password: CharArray,
        provider: String, owner: io.github.anshuman2401.hermesmobile.data.local.LoginOwner, retainedPassword: CharArray): Result<AuthCredential> {
        // Preflight check before credential exposure per ADR-0003 & RPR-003
        val preflightResult = checkStatus(origin)
        if (preflightResult.isFailure) {
            val ex = preflightResult.exceptionOrNull()
            diagnosticRing?.record(
                DiagnosticEvent.Auth.Rejected("Preflight check failed: ${ex?.message}")
            )
            // Immediately wipe mutable password before returning failure
            password.fill('0')
            return Result.failure(
                SecurityException("Preflight check failed before credential exposure: ${ex?.message}", ex)
            )
        }

        val hostStatus = preflightResult.getOrThrow()

        // Resolve the provider ID from the preflight response. "password" names the
        // endpoint flow, not a provider ID. Never send a caller-supplied or default ID
        // that the host did not advertise.
        val advertisedProviders = hostStatus.authProviders
        if (advertisedProviders.isEmpty()) {
            diagnosticRing?.record(
                DiagnosticEvent.Auth.Rejected("Host advertises no password-login provider")
            )
            password.fill('0')
            return Result.failure(
                IllegalStateException(
                    "Host advertises no password-login provider. " +
                        "Cannot authenticate: check host provider configuration."
                )
            )
        }
        val resolvedProvider = provider
            .takeUnless { it.isBlank() || it == "password" }
            ?: advertisedProviders.first()
        if (resolvedProvider !in advertisedProviders) {
            diagnosticRing?.record(
                DiagnosticEvent.Auth.Rejected("Requested provider not advertised")
            )
            password.fill('0')
            return Result.failure(
                IllegalStateException("Requested provider is not advertised by the host. Cannot authenticate.")
            )
        }

        diagnosticRing?.record(DiagnosticEvent.Auth.LoggingIn(provider = resolvedProvider))

        val requestBody: String
        val passwordString = String(password)
        try {
            val payload = linkedMapOf<String, Any?>(
                "username" to username,
                "password" to passwordString,
                "provider" to resolvedProvider
            )
            requestBody = GatewayJson.serialize(GatewayJson.fromAny(payload))
        } finally {
            // Immediate in-memory wiping of mutable password buffer
            password.fill('0')
        }

        val response = try {
            httpTransport.execute(
                method = "POST",
                url = origin.resolveHttp("/auth/password-login"),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                body = requestBody
            )
        } catch (e: Exception) {
            diagnosticRing?.record(
                DiagnosticEvent.Auth.Rejected("HTTP request failed: ${e.javaClass.simpleName}")
            )
            return Result.failure(e)
        }

        if (response.statusCode !in 200..299) {
            diagnosticRing?.record(
                DiagnosticEvent.Auth.Rejected("HTTP ${response.statusCode}")
            )
            return Result.failure(if (response.statusCode == 401 || response.statusCode == 403)
                SavedPasswordRejectedException() else IllegalStateException("Login service unavailable"))
        }

        // Parse cookies from response headers bound strictly to this origin
        val cookies = parseSetCookieHeaders(response.headers, origin)

        // 2xx without required cookie is failure per RPR-003
        if (cookies.isEmpty()) {
            diagnosticRing?.record(
                DiagnosticEvent.Auth.Rejected("Host returned no valid session cookie")
            )
            return Result.failure(
                SecurityException("Authentication returned HTTP ${response.statusCode} but host returned no valid session cookie. Login failed.")
            )
        }

        val cookieMap = cookies.associate { it.name to it.value }
        val expiryEpochSec: Long? = cookies.mapNotNull { it.expiresEpochSeconds }.minOrNull()

        val credential = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = resolvedProvider,
            cookies = cookieMap,
            canonicalOrigin = origin.originUrl,
            expiryEpochSeconds = expiryEpochSec
        )

        try {
            if (!vaultStorage.commitRememberedLogin(owner, credential, username, retainedPassword))
                return Result.failure(StaleAuthenticatedResponseException())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            return Result.failure(exception)
        }
        diagnosticRing?.updateConnectionOrigin(origin.originUrl)
        diagnosticRing?.record(DiagnosticEvent.Auth.Authenticated(provider = resolvedProvider))
        return Result.success(credential)
    }

    override suspend fun login(
        origin: String,
        username: String,
        password: CharArray,
        provider: String
    ): Result<AuthCredential> =
        CanonicalOrigin.parse(origin).fold(
            onSuccess = { login(it, username, password, provider) },
            onFailure = {
                password.fill('0')
                Result.failure(it)
            }
        )

    override suspend fun getStoredCredential(): AuthCredential? = getStoredCredential(origin = null as CanonicalOrigin?)

    override suspend fun getStoredCredential(origin: CanonicalOrigin?): AuthCredential? {
        val cred = vaultStorage.getAuthCredential() ?: return null
        if (origin != null && cred.canonicalOrigin != origin.originUrl) {
            return null
        }
        return cred
    }

    override suspend fun getStoredCredential(origin: String): AuthCredential? =
        CanonicalOrigin.parse(origin).getOrNull()?.let { getStoredCredential(it) }

    override suspend fun mintWebSocketTicket(origin: CanonicalOrigin): Result<GatewayTicket> {
        val exchanged = exchangeAuthenticated(origin, "/api/auth/ws-ticket") { headers ->
            object : AuthenticatedHttpCall<BufferedHeaders> {
                override suspend fun awaitHeaders(): BufferedHeaders = BufferedHeaders(httpTransport.execute(
                    method = "POST", url = origin.resolveHttp("/api/auth/ws-ticket"), headers = headers
                ))
                override fun cancel() = Unit // Buffered transport owns response lifetime.
            }
        }
        if (exchanged.isFailure) return Result.failure(exchanged.exceptionOrNull()!!)
        val accepted = exchanged.getOrThrow()
        val response = accepted.response.buffered
        if (response.statusCode !in 200..299) {
            return Result.failure(IllegalStateException("Ticket minting failed with HTTP ${response.statusCode}"))
        }

        val parsed = GatewayJson.parseOrNull(response.body)?.asObjectOrNull()
            ?: return Result.failure(IllegalStateException("Malformed ws-ticket response JSON"))

        val ticketValue = parsed["ticket"]?.asStringOrNull()
        if (ticketValue.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Missing or blank 'ticket' in ws-ticket response"))
        }

        val ttl = parsed["ttl"]?.asIntOrNull()
            ?: parsed["ttl_seconds"]?.asIntOrNull()
            ?: 30

        if (ttl <= 0) {
            return Result.failure(IllegalStateException("Invalid non-positive TTL in ws-ticket response: $ttl"))
        }

        // Return fresh single-use ticket directly; never cache or reuse per RPR-003
        return try {
            val ticket = GatewayTicket(value = ticketValue, ttlSeconds = ttl)
            if (isResponseCurrent(accepted.receipt)) Result.success(ticket)
            else Result.failure(StaleAuthenticatedResponseException())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Malformed ticket returned by host: ${e.message}", e))
        }
    }

    override suspend fun mintWebSocketTicket(origin: String): Result<GatewayTicket> =
        CanonicalOrigin.parse(origin).fold(
            onSuccess = { mintWebSocketTicket(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {
        invalidatePendingAuthentication()
        diagnosticRing?.record(DiagnosticEvent.Auth.LoggedOut(clearDrafts = clearDrafts))
        // Clear local credential. PR-1 REQ-06: the partition epoch rotates
        // (signed out) BEFORE the credential is removed (SEC-01: sign-out never
        // leaves an offline-readable partition behind). PR-6 REQ-02/REQ-07:
        // the explicit cache choice travels with the clear — KEEP retains this
        // profile's ciphertext behind the SIGNED_OUT rotation, CLEAR purges it
        // after the credential removal (vault-side ordering, PR-6).
        vaultStorage.clearAuthCredential(
            reason = cacheChoice.rotationReason(),
            cacheChoice = cacheChoice
        )

        if (clearDrafts) {
            vaultStorage.clearDrafts()
        }

        // In accordance with ADR-0003: Local reset/sign-out must never remotely delete the server session.
    }

    override suspend fun notifyAuthExpired() {
        diagnosticRing?.record(DiagnosticEvent.Auth.Expired())
        // Expiry revokes network authority while retaining an already-authorized
        // local read-only owner and remembered login. Explicit sign-out revokes both.
        vaultStorage.clearAuthCredential(
            reason = CacheEpochRotationReason.AUTH_EXPIRED,
            cacheChoice = SignOutCacheChoice.KEEP
        )
        // Retains drafts and pending turns per recovery contract
    }

    private class BufferedHeaders(val buffered: HttpResponse) : AuthenticatedHttpHeaders {
        override val statusCode get() = buffered.statusCode
        override val headers get() = buffered.headers
        override fun close() = Unit
    }

    /** Parse expired cookies with a pre-expiry clock, then restore their true expiry. */
    private fun parseCookieUpdates(
        headers: Map<String, List<String>>, origin: CanonicalOrigin, requestPath: String
    ): List<OriginCookie> = headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().mapNotNull { raw ->
        val parts = raw.split(';').map { it.trim() }
        val maxAge = parts.drop(1).lastOrNull { it.substringBefore('=').equals("Max-Age", true) }
            ?.substringAfter('=', "")?.trim()?.toLongOrNull()
        val withoutAge = parts.filterNot { it.substringBefore('=').equals("Max-Age", true) }.joinToString("; ")
        val parsed = OriginCookie.parseSetCookie(withoutAge, origin, Long.MIN_VALUE / 2) ?: return@mapNotNull null
        if (parsed.isSecure && origin.scheme == CanonicalOrigin.Scheme.HTTP) return@mapNotNull null
        // The persisted jar has no per-cookie paths: admit only cookies applicable to this request.
        val path = parsed.path
        if (!path.startsWith('/') || !(requestPath == path ||
            requestPath.startsWith(if (path.endsWith('/')) path else "$path/"))) return@mapNotNull null
        val now = System.currentTimeMillis() / 1000
        val expiry = if (maxAge == null) parsed.expiresEpochSeconds else
            if (maxAge <= 0) 0L else if (maxAge > Long.MAX_VALUE - now) Long.MAX_VALUE else now + maxAge
        parsed.copy(expiresEpochSeconds = expiry)
    }

    private fun parseSetCookieHeaders(
        headers: Map<String, List<String>>,
        origin: CanonicalOrigin
    ): List<OriginCookie> {
        val cookies = mutableListOf<OriginCookie>()
        for ((key, values) in headers) {
            if (key.equals("Set-Cookie", ignoreCase = true)) {
                for (value in values) {
                    val parsed = OriginCookie.parseSetCookie(value, origin)
                    if (parsed != null && !(origin.scheme == CanonicalOrigin.Scheme.HTTP && parsed.isSecure)) {
                        cookies.add(parsed)
                    }
                }
            }
        }
        return cookies
    }
}
