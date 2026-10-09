package io.github.anshuman2401.hermesmobile.data.files

import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class FileOwner internal constructor(activity: SessionActivityOwner) {
    private val accepted = java.util.concurrent.atomic.AtomicReference(activity)
    internal val activity: SessionActivityOwner get() = accepted.get()
    internal val exchanging = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun accept(receipt: AuthCredentialLease) {
        val original = activity
        if (receipt.profileLocalId != original.authority.lease.profileLocalId || receipt.origin != original.authority.lease.origin)
            throw ConversationFileException(FileFailure.STALE)
        accepted.set(SessionActivityOwner(original.repository,
            SessionActivityScope(original.scope.partition.copy(cacheEpoch = receipt.cacheEpoch), original.scope.gatewayGeneration),
            WorkspaceRecoveryOwner(receipt, original.authority.selection), original.storedSessionId, original.runtimeSessionId))
    }
    override fun toString() = "FileOwner([redacted])"
}
interface ConversationFileRepository {
    suspend fun capture(storedId: String, runtimeId: String): FileOwner?
    suspend fun isCurrent(owner: FileOwner): Boolean
    suspend fun download(owner: FileOwner, reference: ConversationFile): FileContent
}
class DefaultConversationFileRepository(private val sessions: SessionRepository, private val vault: VaultStorage,
    private val auth: AuthRepository, private val adapter: FileProtocolAdapter) : ConversationFileRepository {
    override suspend fun capture(storedId: String, runtimeId: String): FileOwner? =
        sessions.captureSendActivityOwner(storedId, runtimeId)?.let(::FileOwner)

    // Header acceptance rotates the auth/cache epoch. While that exchange owns the handoff,
    // monitor structural ownership; only its exact accepted receipt can admit the result.
    override suspend fun isCurrent(owner: FileOwner): Boolean {
        val initial = owner.activity
        val active = sessions.activeSession.value
        if (active?.storedSessionId != initial.storedSessionId || active.runtimeSessionId != initial.runtimeSessionId) return false
        val current = (vault.captureWorkspaceRecoveryOwner() as? WorkspaceRecoveryOwnerResult.Captured)?.owner ?: return false
        val activity = owner.activity
        val expected = activity.authority
        val sameSelection = when (val selection = expected.selection) {
            is SelectedContextReadResult.Selected -> (current.selection as? SelectedContextReadResult.Selected)?.name == selection.name
            SelectedContextReadResult.Absent -> current.selection == SelectedContextReadResult.Absent
            else -> false
        }
        if (!sameSelection || current.lease.profileLocalId != expected.lease.profileLocalId || current.lease.origin != expected.lease.origin)
            return false
        if (!sessions.isSessionActivityConnectionCurrent(activity.scope)) return false
        val exchanging = owner.exchanging.get()
        if (!exchanging && current.lease.epoch != expected.lease.epoch) return false
        if (exchanging) {
            if (!sessions.isSessionActivityConnectionCurrent(activity.scope)) return false
        } else if (!sessions.isSessionActivityScopeCurrent(activity.scope)) return false
        val final = sessions.activeSession.value
        return final?.storedSessionId == activity.storedSessionId && final.runtimeSessionId == activity.runtimeSessionId
    }
    override suspend fun download(owner: FileOwner, reference: ConversationFile): FileContent {
        var content: FileContent? = null
        try {
            if (!isCurrent(owner) || !auth.isResponseCurrent(owner.activity.authority.lease))
                throw ConversationFileException(FileFailure.STALE)
            val activity = owner.activity
            val lease = activity.authority.lease
            val origin = CanonicalOrigin.parse(lease.origin).getOrNull() ?: throw ConversationFileException(FileFailure.STALE)
            val profile = (activity.authority.selection as? SelectedContextReadResult.Selected)?.name ?: "default"
            owner.exchanging.set(true)
            val result = adapter.download(origin, profile, activity.storedSessionId, lease, reference, owner::accept)
            content = result.content
            owner.exchanging.set(false)
            if (!isCurrent(owner) || !auth.isResponseCurrent(result.receipt)) throw ConversationFileException(FileFailure.STALE)
            currentCoroutineContext().ensureActive()
            return result.content
        } catch (cancelled: CancellationException) { content?.discard(); throw cancelled }
        catch (failure: ConversationFileException) { content?.discard(); throw failure }
        catch (_: Exception) { content?.discard(); throw ConversationFileException(FileFailure.NETWORK) }
        finally { owner.exchanging.set(false) }
    }
}
