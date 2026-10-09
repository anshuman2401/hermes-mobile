package com.hermes.bot.data.files

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import org.commonmark.node.*
import org.commonmark.parser.Parser

object FileLimits {
    const val BYTES = 20 * 1024 * 1024
    const val TEXT_BYTES = 256 * 1024
    const val REFERENCES = 100
    const val MESSAGE_CHARS = 128 * 1024
    const val WINDOW_CHARS = 2 * 1024 * 1024
    const val PATH_CHARS = 2048
}
enum class FileFailure(val message: String) {
    UNAVAILABLE("This file is unavailable. It may have moved, or file downloads may not be supported by this host."),
    TOO_LARGE("This file exceeds the 20 MB download limit."),
    AUTH("Sign in again to open files."),
    NETWORK("Could not download this file. Check your connection and try again."),
    STALE("The conversation or connection changed. Refresh chat and reopen Files."),
    INVALID("This file reference or response is not supported.")
}
class ConversationFileException(val failure: FileFailure) : Exception(failure.name)
/** Opaque host path: never an HTTP target and never included in diagnostics. */
class ConversationFile internal constructor(internal val path: String, val name: String, val created: Boolean = false) {
    val key: String get() = path
    override fun toString() = "ConversationFile([redacted])"
}
class FileContent(val reference: ConversationFile, internal val bytes: ByteArray) {
    val size: Int get() = bytes.size
    fun discard() { bytes.fill(0) }
    override fun toString() = "FileContent([redacted])"
}

/** Explicit assistant links/MEDIA only; never scrape bare paths, code examples or user input. */
object ConversationFileReferences {
    private val parser = Parser.builder().build()
    fun reference(raw: String, created: Boolean = false): ConversationFile? {
        if (raw.length > FileLimits.PATH_CHARS) return null
        var value = raw.trim()
        if (value.startsWith("#media:")) value = decode(value.removePrefix("#media:")) ?: return null
        if (value.startsWith("file:///")) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (!uri.rawAuthority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return null
            value = uri.path ?: return null
        } else if ('%' in value) value = decode(value) ?: return null
        if (value.isBlank() || value.length > FileLimits.PATH_CHARS || value.any { it.code < 32 || it.code == 127 || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } ||
            value.any { it in "\\?#<>\"|" } || ':' in value || value.startsWith("//")) return null
        val parts = value.split('/')
        if (parts.any { it == ".." || it.startsWith('.') && it != "." }) return null
        val name = parts.lastOrNull()?.takeIf { it.isNotBlank() && '.' in it && it.length <= 180 } ?: return null
        if (name.lowercase(Locale.ROOT) in setOf("credentials.json", "secrets.json", "config.yaml", "config.yml") ||
            name.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("key", "pem", "p12", "jks", "keystore", "sqlite", "db")) return null
        value = value.removePrefix("./")
        return ConversationFile(value, name, created)
    }
    private fun decode(value: String) = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()
    fun assistant(text: String): List<ConversationFile> {
        // Reject a clipped message rather than interpreting an incomplete reference as another file.
        if (text.length > FileLimits.MESSAGE_CHARS) return emptyList()
        val found = LinkedHashMap<String, ConversationFile>()
        val root = parser.parse(text)
        var node: Node? = root
        var nodes = 0
        while (node != null && ++nodes <= 10_000 && found.size < FileLimits.REFERENCES) {
            val current = node
            val target = when (current) { is Link -> current.destination; is Image -> current.destination; else -> null }
            target?.let { reference(it) }?.let { found.putIfAbsent(it.key, it) }
            if (current is Text && current.literal.trimStart().startsWith("MEDIA:")) {
                current.literal.lineSequence().filter { it.trimStart().startsWith("MEDIA:") }.forEach { line ->
                    val raw = line.trim().removePrefix("MEDIA:").trim().removeSurrounding("\"")
                    reference(raw)?.let { if (found.size < FileLimits.REFERENCES) found.putIfAbsent(it.key, it) }
                }
            }
            if (current !is FencedCodeBlock && current !is IndentedCodeBlock && current !is Code && current !is HtmlBlock && current.firstChild != null) node = current.firstChild
            else { var cursor: Node? = current; while (cursor != null && cursor.next == null) cursor = cursor.parent; node = cursor?.next }
        }
        return found.values.toList()
    }
    /** Only explicit successful structured outputs; arbitrary terminal stdout is not provenance. */
    fun tool(result: Any?, toolName: String = ""): List<ConversationFile> {
        val value = if (result is String && result.length <= 16384) runCatching {
            com.hermes.bot.data.gateway.json.GatewayJson.parse(result).toAny()
        }.getOrNull() else result
        val map = value as? Map<*, *> ?: return emptyList()
        if (map["error"] != null || map["success"] == false || map["ok"] == false || map["is_error"] == true) return emptyList()
        val success = map["success"] == true || map["ok"] == true || map["status"] in setOf("success", "completed") ||
            (toolName == "write_file" && (map["bytes_written"] as? Number)?.toLong()?.let { it >= 0 } == true && map["files_modified"] is List<*>)
        if (!success) return emptyList()
        val found = LinkedHashMap<String, ConversationFile>()
        for (key in listOf("files_created", "files_modified", "files_written", "generated_file", "generated_path", "output_file", "output_path", "saved_to")) {
            val values = when(val v = map[key]) { is String -> listOf(v); is List<*> -> v.take(FileLimits.REFERENCES).filterIsInstance<String>(); else -> emptyList() }
            values.forEach { reference(it, key in setOf("files_created", "generated_file", "generated_path"))?.let { f -> if (found.size < 8) found.putIfAbsent(f.key, f) } }
        }
        return found.values.toList()
    }
}
