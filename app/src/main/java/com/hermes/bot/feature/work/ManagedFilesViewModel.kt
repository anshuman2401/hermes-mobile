package com.hermes.bot.feature.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.files.*
import com.hermes.bot.feature.chat.files.FilePreviewPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class ManagedFilePreview(val name: String, val bytes: ByteArray) {
    fun discard() { bytes.fill(0) }
    override fun toString() = "ManagedFilePreview([redacted])"
}

data class ManagedFilesState(
    val listing: ManagedFileListing? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val online: Boolean = false,
    val preview: ManagedFilePreview? = null,
    val conflictName: String? = null,
    val message: String? = null,
    val pathError: String? = null,
    val hideHiddenFiles: Boolean = false
) {
    val visibleEntries: List<ManagedFileEntry>
        get() = listing?.entries?.filter { !hideHiddenFiles || !it.name.startsWith('.') }.orEmpty()

    override fun toString() = "ManagedFilesState([redacted])"
}

class ManagedFilesViewModel(private val repository: ManagedFileRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ManagedFilesState())
    val state: StateFlow<ManagedFilesState> = mutable
    private var request: Job? = null
    private var generation = 0L
    private var pending: ManagedUploadSource? = null
    private var connectionKey: String? = null

    fun bind(key: String?, online: Boolean) {
        if (connectionKey != key) {
            connectionKey = key
            invalidate()
            mutable.value = ManagedFilesState(online = online)
        } else if (!online && mutable.value.online) {
            invalidate()
            mutable.value = ManagedFilesState(online = false, hideHiddenFiles = mutable.value.hideHiddenFiles)
        } else mutable.value = mutable.value.copy(online = online)
    }

    fun selectionFailed() {
        mutable.value = mutable.value.copy(message = "Could not open that document for upload.")
    }

    fun pageStopped() {
        invalidate()
        mutable.value = mutable.value.copy(loading = false, busy = false, preview = null,
            conflictName = null, message = null, pathError = null)
    }

    fun refresh() = load(mutable.value.listing?.path)

    fun setHideHiddenFiles(hide: Boolean) {
        mutable.value = mutable.value.copy(hideHiddenFiles = hide)
    }

    fun clearPathError() {
        if (mutable.value.pathError != null) mutable.value = mutable.value.copy(pathError = null)
    }

    fun openPath(input: String) {
        val current = mutable.value
        val listing = current.listing ?: return
        if (!current.online || current.loading || current.busy || current.preview != null || !listing.canChangePath) return
        val path = input.trim()
        if (!ManagedFilePaths.validPath(path) ||
            listing.lockedRoot?.let { !ManagedFilePaths.within(path, it) } == true) {
            mutable.value = current.copy(pathError = "Enter an allowed absolute folder path.")
            return
        }
        load(path, direct = true)
    }

    private fun load(path: String?, direct: Boolean = false) {
        if (!mutable.value.online) {
            mutable.value = mutable.value.copy(message = "Reconnect to browse Hermes files.")
            return
        }
        invalidate()
        val token = generation
        mutable.value = mutable.value.copy(loading = true, busy = false, message = null, pathError = null)
        request = viewModelScope.launch {
            try {
                val listing = repository.list(path)
                if (token == generation) mutable.value = mutable.value.copy(listing = listing, loading = false, message = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) mutable.value = mutable.value.copy(loading = false,
                    message = if (direct) null else failure.messageForFiles(),
                    pathError = if (direct) "Folder could not be opened. Current folder is unchanged." else null)
            }
        }
    }

    fun openFolder(entry: ManagedFileEntry) {
        if (!entry.directory || entry.brokenLink ||
            mutable.value.listing?.entries?.any { it.path == entry.path } != true) return
        load(entry.path)
    }

    fun openParent(path: String) {
        if (mutable.value.listing?.parent == path) load(path)
    }

    /** Returns false at the section root, allowing Work to switch back to Crons. */
    fun back(): Boolean {
        if (mutable.value.preview != null) {
            mutable.value.preview?.discard()
            mutable.value = mutable.value.copy(preview = null, message = null, pathError = null)
            return true
        }
        if (mutable.value.busy) { invalidate(); mutable.value = mutable.value.copy(busy = false, conflictName = null); return true }
        val parent = mutable.value.listing?.parent ?: return false
        load(parent)
        return true
    }

    fun openPreview(entry: ManagedFileEntry) {
        if (!admitted(entry) || entry.size != null && entry.size > ManagedFileLimits.PREVIEW_BYTES ||
            FilePreviewPolicy.kind(entry.name) == "external") {
            mutable.value = mutable.value.copy(message = "Preview is unavailable for this file. Use Download.")
            return
        }
        invalidate()
        val token = generation
        mutable.value = mutable.value.copy(busy = true, message = null, pathError = null)
        request = viewModelScope.launch {
            var result: ManagedDownload? = null
            try {
                result = repository.download(entry, ManagedFileLimits.PREVIEW_BYTES)
                val bytes = withContext(Dispatchers.IO) { result.file.readBytes() }
                if (token == generation) mutable.value = mutable.value.copy(busy = false,
                    preview = ManagedFilePreview(entry.name, bytes), message = null)
                else bytes.fill(0)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) mutable.value = mutable.value.copy(busy = false, message = failure.messageForFiles())
            } finally { result?.discard() }
        }
    }

    fun upload(source: ManagedUploadSource, overwrite: Boolean = false) {
        val directory = mutable.value.listing?.path ?: return
        if (!mutable.value.online || mutable.value.busy) return
        if (ManagedFilePaths.child(directory, source.name) == null) {
            mutable.value = mutable.value.copy(message = "This filename cannot be uploaded.")
            return
        }
        invalidate()
        pending = if (overwrite) null else source
        val token = generation
        mutable.value = mutable.value.copy(busy = true, conflictName = null, message = null, pathError = null)
        request = viewModelScope.launch {
            try {
                repository.upload(directory, source, overwrite)
                if (token == generation) {
                    mutable.value = mutable.value.copy(busy = false)
                    pending = null
                    refresh()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) {
                    val conflict = failure is ManagedFileException && failure.failure == ManagedFileFailure.CONFLICT && !overwrite
                    mutable.value = mutable.value.copy(busy = false,
                        conflictName = if (conflict) source.name else null,
                        message = failure.messageForFiles())
                    if (!conflict) pending = null
                }
            }
        }
    }

    fun replace() { pending?.let { upload(it, overwrite = true) } }
    fun dismissConflict() { pending = null; mutable.value = mutable.value.copy(conflictName = null, message = null) }

    fun downloadAndSave(entry: ManagedFileEntry, openOutput: () -> OutputStream?) {
        if (!admitted(entry) || !mutable.value.online || mutable.value.busy) return
        invalidate()
        val token = generation
        mutable.value = mutable.value.copy(busy = true, message = null, pathError = null)
        request = viewModelScope.launch {
            var result: ManagedDownload? = null
            try {
                result = repository.download(entry)
                val output = withContext(Dispatchers.IO) { openOutput() }
                    ?: throw ManagedFileException(ManagedFileFailure.NETWORK)
                output.use {
                    if (!repository.export(result, it)) throw ManagedFileException(ManagedFileFailure.STALE)
                }
                if (token == generation) mutable.value = mutable.value.copy(busy = false, message = "File saved.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) mutable.value = mutable.value.copy(busy = false, message = failure.messageForFiles())
            } finally { result?.discard() }
        }
    }

    private fun admitted(entry: ManagedFileEntry): Boolean =
        !entry.directory && !entry.brokenLink && mutable.value.listing?.entries?.any { it.path == entry.path } == true

    private fun invalidate() {
        generation++
        request?.cancel(); request = null
        mutable.value.preview?.discard()
        pending = null
        mutable.value = mutable.value.copy(preview = null)
    }

    override fun onCleared() { invalidate() }
}

private fun Exception.messageForFiles(): String =
    (this as? ManagedFileException)?.failure?.message ?: ManagedFileFailure.NETWORK.message
