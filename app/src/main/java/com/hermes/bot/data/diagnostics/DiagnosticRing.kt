package com.hermes.bot.data.diagnostics

import com.hermes.bot.data.gateway.GatewayRedactor
import com.hermes.bot.data.origin.CanonicalOrigin
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Static metadata for the diagnostic report header.
 * Pure Kotlin data structure with zero Android framework dependencies.
 */
data class DiagnosticEnvironment(
    val appVersion: String = "0.1.0-debug",
    val buildTimestamp: String = "2026-09-21",
    val androidApiLevel: Int = 36,
    val deviceModel: String = "JVM",
    val hostVersion: String? = null,
    val connectionOrigin: String? = null
)

/**
 * Sanitizer for diagnostic reporting.
 * Reuses existing GatewayRedactor and enforces zero-credential / zero-IP invariants.
 */
object DiagnosticSanitizer {
    private val ipv4 = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
    private val hostname = Regex(
        "(?i)\\b(?:[a-z0-9-]+\\.)+(?:com|org|net|edu|gov|io|local|lan|internal|corp|domain)\\b"
    )
    private val sensitivePair = Regex(
        "(?i)([\\\"']?(?:password|username|token|authorization|cookie|set-cookie|ticket|hermes_session(?:[_-]?[a-z0-9]+)?|session(?:[_-]?id)?|host|ip|body|content)[\\\"']?\\s*[:=]\\s*)([\\\"]?)([^,}\\s\\\"]+|[^,}]+)"
    )
    private val inlineSecret = Regex("(?i)\\b(?:token|ticket|cookie|hermes_session)[-_A-Za-z0-9.=+/]+")
    private val bearer = Regex("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+\\-=/]+")
    private val controls = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
    private val commonKeyPrefix = Regex(
        "\\b(sk-[a-zA-Z0-9_-]{16,}|ghp_[a-zA-Z0-9]{20,}|gho_[a-zA-Z0-9]{20,}|xoxb-[a-zA-Z0-9]{10,})\\b"
    )

    fun sanitize(value: String): String {
        var text = value
            .replace(controls, "")
            .replace(bearer) { "${it.groupValues[1]}[redacted]" }
            .replace(commonKeyPrefix, "[redacted]")
            .replace(inlineSecret, "[redacted]")
            .replace(sensitivePair) { match -> match.groupValues[1] + "[redacted]" }
            .replace(ipv4, "[redacted]")
            .replace(hostname, "[redacted]")
        text = GatewayRedactor.redactString(text)
        return text
    }
}

/**
 * Thread-safe capped in-memory ring buffer for diagnostic events.
 * Evicts oldest records when capacity is reached.
 * Produces paste-safe sanitized structured reports.
 */
