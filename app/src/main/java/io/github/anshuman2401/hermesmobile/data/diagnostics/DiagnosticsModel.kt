package io.github.anshuman2401.hermesmobile.data.diagnostics

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonNumber
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonString

enum class DiagnosticSeverity(val fileName: String) {
    DEBUG("hermes-bot-debug.log"),
    INFO("hermes-bot-info.log"),
    WARN("hermes-bot-warn.log"),
    ERROR("hermes-bot-error.log")
}

/** Source-controlled diagnostics catalog; callers cannot supply arbitrary messages. */
enum class DiagnosticKey(val wireName: String) {
    STAGE("stage"), STATUS_CODE("status_code"), EXCEPTION_CLASS("exception_class"),
    ATTEMPT("attempt"), SUCCESS("success"), API_LEVEL("api_level"),
    COORDINATES("coordinates")
}

/** Metadata has a closed value vocabulary; callers cannot submit raw payload strings. */
sealed interface DiagnosticField {
    val key: DiagnosticKey
    val value: String

    enum class Stage(override val value: String) : DiagnosticField {
        HOST("host"), PORT("port"), PREFLIGHT("preflight"), LOGIN("login"), TICKET("ticket"), GATEWAY("gateway"), PROFILE("profile");
        override val key = DiagnosticKey.STAGE
    }

    data class StatusCode(private val status: Int) : DiagnosticField {
        init { require(status in 100..599) }
        override val key = DiagnosticKey.STATUS_CODE
        override val value = status.toString()
    }

    data class Attempt(private val attempt: Int) : DiagnosticField {
        init { require(attempt >= 0) }
        override val key = DiagnosticKey.ATTEMPT
        override val value = attempt.toString()
    }

    data class ApiLevel(private val apiLevel: Int) : DiagnosticField {
        init { require(apiLevel >= 1) }
        override val key = DiagnosticKey.API_LEVEL
        override val value = apiLevel.toString()
    }

    enum class Outcome(override val value: String) : DiagnosticField {
        YES("true"), NO("false");
        override val key = DiagnosticKey.SUCCESS
    }

    class ExceptionClass private constructor(override val value: String, val trace: String) : DiagnosticField {
        override val key = DiagnosticKey.EXCEPTION_CLASS
        companion object {
            fun from(exception: Throwable): ExceptionClass = ExceptionClass(SafeExceptionTrace.typeName(exception).substringAfterLast('.'), SafeExceptionTrace.capture(exception))
        }
    }

    class FrameCoordinates private constructor(override val value: String) : DiagnosticField {
        override val key = DiagnosticKey.COORDINATES
        companion object {
            fun from(throwable: Throwable): FrameCoordinates {
                val frame = throwable.stackTrace.firstOrNull()
                val coord = if (frame != null) "${frame.className}.${frame.methodName}:${frame.lineNumber}" else "unknown"
                return FrameCoordinates(coord.take(128))
            }
            fun of(coord: String): FrameCoordinates = FrameCoordinates(coord.take(128))
        }
    }
}

