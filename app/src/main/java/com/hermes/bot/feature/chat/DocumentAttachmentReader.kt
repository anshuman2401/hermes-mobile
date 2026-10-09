package com.hermes.bot.feature.chat

import android.content.ContentResolver
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream

internal const val MAX_DOCUMENT_BYTES = 50 * 1024 * 1024

data class SelectedDocument(val bytes: ByteArray, val displayName: String, val mimeType: String)

/** Read only the selected document grant, stopping after the first over-cap byte. */
fun interface DocumentAttachmentReader {
    suspend fun read(uri: String): SelectedDocument
}

class ContentResolverDocumentAttachmentReader(private val resolverProvider: () -> ContentResolver) : DocumentAttachmentReader {
    override suspend fun read(uri: String): SelectedDocument {
        val resolver = resolverProvider()
        val selectedUri = uri.toUri()
        val reportedName = resolver.query(selectedUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val name = reportedName?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.trim()?.take(120)?.takeIf { it.isNotBlank() } ?: "Document"
        val mimeType = resolver.getType(selectedUri)?.trim()?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")) }
            ?: "application/octet-stream"
        val input = resolver.openInputStream(selectedUri) ?: throw IllegalStateException("Selected document is unavailable")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (output.size() <= MAX_DOCUMENT_BYTES) {
                val remaining = MAX_DOCUMENT_BYTES + 1 - output.size()
                val count = stream.read(chunk, 0, minOf(chunk.size, remaining))
                if (count < 0) break
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        return SelectedDocument(bytes, name, mimeType)
    }
}