class DiagnosticRing(
    val capacity: Int = DEFAULT_CAPACITY,
    initialEnvironment: DiagnosticEnvironment = DiagnosticEnvironment()
) {
    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticEvent>(capacity)

    @Volatile
    var environment: DiagnosticEnvironment = safeEnvironment(initialEnvironment)
        private set

    private fun safeEnvironment(value: DiagnosticEnvironment) = value.copy(
        hostVersion = value.hostVersion?.let { "[redacted]" },
        connectionOrigin = value.connectionOrigin?.let(::sanitizeOriginForReport)
    )

    fun record(event: DiagnosticEvent) {
        synchronized(lock) {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(event.withPrivateValuesRemoved())
        }
    }

    /** Do not retain owner- or peer-supplied strings even in the process-only ring. */
    private fun DiagnosticEvent.withPrivateValuesRemoved(): DiagnosticEvent = when (this) {
        is DiagnosticEvent.Auth.LoggingIn -> copy(provider = "[redacted]")
        is DiagnosticEvent.Auth.Authenticated -> copy(provider = "[redacted]")
        is DiagnosticEvent.Auth.Rejected -> copy(reason = "[redacted]")
        is DiagnosticEvent.Auth.LoggedOut, is DiagnosticEvent.Auth.Expired -> this
        is DiagnosticEvent.Http.RequestCompleted -> copy(
            method = method.takeIf { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE") } ?: "[redacted]",
            urlPathShape = urlPathShape.substringBefore('?').takeIf {
                it in setOf("/api/status", "/auth/password-login", "/api/auth/ws-ticket", "/api/profiles/sessions")
            } ?: "[redacted]",
            error = error?.let { "[redacted]" }
        )
        is DiagnosticEvent.Switch.ConfirmTapped -> copy(targetProfile = "[redacted]")
        is DiagnosticEvent.Switch.SelectionResult -> copy(targetProfile = "[redacted]", reason = reason?.let { "[redacted]" })
        is DiagnosticEvent.Switch.PreferencePersisted -> copy(profile = "[redacted]")
        is DiagnosticEvent.Switch.ScopedFetchStarted -> copy(profile = "[redacted]")
        is DiagnosticEvent.Switch.ScopedFetchOutcome -> copy(profile = "[redacted]", error = error?.let { "[redacted]" })
        is DiagnosticEvent.Switch.SheetOpened, is DiagnosticEvent.Switch.PreferenceCleared -> this
        is DiagnosticEvent.WebSocket.Connecting -> copy(url = null)
        is DiagnosticEvent.WebSocket.Connected -> copy(url = null)
        is DiagnosticEvent.WebSocket.Disconnected -> copy(reason = reason?.let { "[redacted]" })
        is DiagnosticEvent.WebSocket.ResumeOutcome -> copy(error = error?.let { "[redacted]" })
        is DiagnosticEvent.WebSocket.ReconnectScheduled -> this
        is DiagnosticEvent.SessionList.Outcome -> copy(profile = profile?.let { "[redacted]" }, error = error?.let { "[redacted]" })
        is DiagnosticEvent.App.Lifecycle -> copy(stage = "[redacted]")
        is DiagnosticEvent.App.ForegroundEntered, is DiagnosticEvent.App.BackgroundEntered -> this
    }

    fun snapshot(): List<DiagnosticEvent> {
        synchronized(lock) {
            return buffer.toList()
        }
    }

    fun updateHostVersion(version: String) {
        environment = environment.copy(hostVersion = "[redacted]")
    }

    fun updateConnectionOrigin(origin: String?) {
        environment = environment.copy(connectionOrigin = origin?.let(::sanitizeOriginForReport))
    }

    fun updateHostInfo(version: String? = null, origin: String? = null) {
        if (version != null) updateHostVersion(version)
        if (origin != null) updateConnectionOrigin(origin)
    }

    fun updateEnvironment(env: DiagnosticEnvironment) {
        environment = safeEnvironment(env)
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }

    fun snapshotReport(maxEvents: Int = capacity): String {
        val events = snapshot().takeLast(maxEvents)
        val env = environment
        val report = buildString {
            appendLine("=== Hermes Mobile Diagnostics Report ===")
            appendLine("Generated: ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}")
            appendLine()
            appendLine("Environment:")
            appendLine("- App Version: ${env.appVersion} (build ${env.buildTimestamp})")
            appendLine("- Android API: ${env.androidApiLevel}")
            appendLine("- Device Model: ${env.deviceModel}")
            appendLine("- Host App Version: ${env.hostVersion ?: "unknown"}")
            appendLine("- Connection Origin: ${sanitizeOriginForReport(env.connectionOrigin)}")
            appendLine()
            appendLine("Events (${events.size}):")
            if (events.isEmpty()) {
                appendLine("None recorded.")
            } else {
                for (event in events) {
                    val ts = DateTimeFormatter.ISO_INSTANT.format(event.timestamp)
                    appendLine("[$ts] ${event.formatSummary()}")
                }
            }
        }
        return DiagnosticSanitizer.sanitize(report.trimEnd())
    }

    companion object {
        const val DEFAULT_CAPACITY = 500

        fun sanitizeOriginForReport(rawOrigin: String?): String {
            if (rawOrigin.isNullOrBlank()) return "none"
            if (rawOrigin.matches(Regex("(?:http|https)://\\[redacted]:[0-9]{1,5}"))) return rawOrigin
            return try {
                val canonical = CanonicalOrigin.parse(rawOrigin).getOrNull()
                if (canonical != null) {
                    "${canonical.scheme.value}://[redacted]:${canonical.port}"
                } else {
                    "[redacted]"
                }
            } catch (_: Exception) {
                "[redacted]"
            }
        }

        fun sanitizePathShape(rawUrl: String): String {
            return try {
                val uri = URI(rawUrl)
                safeRoute(uri.rawPath)
            } catch (_: Exception) {
                "[redacted]"
            }
        }

        private fun safeRoute(path: String?): String = path?.takeIf {
            it in setOf("/api/status", "/auth/password-login", "/api/auth/ws-ticket", "/api/profiles/sessions")
        } ?: "[redacted]"
    }
}
