package com.hermes.bot.feature.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

internal const val MAX_PHOTO_BYTES = com.hermes.bot.data.attachments.MAX_IMAGE_ATTACHMENT_BYTES

data class SelectedPhoto(val bytes: ByteArray, val displayName: String, val ext: String?)

/** Read a picker grant without retaining its URI or bytes after staging. */
fun interface PhotoAttachmentReader {
    suspend fun read(uri: String): SelectedPhoto
}

class ContentResolverPhotoAttachmentReader(private val resolverProvider: () -> ContentResolver) : PhotoAttachmentReader {
    override suspend fun read(uri: String): SelectedPhoto {
        val resolver = resolverProvider()
        val selectedUri = Uri.parse(uri)
        val reportedName = resolver.query(selectedUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val name = reportedName?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.trim()?.take(120)?.takeIf { it.isNotBlank() } ?: "Photo"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") }
            ?: when (resolver.getType(selectedUri)?.lowercase()) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/bmp" -> "bmp"
                else -> null
            }
        val input = resolver.openInputStream(selectedUri) ?: throw IllegalStateException("Selected photo is unavailable")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (output.size() <= MAX_PHOTO_BYTES) {
                val remaining = MAX_PHOTO_BYTES + 1 - output.size()
                val count = stream.read(chunk, 0, minOf(chunk.size, remaining))
                if (count < 0) break
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        return SelectedPhoto(bytes, name, ext)
    }
}
