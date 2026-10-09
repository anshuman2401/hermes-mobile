package io.github.anshuman2401.hermesmobile.data.files

import io.github.anshuman2401.hermesmobile.data.auth.*
import io.github.anshuman2401.hermesmobile.data.local.AuthCredentialLease
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.*

class FileDownload(val content: FileContent, val receipt: AuthCredentialLease) {
    override fun toString() = "FileDownload([redacted])"
}
interface FileProtocolAdapter {
    suspend fun download(origin: CanonicalOrigin, profile: String, sessionId: String, lease: AuthCredentialLease,
        reference: ConversationFile, onReceipt: (AuthCredentialLease) -> Unit): FileDownload
}
class DefaultFileProtocolAdapter(private val auth: AuthRepository, private val transport: FileHttpTransport,
    private val timeoutMillis: Long = 60_000) : FileProtocolAdapter {
    init { require(timeoutMillis in 1..60_000) }
    override suspend fun download(origin: CanonicalOrigin, profile: String, sessionId: String, lease: AuthCredentialLease,
        reference: ConversationFile, onReceipt: (AuthCredentialLease) -> Unit): FileDownload {
        var bytes: ByteArray? = null
        try {
            return withTimeoutOrNull(timeoutMillis) {
                if (sessionId.isBlank() || profile.isBlank() || ConversationFileReferences.reference(reference.path) == null)
                    throw ConversationFileException(FileFailure.INVALID)
                val exchange = auth.exchangeAuthenticated(origin, PATH, lease) { headers ->
                    transport.open(origin, PATH, profile, sessionId, reference, headers, timeoutMillis)
                }.getOrThrow()
                exchange.response.use { response ->
                    onReceipt(exchange.receipt)
                    when(response.statusCode) {
                        404, 405, 410, 501 -> throw ConversationFileException(FileFailure.UNAVAILABLE)
                        413 -> throw ConversationFileException(FileFailure.TOO_LARGE)
                        !in 200..299 -> throw ConversationFileException(FileFailure.NETWORK)
                    }
                    bytes = response.readBoundedBody()
                    if (!auth.isResponseCurrent(exchange.receipt)) throw ConversationFileException(FileFailure.STALE)
                    currentCoroutineContext().ensureActive()
                    FileDownload(FileContent(reference, bytes!!), exchange.receipt)
                }
            } ?: throw ConversationFileException(FileFailure.NETWORK)
        } catch (cancelled: CancellationException) { bytes?.fill(0); throw cancelled }
        catch (failure: Exception) {
            bytes?.fill(0)
            throw when(failure) {
                is ConversationFileException -> failure
                is AuthSessionRejectedException -> ConversationFileException(FileFailure.AUTH)
                is StaleAuthenticatedResponseException -> ConversationFileException(FileFailure.STALE)
                else -> ConversationFileException(FileFailure.NETWORK)
            }
        }
    }
    companion object { const val PATH = "/api/fs/download" }
}
