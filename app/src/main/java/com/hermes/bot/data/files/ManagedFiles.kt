package com.hermes.bot.data.files

import com.hermes.bot.data.gateway.json.GatewayJson
import java.io.InputStream
import java.util.Locale

object ManagedFileLimits {
    const val TRANSFER_BYTES = 100L * 1024 * 1024
    const val PREVIEW_BYTES = 20L * 1024 * 1024
    const val LIST_BYTES = 2 * 1024 * 1024
    const val ENTRIES = 1000
    const val PATH_CHARS = 4096
}

enum class ManagedFileFailure(val message: String) {
    AUTH("Sign in again to manage Hermes files."),
    STALE("The connection changed. Reopen Files and try again."),
    UNSUPPORTED("File management is unavailable on this Hermes host."),
    FORBIDDEN("Hermes does not allow access to this folder or file."),
    MISSING("This folder or file is no longer available. Refresh Files."),
    CONFLICT("A file with this name already exists."),
    TOO_LARGE("This file exceeds the 100 MB transfer limit."),
    INVALID("Hermes returned an unsupported file listing."),
    NETWORK("Could not complete the file action. Check the connection and try again."),
    UNKNOWN("The upload result is unknown. Refresh the folder before trying again.")
}
class ManagedFileException(val failure: ManagedFileFailure) : Exception(failure.name)

data class ManagedFileEntry(val name: String, val path: String, val directory: Boolean,
    val size: Long?, val modifiedSeconds: Double?, val brokenLink: Boolean = false) {
    override fun toString() = "ManagedFileEntry([redacted])"
}
data class ManagedFileListing(val path: String, val parent: String?, val entries: List<ManagedFileEntry>,
    val limited: Boolean, val canChangePath: Boolean, val lockedRoot: String?) {
    override fun toString() = "ManagedFileListing([redacted])"
}
data class ManagedUploadSource(val name: String, val size: Long?, val open: () -> InputStream) {
    override fun toString() = "ManagedUploadSource([redacted])"
}

object ManagedFilePaths {
    fun validPath(value: String): Boolean = value.isNotBlank() && value.length <= ManagedFileLimits.PATH_CHARS &&
        value.startsWith('/') && !value.startsWith("//") &&
        value.none { it.code < 32 || it.code == 127 || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } &&
        value.split('/').none { it == ".." || it == "." }

    fun uploadName(value: String): String? {
        if (value.isBlank() || value.length > 180 || value == "." || value == ".." ||
            value.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 ||
                it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }) return null
        return value
    }

    fun child(directory: String, name: String): String? = uploadName(name)?.let {
        if (!validPath(directory)) return null
        val path = directory.trimEnd('/') + "/" + it
        path.takeIf(::validPath)
    }

    fun within(path: String, root: String): Boolean = path == root ||
        path.startsWith(root.trimEnd('/') + "/")
}

object ManagedFileListingParser {
    fun parse(body: String): ManagedFileListing {
        if (body.length > ManagedFileLimits.LIST_BYTES) throw ManagedFileException(ManagedFileFailure.INVALID)
        val root = GatewayJson.parseOrNull(body)?.asObjectOrNull()
            ?: throw ManagedFileException(ManagedFileFailure.INVALID)
        val path = root["path"]?.asStringOrNull()?.takeIf(ManagedFilePaths::validPath)
            ?: throw ManagedFileException(ManagedFileFailure.INVALID)
        val parent = root["parent"]?.asStringOrNull()
        val lockedRoot = root["locked_root"]?.asStringOrNull()
        if (parent != null && !ManagedFilePaths.validPath(parent) ||
            lockedRoot != null && !ManagedFilePaths.validPath(lockedRoot))
            throw ManagedFileException(ManagedFileFailure.INVALID)
        if (lockedRoot != null && !ManagedFilePaths.within(path, lockedRoot))
            throw ManagedFileException(ManagedFileFailure.INVALID)
        if (parent != null && (!ManagedFilePaths.within(path, parent) || parent == path ||
                lockedRoot != null && !ManagedFilePaths.within(parent, lockedRoot)))
            throw ManagedFileException(ManagedFileFailure.INVALID)
        val canChange = root["can_change_path"]?.asBooleanOrNull()
            ?: throw ManagedFileException(ManagedFileFailure.INVALID)
        val rows = root["entries"]?.asArrayOrNull()?.list
            ?: throw ManagedFileException(ManagedFileFailure.INVALID)
        val accepted = rows.take(ManagedFileLimits.ENTRIES).map { value ->
            val row = value.asObjectOrNull() ?: throw ManagedFileException(ManagedFileFailure.INVALID)
            val name = row["name"]?.asStringOrNull()?.takeIf { it.isNotBlank() && it.length <= 180 && '/' !in it && '\\' !in it }
                ?: throw ManagedFileException(ManagedFileFailure.INVALID)
            val itemPath = row["path"]?.asStringOrNull()?.takeIf(ManagedFilePaths::validPath)
                ?: throw ManagedFileException(ManagedFileFailure.INVALID)
            if (itemPath != path.trimEnd('/') + "/" + name ||
                lockedRoot != null && !ManagedFilePaths.within(itemPath, lockedRoot))
                throw ManagedFileException(ManagedFileFailure.INVALID)
            val directory = row["is_directory"]?.asBooleanOrNull()
                ?: throw ManagedFileException(ManagedFileFailure.INVALID)
            ManagedFileEntry(name, itemPath, directory, row["size"]?.asLongOrNull()?.takeIf { it >= 0 },
                row["mtime"]?.asDoubleOrNull(), row["broken_link"]?.asBooleanOrNull() == true)
        }.sortedWith(compareBy<ManagedFileEntry> { !it.directory }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
        if (accepted.map { it.path }.toSet().size != accepted.size)
            throw ManagedFileException(ManagedFileFailure.INVALID)
        return ManagedFileListing(path, parent, accepted, rows.size > ManagedFileLimits.ENTRIES, canChange, lockedRoot)
    }
}
