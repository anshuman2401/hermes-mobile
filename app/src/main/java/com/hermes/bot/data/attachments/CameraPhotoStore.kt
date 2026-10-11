package com.hermes.bot.data.attachments

internal const val MAX_IMAGE_ATTACHMENT_BYTES = 25 * 1024 * 1024

/** A temporary camera grant. Never saved in a draft, snapshot, or saved-state handle. */
class CameraPhoto(val uri: String) {
    override fun toString(): String = "CameraPhoto(<temporary>)"
}

interface CameraPhotoStore {
    suspend fun create(): CameraPhoto
    /** Validates the capture and returns a bounded, correctly oriented JPEG for review. */
    suspend fun preview(photo: CameraPhoto): ByteArray
    /** Idempotently revokes the grant and deletes the temporary original. */
    fun discard(photo: CameraPhoto)
}

class CameraUnavailableException : IllegalStateException()
class CameraPhotoTooLargeException : IllegalStateException()
