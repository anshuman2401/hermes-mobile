package io.github.anshuman2401.hermesmobile.data.files

import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.local.AuthCredentialLease
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ManagedDownload internal constructor(val name: String, internal val file: File,
    internal val owner: ManagedFilesOwner) {
    fun discard() { file.delete() }
    override fun toString() = "ManagedDownload([redacted])"
}
class ManagedFilesOwner internal constructor(val localId: String, val origin: CanonicalOrigin,
    val lease: AuthCredentialLease) {
    override fun toString() = "ManagedFilesOwner([redacted])"
}

interface ManagedFileRepository {
    suspend fun list(path: String?): ManagedFileListing
    suspend fun upload(directory: String, source: ManagedUploadSource, overwrite: Boolean)
    suspend fun download(entry: ManagedFileEntry, maxBytes: Long = ManagedFileLimits.TRANSFER_BYTES): ManagedDownload
    suspend fun isCurrent(download: ManagedDownload): Boolean
    suspend fun export(download: ManagedDownload, output: java.io.OutputStream): Boolean
}

class DefaultManagedFileRepository(private val vault: VaultStorage, private val auth: AuthRepository,
    private val adapter: ManagedFileProtocolAdapter, private val cacheDir: File) : ManagedFileRepository {
    private suspend fun capture(): ManagedFilesOwner {
        val id = vault.getActiveProfileId() ?: throw ManagedFileException(ManagedFileFailure.AUTH)
        val selected = vault.getConnectionProfile(id) ?: throw ManagedFileException(ManagedFileFailure.AUTH)
        val origin = CanonicalOrigin.parse(selected.normalizedOrigin).getOrNull()
            ?: throw ManagedFileException(ManagedFileFailure.STALE)
        val lease = vault.readAuthCredentialLease(origin.originUrl)
            ?: throw ManagedFileException(ManagedFileFailure.AUTH)
        if (lease.profileLocalId != id || lease.origin != origin.originUrl)
            throw ManagedFileException(ManagedFileFailure.STALE)
        return ManagedFilesOwner(id, origin, lease)
    }

    private suspend fun current(owner: ManagedFilesOwner): Boolean {
        if (vault.getActiveProfileId() != owner.localId) return false
        val selected = vault.getConnectionProfile(owner.localId) ?: return false
        val origin = CanonicalOrigin.parse(selected.normalizedOrigin).getOrNull() ?: return false
        return owner.origin.isSameOrigin(origin) && auth.isResponseCurrent(owner.lease)
    }

    override suspend fun list(path: String?): ManagedFileListing {
        val owner = capture()
        val result = adapter.list(owner.origin, owner.lease, path)
        if (!current(ManagedFilesOwner(owner.localId, owner.origin, result.lease)))
            throw ManagedFileException(ManagedFileFailure.STALE)
        return result.listing
    }

    override suspend fun upload(directory: String, source: ManagedUploadSource, overwrite: Boolean) {
        val target = ManagedFilePaths.child(directory, source.name)
            ?: throw ManagedFileException(ManagedFileFailure.INVALID)
        if (source.size != null && (source.size < 0 || source.size > ManagedFileLimits.TRANSFER_BYTES))
            throw ManagedFileException(ManagedFileFailure.TOO_LARGE)
        val owner = capture()
        try {
            val result = adapter.upload(owner.origin, owner.lease, target, source, overwrite)
            if (!current(ManagedFilesOwner(owner.localId, owner.origin, result.lease)))
                throw ManagedFileException(ManagedFileFailure.STALE)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ManagedFileException) { throw failure }
        catch (_: Exception) { throw ManagedFileException(ManagedFileFailure.UNKNOWN) }
    }

    override suspend fun download(entry: ManagedFileEntry, maxBytes: Long): ManagedDownload {
        if (entry.directory || entry.brokenLink || !ManagedFilePaths.validPath(entry.path))
            throw ManagedFileException(ManagedFileFailure.INVALID)
        val owner = capture()
        val folder = File(cacheDir, "managed-file-transfers").apply { mkdirs() }
        val temporary = File(folder, UUID.randomUUID().toString())
        try {
            val result = adapter.download(owner.origin, owner.lease, entry.path, temporary, maxBytes)
            if (result.bytes > maxBytes) throw ManagedFileException(ManagedFileFailure.TOO_LARGE)
            val finalOwner = ManagedFilesOwner(owner.localId, owner.origin, result.lease)
            if (!current(finalOwner)) throw ManagedFileException(ManagedFileFailure.STALE)
            return ManagedDownload(entry.name, temporary, finalOwner)
        } catch (cancelled: CancellationException) { temporary.delete(); throw cancelled }
        catch (failure: Exception) {
            temporary.delete()
            throw if (failure is ManagedFileException) failure else ManagedFileException(ManagedFileFailure.NETWORK)
        }
    }

    override suspend fun isCurrent(download: ManagedDownload): Boolean = current(download.owner) && download.file.exists()

    override suspend fun export(download: ManagedDownload, output: java.io.OutputStream): Boolean = withContext(Dispatchers.IO) {
        if (!isCurrent(download)) return@withContext false
        download.file.inputStream().use { input -> input.copyTo(output, 8192) }
        isCurrent(download)
    }

    companion object {
        fun clearOldTransfers(cacheDir: File) { File(cacheDir, "managed-file-transfers").deleteRecursively() }
    }
}
