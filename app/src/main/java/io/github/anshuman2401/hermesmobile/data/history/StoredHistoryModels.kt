package io.github.anshuman2401.hermesmobile.data.history

import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonValue
import io.github.anshuman2401.hermesmobile.data.local.AuthCredentialLease
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Explicit operation identity, in memory only. The presentation owner invalidates on supersession. */
class StoredHistoryScope internal constructor(
    val localProfileId: String,
    val origin: CanonicalOrigin,
    val remoteProfile: String,
    val requestedSessionId: String,
    val requestGeneration: Long,
    val tailGeneration: Long,
    internal val lease: AuthCredentialLease,
    internal val selectedContext: String?
) {
    @Volatile internal var current = true
        private set
    fun invalidate() { current = false }
    override fun toString() = "StoredHistoryScope([REDACTED])"
}

/** Single sequential read cycle; five successful pages and two size reductions share one deadline. */
class StoredHistoryBudget(
    internal val scope: StoredHistoryScope,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val started = nanoTime()
    private class Continuation(scope: StoredHistoryScope) {
        @Volatile var receipt = scope.lease
        @Volatile var limit = 120
    }
    private var continuation = Continuation(scope)
    internal var receipt
        get() = continuation.receipt
        set(value) { continuation.receipt = value }
    /** A gesture renews its deadline/counters, not the selected window's receipt or limit. */
    internal fun nextCycle(): StoredHistoryBudget = StoredHistoryBudget(scope, nanoTime).also {
        it.continuation = continuation
    }
    @Volatile var headerDecision: StoredHistoryHeaderDecision = StoredHistoryHeaderDecision.unavailable()
        private set
    internal fun beginHeaders(): StoredHistoryHeaderDecision = StoredHistoryHeaderDecision(receipt).also { headerDecision = it }
    /** Captures this attempt only. Before dispatch the capability explicitly reports UNAVAILABLE. */
    suspend fun awaitHeaderDecision(): StoredHistoryHeaderOutcome {
        val decision = headerDecision
        if (decision.isComplete) return decision.await()
        val remaining = remainingMillis()
        if (remaining <= 0) return StoredHistoryHeaderOutcome(StoredHistoryHeaderState.DEADLINE)
        return withTimeoutOrNull(remaining) { decision.await() }
            ?: StoredHistoryHeaderOutcome(StoredHistoryHeaderState.DEADLINE)
    }
    var acceptedLimit: Int
        get() = continuation.limit
        internal set(value) { continuation.limit = value }
    var successfulPages = 0
        internal set
    var httpAttempts = 0
        internal set
    internal var terminal = false
    internal var inUse = false
    internal fun remainingMillis(): Long = 30_000L - (nanoTime() - started) / 1_000_000L
    override fun toString() = "StoredHistoryBudget(limit=$acceptedLimit, pages=$successfulPages, attempts=$httpAttempts)"
}

enum class StoredHistoryHeaderState { UNAVAILABLE, ACCEPTED, DENIED, FAILED, CANCELLED, DEADLINE }

class StoredHistoryHeaderOutcome internal constructor(
    val state: StoredHistoryHeaderState,
    internal val acceptedReceipt: AuthCredentialLease? = null
) {
    override fun toString() = "StoredHistoryHeaderOutcome(state=$state, receipt=[REDACTED])"
}

/** Issuing identity and its own accepted successor only; completion never waits for a body. */
class StoredHistoryHeaderDecision internal constructor(internal val issuingReceipt: AuthCredentialLease?) {
    private val terminal = CompletableDeferred<StoredHistoryHeaderOutcome>()
    val isComplete: Boolean get() = terminal.isCompleted
    internal suspend fun await() = terminal.await()
    internal fun complete(state: StoredHistoryHeaderState, receipt: AuthCredentialLease? = null) {
        terminal.complete(StoredHistoryHeaderOutcome(state, receipt))
    }
    override fun toString() = "StoredHistoryHeaderDecision(complete=$isComplete, owner=[REDACTED])"
    internal companion object {
        fun unavailable() = StoredHistoryHeaderDecision(null).apply { complete(StoredHistoryHeaderState.UNAVAILABLE) }
    }
}

/** Fully validated, in-memory HOLD. No public page alias or admission/selection capability. */
class StoredHistoryCandidate internal constructor(internal val page: StoredHistoryPage) {
    val isDescendant: Boolean get() = page.requestedSessionId != page.resolvedSessionId
    override fun toString() = "StoredHistoryCandidate(held=true, descendant=$isDescendant, data=[REDACTED])"
}

/** Missing property differs from present JSON null. Wire order and row identity never derive from text. */
class StoredHistoryRow internal constructor(
    val id: Long,
    val role: String,
    val fields: Map<String, JsonValue>,
    val canonicalBytes: Int,
    val truncated: Boolean,
    val editable: io.github.anshuman2401.hermesmobile.data.gateway.EditableMessage? = null
) {
    fun hasProperty(name: String) = fields.containsKey(name)
    override fun toString() = "StoredHistoryRow([REDACTED])"
}

class StoredHistoryPage internal constructor(
    val scope: StoredHistoryScope,
    val requestedSessionId: String,
    val resolvedSessionId: String,
    val offset: Int,
    val limit: Int,
    val rows: List<StoredHistoryRow>
) {
    val returned: Int get() = rows.size
    val nextOffset: Int get() = offset + returned
    val possiblyEarlier: Boolean get() = returned == limit
    override fun toString() = "StoredHistoryPage([REDACTED])"
}

enum class StoredHistoryFailure { UNAVAILABLE, STALE, OVERSIZE, DEPTH, INTEGRITY, UNBOUND_DESCENDANT, DEADLINE, BUDGET }
class StoredHistoryException(val failure: StoredHistoryFailure,
    val requestedSessionId: String? = null, val resolvedSessionId: String? = null) : IllegalStateException("Stored history ${failure.name}")