enum class CatalogEvent(
    val severity: DiagnosticSeverity,
    val component: String,
    val safeMessage: String,
    val allowedMetadata: Set<DiagnosticKey> = emptySet()
) {
    APP_CRASH(DiagnosticSeverity.ERROR, "crash", "Unhandled application exception", setOf(DiagnosticKey.EXCEPTION_CLASS)),
    APP_START(DiagnosticSeverity.INFO, "startup", "Application started"),
    DIAGNOSTICS_READY(DiagnosticSeverity.INFO, "diagnostics", "Private diagnostics ready"),
    DIAGNOSTICS_DEGRADED(DiagnosticSeverity.WARN, "diagnostics", "Private diagnostics unavailable"),
    COMPOSITION_START(DiagnosticSeverity.INFO, "bootstrap", "Composition started"),
    COMPOSITION_READY(DiagnosticSeverity.INFO, "bootstrap", "Composition ready"),
    COMPOSITION_FAILED(DiagnosticSeverity.ERROR, "bootstrap", "Composition failed"),
    ACTIVITY_CREATE(DiagnosticSeverity.INFO, "activity", "Activity created"),
    APP_FOREGROUND(DiagnosticSeverity.INFO, "activity", "Application foregrounded"),
    APP_BACKGROUND(DiagnosticSeverity.INFO, "activity", "Application backgrounded"),

    CONNECT_SUBMIT(DiagnosticSeverity.INFO, "connect", "Connection submitted"),
    ENDPOINT_INVALID(DiagnosticSeverity.WARN, "connect", "Connection input invalid", setOf(DiagnosticKey.STAGE)),
    PREFLIGHT_START(DiagnosticSeverity.INFO, "connect", "Connection preflight started"),
    PREFLIGHT_OK(DiagnosticSeverity.INFO, "connect", "Connection preflight succeeded"),
    PREFLIGHT_FAILED(DiagnosticSeverity.WARN, "connect", "Connection preflight failed", setOf(DiagnosticKey.EXCEPTION_CLASS)),
    AUTH_REQUIRED(DiagnosticSeverity.INFO, "auth", "Authentication required"),
    LOGIN_START(DiagnosticSeverity.INFO, "auth", "Login started"),
    LOGIN_OK(DiagnosticSeverity.INFO, "auth", "Login succeeded"),
    LOGIN_FAILED(DiagnosticSeverity.WARN, "auth", "Login failed", setOf(DiagnosticKey.EXCEPTION_CLASS)),
    TICKET_START(DiagnosticSeverity.INFO, "auth", "Gateway ticket requested"),
    TICKET_OK(DiagnosticSeverity.INFO, "auth", "Gateway ticket received"),
    TICKET_FAILED(DiagnosticSeverity.WARN, "auth", "Gateway ticket failed", setOf(DiagnosticKey.EXCEPTION_CLASS)),
    GATEWAY_CONNECT_START(DiagnosticSeverity.INFO, "gateway", "Gateway connection started"),
    GATEWAY_READY(DiagnosticSeverity.INFO, "gateway", "Gateway ready"),
    GATEWAY_CAPABILITIES_OK(DiagnosticSeverity.INFO, "gateway", "Gateway capabilities accepted"),
    GATEWAY_CONNECT_FAILED(DiagnosticSeverity.WARN, "gateway", "Gateway connection failed", setOf(DiagnosticKey.EXCEPTION_CLASS)),
    GATEWAY_DISCONNECTED(DiagnosticSeverity.WARN, "gateway", "Gateway disconnected"),
    PROFILE_SAVE_OK(DiagnosticSeverity.INFO, "persistence", "Connection profile saved"),
    PROFILE_SAVE_FAILED(DiagnosticSeverity.WARN, "persistence", "Connection profile was not saved", setOf(DiagnosticKey.EXCEPTION_CLASS))
}

class DiagnosticMetadata private constructor(internal val values: Map<DiagnosticKey, DiagnosticField>) {
    companion object {
        val EMPTY = DiagnosticMetadata(emptyMap())
        fun forEvent(event: CatalogEvent, vararg fields: DiagnosticField): DiagnosticMetadata {
            require(fields.all { it.key in event.allowedMetadata }) { "Metadata key is not allowed for ${event.name}" }
            return DiagnosticMetadata(fields.associateBy { it.key })
        }
    }
}

