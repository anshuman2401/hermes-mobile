package io.github.anshuman2401.hermesmobile.data.insights

/** Fixed safe failures; raw host errors and response bodies never enter diagnostics. */
enum class InsightsFailure(val message: String) {
    UNAVAILABLE("Host information unavailable. Check your connection and retry."),
    AUTH("Sign in again to view host information."),
    UNSUPPORTED("This host does not support this page."),
    MALFORMED("The host returned an unsupported response."),
    STALE("The connection or selected profile changed. Refresh to reload."),
    ORIGIN("Request rejected by the exact-origin policy.")
}
sealed interface InsightsRead<out T> {
    class Ready<T>(val value: T) : InsightsRead<T> { override fun toString() = "InsightsRead.Ready([private])" }
    data class Failed(val reason: InsightsFailure) : InsightsRead<Nothing>
}
data class Metric(val label: String, val value: Double?, val unit: String = "")
data class HostStatusOverview(val version: String?, val gateway: String?, val overall: String?, val sessions: Double?, val agents: Double?, val components: List<Pair<String, String>>)
data class HostSystemOverview(val metrics: List<Metric>)
data class HostHealth(val status: InsightsRead<HostStatusOverview>, val system: InsightsRead<HostSystemOverview>)
data class DailyUsage(val day: String, val input: Double?, val output: Double?)
data class UsageRow(val name: String, val count: Double?, val cost: Double? = null)
data class HostUsage(val days: Int, val totals: List<Metric>, val daily: List<DailyUsage>, val models: List<UsageRow>, val tasks: List<UsageRow>, val skills: List<UsageRow>, val tools: List<UsageRow>)
enum class HostLogFile(val wire: String, val label: String) { AGENT("agent", "Agent"), ERRORS("errors", "Errors"), GATEWAY("gateway", "Gateway"), DESKTOP("desktop", "Desktop") }
enum class HostLogLevel(val wire: String, val label: String) { ALL("ALL", "All"), INFO("INFO", "Info+"), WARNING("WARNING", "Warning+"), ERROR("ERROR", "Error+") }
class HostLogTail(val lines: List<String>) { override fun toString() = "HostLogTail([private])" }
data class VisibleLogLine(val index: Int, val text: String) { override fun toString() = "VisibleLogLine([private])" }
object HostLogProjection {
    fun visible(lines: List<String>, query: String): List<VisibleLogLine> = lines.mapIndexed { index, text -> VisibleLogLine(index + 1, text) }
        .filter { query.isBlank() || it.text.contains(query.trim(), ignoreCase = true) }
    fun copy(lines: List<VisibleLogLine>): String = buildString {
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                val previous = lines[index - 1].text
                if (!previous.endsWith('\n') && !previous.endsWith('\r')) append('\n')
            }
            append(line.text)
        }
    }
}
