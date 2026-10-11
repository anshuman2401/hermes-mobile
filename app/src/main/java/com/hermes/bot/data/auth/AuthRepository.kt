package com.hermes.bot.data.auth

import com.hermes.bot.data.gateway.GatewayTicket
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.SignOutCacheChoice
import com.hermes.bot.data.origin.CanonicalOrigin

/**
 * Authentication repository contract for private Hermes host access.
 * Scoped to INT-001 certified cookie session and WebSocket ticket flow.
 * Governed by ADR-0003 and RPR-003.
 */
data class HostStatus(
    val version: String,
    val authRequired: Boolean,
    val gatewayRunning: Boolean,
    val rawStatusJson: String = "",
    /**
     * Password-capable provider IDs advertised by the host in the `/api/status` `auth_providers`
     * array. Sanitized at parse time; never contains guessed or default values.
     * For the certified Hermes v0.21.1 host this resolves to `["basic"]`.
     */
    val authProviders: List<String> = emptyList()
)

/** Header-first exchange boundary. Call cancellation must also close any pending response. */
interface AuthenticatedHttpCall<T : AuthenticatedHttpHeaders> {
    suspend fun awaitHeaders(): T
    fun cancel()
}

interface AuthenticatedHttpHeaders : AutoCloseable {
    val statusCode: Int
    val headers: Map<String, List<String>>
}

class AuthenticatedExchange<T : AuthenticatedHttpHeaders>(
    val response: T,
    val receipt: com.hermes.bot.data.local.AuthCredentialLease
)

class AuthOriginPolicyRejectedException : SecurityException("Redirect rejected by exact-origin policy.")

/** Definite absence/rejection of the current origin-bound login; contains no server text. */
class AuthSessionRejectedException(statusCode: Int? = null) : SecurityException(
    if (statusCode == null) "No current authenticated session" else "Current authenticated session unauthorized (HTTP $statusCode)"
)

class StaleAuthenticatedResponseException : IllegalStateException("Authenticated response superseded")

class SavedPasswordRejectedException : SecurityException("Saved credentials rejected")

interface AuthRepository {
    fun invalidatePendingAuthentication() {}
    /** Non-secret presentation invalidation, independent of network cookie revisions. */
    val localAccessRevision: kotlinx.coroutines.flow.StateFlow<Long>? get() = null
    val sessionRejections: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()
    suspend fun prepareLocalLogin(origin: CanonicalOrigin): Boolean = false
    suspend fun recoverSession(origin: CanonicalOrigin): Result<Unit> = Result.failure(AuthSessionRejectedException())
    suspend fun recoverSession(origin: CanonicalOrigin, explicitConnect: Boolean): Result<Unit> = recoverSession(origin)
    suspend fun rememberedEndpoint(host: String? = null): CanonicalOrigin? = null
    suspend fun rememberLoginPreference(origin: CanonicalOrigin): Boolean = true
    suspend fun hasRememberedLogin(origin: CanonicalOrigin): Boolean = false
    suspend fun hasAutomaticLogin(origin: CanonicalOrigin): Boolean = false
    /** Endpoint navigation revokes current authority without counting as deliberate sign-out. */
    suspend fun leaveConnection() { signOut(cacheChoice = SignOutCacheChoice.KEEP) }
    suspend fun forgetRememberedLogin(origin: CanonicalOrigin) {}
    suspend fun noteSuccessfulEndpoint(origin: CanonicalOrigin) {}
    suspend fun selectConnectionForEndpoint(origin: CanonicalOrigin): Boolean = false

    suspend fun checkStatus(origin: CanonicalOrigin): Result<HostStatus> = checkStatus(origin.originUrl)
    suspend fun checkStatus(origin: String): Result<HostStatus> =
        CanonicalOrigin.parse(origin).fold(
            onSuccess = { checkStatus(it) },
            onFailure = { Result.failure(it) }
        )

    suspend fun hasValidSession(): Boolean = hasValidSession(origin = null as CanonicalOrigin?)
    suspend fun hasValidSession(origin: CanonicalOrigin?): Boolean = hasValidSession()
    suspend fun hasValidSession(origin: String): Boolean =
        CanonicalOrigin.parse(origin).getOrNull()?.let { hasValidSession(it) } ?: hasValidSession()

    suspend fun login(
        origin: CanonicalOrigin,
        username: String,
        password: CharArray,
        provider: String = ""
    ): Result<AuthCredential> = login(origin.originUrl, username, password, provider)

    suspend fun login(
        origin: String,
        username: String,
        password: CharArray,
        provider: String = ""
    ): Result<AuthCredential> =
        CanonicalOrigin.parse(origin).fold(
            onSuccess = { login(it, username, password, provider) },
            onFailure = { Result.failure(it) }
        )

    suspend fun login(origin: CanonicalOrigin, username: String, password: CharArray,
        remember: Boolean, provider: String = ""): Result<AuthCredential> =
        if (remember) login(origin, username, password, provider)
        else { password.fill('\u0000'); Result.failure(IllegalStateException("Temporary login unsupported")) }

    suspend fun getStoredCredential(): AuthCredential? = getStoredCredential(origin = null as CanonicalOrigin?)
    suspend fun getStoredCredential(origin: CanonicalOrigin?): AuthCredential? = getStoredCredential()
    suspend fun getStoredCredential(origin: String): AuthCredential? =
        CanonicalOrigin.parse(origin).getOrNull()?.let { getStoredCredential(it) } ?: getStoredCredential()

    suspend fun mintWebSocketTicket(origin: CanonicalOrigin): Result<GatewayTicket> = mintWebSocketTicket(origin.originUrl)
    suspend fun mintWebSocketTicket(origin: String): Result<GatewayTicket> =
        CanonicalOrigin.parse(origin).fold(
            onSuccess = { mintWebSocketTicket(it) },
            onFailure = { Result.failure(it) }
        )

    /** Adapter seam only: caller owns/ closes accepted response; body work follows lock release. */
    suspend fun <T : AuthenticatedHttpHeaders> exchangeAuthenticated(
        origin: CanonicalOrigin,
        requestPath: String,
        call: (Map<String, String>) -> AuthenticatedHttpCall<T>
    ): Result<AuthenticatedExchange<T>> = Result.failure(IllegalStateException("Unsupported authenticated exchange"))

    /** Captured reads fail closed before transport creation if their owner has changed. */
    suspend fun <T : AuthenticatedHttpHeaders> exchangeAuthenticated(
        origin: CanonicalOrigin,
        requestPath: String,
        expected: com.hermes.bot.data.local.AuthCredentialLease,
        call: (Map<String, String>) -> AuthenticatedHttpCall<T>
    ): Result<AuthenticatedExchange<T>> = Result.failure(StaleAuthenticatedResponseException())

    suspend fun isResponseCurrent(receipt: com.hermes.bot.data.local.AuthCredentialLease): Boolean = false

    /**
     * Local sign-out. PR-6 REQ-02: [cacheChoice] is NOT defaulted — every
     * caller must make the KEEP/CLEAR decision explicitly; retention is only
     * ever an explicitly designated [SignOutCacheChoice.KEEP] (REQ-07).
     */
    suspend fun signOut(clearDrafts: Boolean = false, cacheChoice: SignOutCacheChoice)
    suspend fun notifyAuthExpired()
}
