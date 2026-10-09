package io.github.anshuman2401.hermesmobile.feature.chat.files

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import io.github.anshuman2401.hermesmobile.data.files.*
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import java.util.UUID

class FilePreview(val bitmap: Bitmap? = null, val text: String? = null, val pages: Int = 1, val notice: String? = null)
object FilePreviewPolicy {
    fun kind(name: String): String = when(name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "pdf" -> "pdf"
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> "image"
        "txt", "md", "csv", "tsv", "json", "xml", "yaml", "yml", "kt", "java", "py", "js", "ts", "css", "html", "sh", "sql", "log" -> "text"
        else -> "external"
    }
    fun mime(name: String): String = when(name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "pdf" -> "application/pdf"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"; "csv" -> "text/csv"
        else -> if (kind(name) == "text") "text/plain" else "application/octet-stream"
    }
    fun imageSample(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0 || width.toLong() * height > 32_000_000) return null
        var sample = 1
        while (width.toLong() / sample * (height / sample) > 2_000_000) sample *= 2
        return sample
    }
    fun text(bytes: ByteArray): FilePreview {
        val length = minOf(bytes.size, FileLimits.TEXT_BYTES)
        if (bytes.take(minOf(length, 4096)).any { it == 0.toByte() }) return FilePreview(notice = "Binary content cannot be previewed as text.")
        val raw = String(bytes, 0, length, Charsets.UTF_8)
        val lines = raw.lineSequence().take(1000).map { it.take(4096) }.toList()
        val text = lines.joinToString("\n")
        return FilePreview(text = text, notice = if (bytes.size > length || text != raw)
            "Text preview limited to 256 KB, 1,000 lines and 4,096 characters per line. Save to view the full file." else null)
    }
}
object AndroidFileActions {
    private const val EXPORT_DIR = "conversation-exports"
    private const val TTL = 10 * 60 * 1000L
    suspend fun preview(context: Context, content: FileContent, page: Int): FilePreview =
        preview(context, content.bytes, content.reference.name, page)

    suspend fun preview(context: Context, bytes: ByteArray, name: String, page: Int): FilePreview = withContext(Dispatchers.IO) {
        try {
            when(FilePreviewPolicy.kind(name)) {
                "text" -> FilePreviewPolicy.text(bytes)
                "image" -> {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    val sample = FilePreviewPolicy.imageSample(options.outWidth, options.outHeight)
                        ?: return@withContext FilePreview(notice = "This image is too large or cannot be previewed.")
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                    FilePreview(bitmap = bitmap, notice = if (bitmap == null) "Image preview unavailable." else null)
                }
                "pdf" -> {
                    val temporary = File.createTempFile("preview-", ".pdf", context.cacheDir)
                    try {
                        temporary.writeBytes(bytes)
                        ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            PdfRenderer(fd).use { renderer ->
                                if (renderer.pageCount == 0) return@withContext FilePreview(notice = "This PDF has no pages.")
                                renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { pdf ->
                                    if (pdf.width <= 0 || pdf.height <= 0) return@withContext FilePreview(notice = "PDF page unavailable.")
                                    val scale = minOf(1200.0 / pdf.width, 1600.0 / pdf.height, 2.0)
                                    val bitmap = Bitmap.createBitmap((pdf.width * scale).toInt().coerceAtLeast(1),
                                        (pdf.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(Color.WHITE)
                                    try { pdf.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                    catch (failure: Exception) { bitmap.recycle(); throw failure }
                                    FilePreview(bitmap = bitmap, pages = renderer.pageCount)
                                }
                            }
                        }
                    } finally { temporary.delete() }
                }
                else -> FilePreview(notice = "Preview is not available for this format. Save or open it in another app.")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FilePreview(notice = "Could not preview this file. You can still save or share it.") }
    }
    suspend fun save(context: Context, uri: Uri, content: FileContent) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Export unavailable")
        output.use { it.write(content.bytes) }
    }
    /** Private copies are only created after an explicit Share/Open action, never for discovery. */
    suspend fun share(context: Context, content: FileContent, open: Boolean) {
        val app = context.applicationContext
        val file = withContext(Dispatchers.IO) {
            val directory = File(app.cacheDir, EXPORT_DIR).apply { mkdirs() }
            synchronized(AndroidFileActions) {
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() >= TTL }?.forEach { it.deleteRecursively() }
                // Keep at most four bounded exports at once, including the new one.
                directory.listFiles()?.sortedBy { it.lastModified() }?.dropLast(3)?.forEach { it.deleteRecursively() }
                val folder = File(directory, UUID.randomUUID().toString()).apply { mkdirs() }
                File(folder, content.reference.name).apply { writeBytes(content.bytes) }
            }
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.conversationfiles", file)
        val intent = if (open) Intent(Intent.ACTION_VIEW).setDataAndType(uri, FilePreviewPolicy.mime(content.reference.name))
            else Intent(Intent.ACTION_SEND).setType(FilePreviewPolicy.mime(content.reference.name)).putExtra(Intent.EXTRA_STREAM, uri)
        intent.clipData = android.content.ClipData.newRawUri("Conversation file", uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Handler(Looper.getMainLooper()).postDelayed({
            app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            file.delete(); file.parentFile?.delete()
        }, TTL)
        withContext(Dispatchers.Main) { context.startActivity(Intent.createChooser(intent, if (open) "Open file" else "Share file")) }
    }
    /** Called on process startup: no preview/export is restored across a process restart. */
    fun clearOldExports(context: Context) {
        File(context.cacheDir, EXPORT_DIR).deleteRecursively()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("preview-") && it.extension == "pdf" }?.forEach { it.delete() }
    }
}