/** Runs before writes and whenever private file bytes are rendered. */
object DiagnosticRedactor {
    private val sensitivePair = Regex(
        "(?i)([\\\"']?(?:password|username|token|authorization|cookie|set-cookie|ticket|session(?:[_-]?id)?|host|ip|body|content)[\\\"']?\\s*[:=]\\s*)([\\\"]?)([^,}\\s\\\"]+|[^,}]+)"
    )
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+\\-=/]+")
    private val inlineSecret = Regex("(?i)\\b(?:password|token|ticket|cookie|session)[-_A-Za-z0-9.=+/]+")
    private val ipv4 = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
    private val hostname = Regex("\\b(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}\\b")
    private val controls = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")

    fun sanitize(value: String): String = value
        .replace(controls, "")
        .replace(bearer, "[redacted]")
        .replace(inlineSecret, "[redacted]")
        .replace(sensitivePair) { match -> match.groupValues[1] + "[redacted]" }
        .replace(ipv4, "[redacted]")
        .replace(hostname, "[redacted]")
        .take(1024)

    fun sanitizeLine(line: String): String = sanitize(line)

    /** Viewer exposes fixed diagnostic fields rather than rendering mutable JSON directly. */
    fun structuredDisplay(line: String): String {
        return DiagnosticJsonl.parseComplete(line)?.structuredDisplay() ?: "[malformed record]"
    }

}

/** Parses hostile JSONL before the private viewer consumes it. */
object DiagnosticJsonl {
    data class ReadResult(val records: List<String>, val rejectedCount: Int)

    data class Record(
        val timestamp: String,
        val severity: String,
        val code: String,
        val component: String,
        val message: String,
        val trace: String? = null,
        val details: Map<String, String> = emptyMap()
    ) {
        fun canonicalJsonLine(): String = GatewayJson.serialize(
            JsonObject(
                "v" to JsonNumber(1),
                "ts" to JsonString(timestamp),
                "severity" to JsonString(severity),
                "code" to JsonString(code),
                "component" to JsonString(component),
                "message" to JsonString(message),
                "trace" to JsonString(trace ?: ""),
                "meta" to JsonObject(details.mapValues { JsonString(it.value) })
            )
        )

        fun structuredDisplay(): String = "$timestamp  $code/$component  $message" +
            details.entries.joinToString("") { "\n  ${it.key}: ${it.value}" } +
            (trace?.let { "\n$it" } ?: "")
    }

    fun parseComplete(line: String): Record? {
        val objectValue = GatewayJson.parseOrNull(line)?.asObjectOrNull() ?: return null
        val version = objectValue["v"]?.asIntOrNull() ?: return null
        if (version != 1) return null
        val severity = objectValue["severity"]?.asStringOrNull() ?: return null
        if (DiagnosticSeverity.entries.none { it.name == severity }) return null
        val timestamp = objectValue["ts"]?.asStringOrNull() ?: return null
        val code = objectValue["code"]?.asStringOrNull() ?: return null
        val component = objectValue["component"]?.asStringOrNull() ?: return null
        val message = objectValue["message"]?.asStringOrNull() ?: return null
        return Record(
            DiagnosticRedactor.sanitize(timestamp),
            severity,
            DiagnosticRedactor.sanitize(code),
            DiagnosticRedactor.sanitize(component),
            DiagnosticRedactor.sanitize(message),
            objectValue["trace"]?.asStringOrNull()?.takeIf { it.isNotBlank() }?.let(SafeExceptionTrace::validate),
            safeMetadata(code, objectValue["meta"]?.asObjectOrNull())
        )
    }

    private fun safeMetadata(code: String, meta: JsonObject?): Map<String, String> {
        val event = CatalogEvent.entries.firstOrNull { it.name.lowercase() == code } ?: return emptyMap()
        return event.allowedMetadata.mapNotNull { key ->
            val value = meta?.get(key.wireName)?.asStringOrNull() ?: return@mapNotNull null
            val accepted = when (key) {
                DiagnosticKey.STAGE -> DiagnosticField.Stage.entries.any { it.value == value }
                DiagnosticKey.STATUS_CODE -> value.toIntOrNull()?.let { it in 100..599 } == true
                DiagnosticKey.ATTEMPT, DiagnosticKey.API_LEVEL -> value.length <= 9 && value.all(Char::isDigit)
                DiagnosticKey.SUCCESS -> value == "true" || value == "false"
                // Types and coordinates are displayed exclusively through the validated trace.
                DiagnosticKey.EXCEPTION_CLASS, DiagnosticKey.COORDINATES -> false
            }
            if (accepted) key.wireName to value else null
        }.toMap()
    }

