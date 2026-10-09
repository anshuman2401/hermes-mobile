package io.github.anshuman2401.hermesmobile.data.voice

import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class VoiceOwner internal constructor(activity: SessionActivityOwner) {
    private val accepted = java.util.concurrent.atomic.AtomicReference(activity)
    internal val activity: SessionActivityOwner get() = accepted.get()
    internal val exchanging = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun accept(receipt: AuthCredentialLease) {
        val original = activity
        if (receipt.profileLocalId != original.authority.lease.profileLocalId || receipt.origin != original.authority.lease.origin)
            throw VoiceException(VoiceFailure.STALE)
        accepted.set(SessionActivityOwner(original.repository,
            SessionActivityScope(original.scope.partition.copy(cacheEpoch = receipt.cacheEpoch), original.scope.gatewayGeneration),
            WorkspaceRecoveryOwner(receipt, original.authority.selection), original.storedSessionId, original.runtimeSessionId))
    }
    override fun toString() = "VoiceOwner([redacted])"
}
interface VoiceRepository {
    suspend fun capture(storedId: String, runtimeId: String): VoiceOwner?
    suspend fun isCurrent(owner: VoiceOwner): Boolean
    suspend fun transcribe(owner: VoiceOwner, wav: ByteArray): VoiceResult
}
class DefaultVoiceRepository(private val sessions: SessionRepository, private val vault: VaultStorage,
    private val auth: AuthRepository, private val adapter: VoiceProtocolAdapter) : VoiceRepository {
    override suspend fun capture(storedId: String, runtimeId: String): VoiceOwner? =
        sessions.captureSendActivityOwner(storedId, runtimeId)?.let(::VoiceOwner)

    // Header acceptance rotates the auth/cache epoch. While that exchange owns the handoff,
    // monitor structural ownership; only its exact accepted receipt can admit the result.
    override suspend fun isCurrent(owner: VoiceOwner): Boolean {
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
        val exchanging = owner.exchanging.get()
        if (!exchanging && current.lease.epoch != expected.lease.epoch) return false
        if (exchanging) {
            if (!sessions.isSessionActivityConnectionCurrent(activity.scope)) return false
        } else if (!sessions.isSessionActivityScopeCurrent(activity.scope)) return false
        val final = sessions.activeSession.value
        return final?.storedSessionId == activity.storedSessionId && final.runtimeSessionId == activity.runtimeSessionId
    }
    override suspend fun transcribe(owner: VoiceOwner, wav: ByteArray): VoiceResult {
        try {
            if (!isCurrent(owner) || !auth.isResponseCurrent(owner.activity.authority.lease))
                return VoiceResult.Failed(VoiceFailure.STALE)
            val activity = owner.activity
            val lease = activity.authority.lease
            val origin = CanonicalOrigin.parse(lease.origin).getOrNull() ?: return VoiceResult.Failed(VoiceFailure.STALE)
            val profile = (activity.authority.selection as? SelectedContextReadResult.Selected)?.name ?: "default"
            owner.exchanging.set(true)
            val result = adapter.transcribe(origin, profile, lease, wav, owner::accept)
            owner.exchanging.set(false)
            if (!isCurrent(owner) || !auth.isResponseCurrent(result.receipt)) return VoiceResult.Failed(VoiceFailure.STALE)
            currentCoroutineContext().ensureActive()
            return VoiceResult.Transcript(result.transcript)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: VoiceException) { return VoiceResult.Failed(failure.failure) }
        catch (_: Exception) { return VoiceResult.Failed(VoiceFailure.NETWORK) }
        finally { owner.exchanging.set(false) }
    }
}
class VoiceOperation internal constructor(internal val owner: VoiceOwner, internal val capture: VoiceCapture) {
    override fun toString() = "VoiceOperation([redacted])"
}
interface VoiceDictationCoordinator {
    suspend fun start(storedId: String, runtimeId: String): VoiceOperation
    suspend fun isCurrent(operation: VoiceOperation): Boolean
    fun recordingFailure(operation: VoiceOperation): VoiceFailure? = null
    suspend fun stopAndTranscribe(operation: VoiceOperation): VoiceResult
    fun cancel(operation: VoiceOperation)
}
class DefaultVoiceDictationCoordinator(private val repository: VoiceRepository,
    private val recorder: VoiceRecorder) : VoiceDictationCoordinator {
    override suspend fun start(storedId: String, runtimeId: String): VoiceOperation {
        val owner = repository.capture(storedId, runtimeId) ?: throw VoiceException(VoiceFailure.STALE)
        val capture = recorder.start()
        try {
            if (!repository.isCurrent(owner)) throw VoiceException(VoiceFailure.STALE)
            currentCoroutineContext().ensureActive()
            return VoiceOperation(owner, capture)
        } catch (failure: Exception) { capture.cancel(); throw failure }
    }
    override fun recordingFailure(operation: VoiceOperation): VoiceFailure? = operation.capture.failure
    override suspend fun isCurrent(operation: VoiceOperation) = repository.isCurrent(operation.owner)
    override suspend fun stopAndTranscribe(operation: VoiceOperation): VoiceResult {
        var wav: ByteArray? = null
        try {
            wav = operation.capture.stop()
            if (!isCurrent(operation)) return VoiceResult.Failed(VoiceFailure.STALE)
            return repository.transcribe(operation.owner, wav)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: VoiceException) { return VoiceResult.Failed(failure.failure) }
        catch (_: Exception) { return VoiceResult.Failed(VoiceFailure.MICROPHONE) }
        finally { wav?.fill(0); operation.capture.cancel() }
    }
    override fun cancel(operation: VoiceOperation) { operation.capture.cancel() }
}
