package io.github.anshuman2401.hermesmobile.data.diagnostics

/** Only code coordinates cross this boundary, never messages or source paths. */
object SafeExceptionTrace {
    private val codeName = Regex("(?:io\\.github\\.anshuman2401\\.hermesmobile|java|javax|android|androidx|kotlin|kotlinx|okhttp3|okio)\\.[A-Za-z0-9_.$]+")
    private val methodName = Regex("[A-Za-z0-9_$<>-]{1,128}")
    private val frameLine = Regex("    at ([A-Za-z0-9_.$]+)#([A-Za-z0-9_$<>-]{1,128}):(-?[0-9]{1,9})")

    fun typeName(error: Throwable): String = safeType(error.javaClass.name)
    private fun safeType(name: String): String =
        name.takeIf { it.length <= 256 && codeName.matches(it) } ?: "[exception type omitted]"

    fun capture(error: Throwable): String = buildString {
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = error
        repeat(4) { depth ->
            val item = current ?: return@buildString
            if (seen.put(item, true) != null) { appendLine("[cause cycle omitted]"); return@buildString }
            appendLine((if (depth > 0) "Caused by: " else "") + typeName(item))
            item.stackTrace.take(24).forEach { frame ->
                if (codeName.matches(frame.className) && frame.className.length <= 256 && methodName.matches(frame.methodName)) {
                    appendLine("    at ${frame.className}#${frame.methodName}:${frame.lineNumber}")
                } else appendLine("    [frame omitted]")
            }
            if (item.stackTrace.size > 24) appendLine("    [additional frames omitted]")
            current = item.cause
        }
        if (current != null) appendLine("[additional causes omitted]")
    }.trimEnd().let { trace ->
        val retained = trace.lines().runningFold(0) { size, line -> size + line.length + 1 }
        trace.lines().take(retained.drop(1).takeWhile { it <= 11_900 }.size).joinToString("\n")
    }

    /** Reject mutable file bytes unless every line belongs to the closed trace grammar. */
    fun validate(value: String): String? {
        if (value.length > 12_000 || value.lines().size > 110) return null
        return value.takeIf { text -> text.lines().all { line ->
            val type = line.removePrefix("Caused by: ")
            val frame = frameLine.matchEntire(line)
            codeName.matches(type) && type.length <= 256 ||
                line in setOf("[exception type omitted]", "Caused by: [exception type omitted]", "[cause cycle omitted]", "[additional causes omitted]", "    [frame omitted]", "    [additional frames omitted]") ||
                frame != null && codeName.matches(frame.groupValues[1]) && frame.groupValues[1].length <= 256
        } }
    }
}

class DiagnosticCrashHandler(
    private val capture: (Throwable) -> Unit,
    private val previous: Thread.UncaughtExceptionHandler
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { capture(error) } catch (_: Throwable) { /* Never prevent Android crash handling. */ }
        finally { previous.uncaughtException(thread, error) }
    }
}
