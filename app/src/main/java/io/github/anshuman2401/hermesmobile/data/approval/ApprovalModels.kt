package io.github.anshuman2401.hermesmobile.data.approval

/** Choices are mapped to the wire only by the gateway adapter. */
enum class ApprovalChoice { ONCE, SESSION, ALWAYS, DENY }
enum class ApprovalOutcome { SENT, UNKNOWN, REJECTED, UNAVAILABLE }

data class ApprovalRequest(
    val requestId: String,
    val runtimeId: String,
    val details: String,
    val choices: Set<ApprovalChoice>,
    val serverRequestId: String? = null
) { override fun toString() = "ApprovalRequest([redacted])" }

data class ApprovalInbox(
    val requests: List<ApprovalRequest> = emptyList(),
    val revision: Long = 0,
    val available: Boolean = false,
    val cancelled: Map<String, String> = emptyMap()
) { override fun toString() = "ApprovalInbox([redacted])" }

/** Revision guards prevent an older read from reviving a decision/cancellation. */
class ApprovalInboxStore {
    val state = kotlinx.coroutines.flow.MutableStateFlow(ApprovalInbox())
    @Synchronized fun reset() { state.value = ApprovalInbox(revision = state.value.revision + 1) }
    @Synchronized fun available(value: Boolean) { state.value = state.value.copy(available = value, revision = state.value.revision + 1) }
    @Synchronized fun receive(request: ApprovalRequest) {
        val old = state.value
        val existing = old.requests.find { it.runtimeId == request.runtimeId && it.requestId == request.requestId }
        if (existing?.serverRequestId != null && request.serverRequestId == null) return
        if (existing == request) return
        if (existing == null && old.requests.size >= 128) return
        state.value = old.copy(requests = if (existing == null) old.requests + request else
            old.requests.map { if (it === existing) request else it }, revision = old.revision + 1,
            cancelled = old.cancelled - request.runtimeId)
    }
    @Synchronized fun cancel(runtime: String?, serverId: String, reason: String) {
        val old = state.value
        val matches = old.requests.filter { it.runtimeId == runtime && it.serverRequestId == serverId }
        if (matches.isEmpty()) return
        state.value = old.copy(requests = old.requests - matches.toSet(), revision = old.revision + 1,
            cancelled = (old.cancelled + (matches.first().runtimeId to reason)).entries.toList().takeLast(128).associate { it.toPair() })
    }
    @Synchronized fun reconcile(runtime: String, requests: List<ApprovalRequest>, revision: Long): Boolean {
        val old = state.value
        if (old.revision != revision) return false
        val merged = requests.map { next -> old.requests.find { it.runtimeId == runtime && it.requestId == next.requestId }
            ?.serverRequestId?.let { next.copy(serverRequestId = it) } ?: next }
        val combined = old.requests.filter { it.runtimeId != runtime } + merged
        if (combined.size > 128) return false
        state.value = old.copy(requests = combined, revision = old.revision + 1,
            cancelled = if (requests.isNotEmpty()) old.cancelled - runtime else old.cancelled)
        return true
    }
    @Synchronized fun changed() { state.value = state.value.copy(revision = state.value.revision + 1) }
}