    fun readComplete(input: InputStream, maxRecordBytes: Int): ReadResult {
        val records = mutableListOf<String>()
        val rejected = streamComplete(input, maxRecordBytes) { records += it.canonicalJsonLine() }
        return ReadResult(records, rejected)
    }

    /** Returns the count of malformed, partial, or oversized input records. */
    fun streamComplete(input: InputStream, maxRecordBytes: Int, consume: (Record) -> Unit): Int {
        val buffer = ByteArrayOutputStream(maxRecordBytes.coerceAtMost(1024))
        var oversized = false
        var rejected = 0
        fun finishLine() {
            if (oversized) {
                rejected++
            } else {
                val record = parseComplete(buffer.toString(Charsets.UTF_8.name()))
                if (record == null) rejected++ else consume(record)
            }
            buffer.reset()
            oversized = false
        }
        while (true) {
            val next = input.read()
            if (next == -1) break
            if (next == '\n'.code) {
                finishLine()
            } else if (!oversized) {
                if (buffer.size() >= maxRecordBytes) oversized = true else buffer.write(next)
            }
        }
        if (oversized || buffer.size() > 0) rejected++
        return rejected
    }
}

object DiagnosticRecordEncoder {
    const val MAX_RECORD_BYTES = 16 * 1024

    fun encode(event: CatalogEvent, metadata: DiagnosticMetadata = DiagnosticMetadata.EMPTY): String {
        val meta = metadata.values.entries.joinToString(",") { (key, field) ->
            "\"${key.wireName}\":\"${escape(DiagnosticRedactor.sanitize(field.value).take(256))}\""
        }
        val raw = buildString {
            append('{')
            append("\"v\":1,\"ts\":\"")
            append(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            append("\",\"severity\":\"")
            append(event.severity.name)
            append("\",\"code\":\"")
            append(event.name.lowercase())
            append("\",\"component\":\"")
            append(event.component)
            append("\",\"message\":\"")
            append(escape(event.safeMessage))
            append('"')
            if (meta.isNotEmpty()) append(",\"meta\":{$meta}")
            val trace = (metadata.values[DiagnosticKey.EXCEPTION_CLASS] as? DiagnosticField.ExceptionClass)?.trace
            if (trace != null) append(",\"trace\":\"${escape(trace)}\"")
            append("}\n")
        }
        return raw.toByteArray(Charsets.UTF_8).let { bytes ->
            if (bytes.size <= MAX_RECORD_BYTES) raw else String(bytes.copyOf(MAX_RECORD_BYTES - 2), Charsets.UTF_8) + "}\n"
        }
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

interface Diagnostics {
    fun emit(event: CatalogEvent, metadata: DiagnosticMetadata = DiagnosticMetadata.EMPTY)
}

/** Production lifecycle emitter kept Android-free so startup and activity transitions are traceable on JVM. */
class DiagnosticsLifecycle(private val diagnostics: Diagnostics) {
    fun applicationStarted(prepared: Boolean) {
        diagnostics.emit(if (prepared) CatalogEvent.DIAGNOSTICS_READY else CatalogEvent.DIAGNOSTICS_DEGRADED)
        diagnostics.emit(CatalogEvent.APP_START)
        diagnostics.emit(CatalogEvent.COMPOSITION_START)
    }

    fun compositionReady() = diagnostics.emit(CatalogEvent.COMPOSITION_READY)
    fun compositionFailed() = diagnostics.emit(CatalogEvent.COMPOSITION_FAILED)
    fun activityCreated() = diagnostics.emit(CatalogEvent.ACTIVITY_CREATE)
    fun foregrounded() = diagnostics.emit(CatalogEvent.APP_FOREGROUND)
    fun backgrounded() = diagnostics.emit(CatalogEvent.APP_BACKGROUND)
}

object NoOpDiagnostics : Diagnostics {
    override fun emit(event: CatalogEvent, metadata: DiagnosticMetadata) = Unit
}
