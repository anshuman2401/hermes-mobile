package com.hermes.bot.data.attachments

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/** Only this dedicated cache directory is shared with the external camera application. */
class AndroidCameraPhotoStore(private val context: Context) : CameraPhotoStore {
    private val directory by lazy { File(context.cacheDir, "camera-attachments") }
    private val authority by lazy { "${context.packageName}.cameraphotos" }
    private val active = mutableMapOf<String, File>()

    override suspend fun create(): CameraPhoto = createPhoto()

    @Synchronized
    private fun createPhoto(): CameraPhoto {
        if (Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) == null) {
            throw CameraUnavailableException()
        }
        check(directory.isDirectory || directory.mkdirs())
        val file = File.createTempFile("capture-", ".jpg", directory)
        try {
            val uri = FileProvider.getUriForFile(context, authority, file, "Camera photo.jpg").toString()
            active[uri] = file
            return CameraPhoto(uri)
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    override suspend fun preview(photo: CameraPhoto): ByteArray {
        val file = synchronized(this) { active[photo.uri] } ?: error("Capture unavailable")
        if (file.length() > MAX_IMAGE_ATTACHMENT_BYTES) throw CameraPhotoTooLargeException()
        check(file.length() > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight).toLong() > 1280L * sample) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Capture unavailable")
        var oriented: Bitmap? = null
        try {
            val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val display = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            oriented = display
            return ByteArrayOutputStream().use { output ->
                check(display.compress(Bitmap.CompressFormat.JPEG, 85, output))
                output.toByteArray()
            }
        } finally {
            if (oriented !== bitmap) oriented?.recycle()
            bitmap.recycle()
        }
    }

    @Synchronized
    override fun discard(photo: CameraPhoto) {
        val file = active.remove(photo.uri) ?: return
        revoke(photo.uri)
        file.delete()
    }

    /** Process death abandons every memory-only capture; startup removes its cache remnants. */
    @Synchronized
    fun clearAbandoned() {
        directory.listFiles()?.filter { it.isFile && it !in active.values }?.forEach { file ->
            runCatching { revoke(FileProvider.getUriForFile(context, authority, file, "Camera photo.jpg").toString()) }
            file.delete()
        }
    }

    private fun revoke(uri: String) {
        runCatching { context.revokeUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }
}
