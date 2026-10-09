package io.github.anshuman2401.hermesmobile.data.voice

import io.github.anshuman2401.hermesmobile.data.auth.*
import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.session.*
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.feature.chat.ChatStreamingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

internal class VoiceTestFixture(directory: File, val origin: CanonicalOrigin = CanonicalOrigin.parse("http://example.test").getOrThrow(),
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO) {
    val vault = EncryptedVault(directory, AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = dispatcher)
    val auth = DefaultAuthRepository(vault)
    val sessions = Sessions(vault)
    suspend fun initialize() {
        vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
        vault.setActiveProfileId("synthetic-local")
        vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-old"), origin.originUrl))
    }
    class Sessions(private val vault: VaultStorage) : SessionRepository {
        override val activeSession = MutableStateFlow<SessionHandle?>(SessionHandle("synthetic-stored", "synthetic-runtime"))
        override val activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeTurnStreaming = MutableStateFlow(ChatStreamingStatus())
        var scopeCurrent = true
        override suspend fun captureSendActivityOwner(storedSessionId: String, runtimeSessionId: String): SessionActivityOwner? {
            val authority = (vault.captureWorkspaceRecoveryOwner() as? WorkspaceRecoveryOwnerResult.Captured)?.owner ?: return null
            val partition = (vault.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition ?: return null
            return SessionActivityOwner(this, SessionActivityScope(partition, 1), authority, storedSessionId, runtimeSessionId)
        }
        override suspend fun isSessionActivityConnectionCurrent(scope: SessionActivityScope) = scopeCurrent
        override suspend fun isSessionActivityScopeCurrent(scope: SessionActivityScope): Boolean = scopeCurrent &&
            (vault.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition == scope.partition
        override suspend fun listSessions(forceRefresh: Boolean) = Result.success(SessionListOutcome(emptyList(), false))
        override suspend fun fetchHistorySnapshot(durableSessionId: String) = resumeSession(durableSessionId)
        override suspend fun createDraftSession(title: String?) = Result.success(activeSession.value!!)
        override suspend fun resumeSession(storedSessionId: String) = Result.success(SessionResumeResult("synthetic-runtime", storedSessionId, 0))
        override suspend fun restoreSelectedSession(): SessionHandle? = activeSession.value
        override suspend fun clearActiveSession() { activeSession.value = null }
        override suspend fun getActiveSession(): SessionHandle? = activeSession.value
    }
}
