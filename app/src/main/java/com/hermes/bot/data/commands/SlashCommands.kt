package com.hermes.bot.data.commands

/** Presentation projections. Command payloads never enter diagnostics or history caches. */
class SlashSuggestion(val text: String, val description: String, val group: String = "Commands") {
    override fun toString() = "SlashSuggestion([redacted])"
}
class SlashCatalog(
    val suggestions: List<SlashSuggestion>,
    val aliases: Map<String, String> = emptyMap(),
    val unavailable: Set<String> = emptySet()
) { override fun toString() = "SlashCatalog([redacted])" }
class SlashCompletion(val items: List<SlashSuggestion>, val replaceFrom: Int = 1) {
    override fun toString() = "SlashCompletion([redacted])"
}
class SlashInvocation(val name: String, val argument: String) {
    val text: String get() = "/$name" + if (argument.isEmpty()) "" else " $argument"
    override fun toString() = "SlashInvocation([redacted])"
}
object SlashCommandPolicy {
    const val MAX_ROWS = 256
    const val MAX_QUERY = 2048
    const val MAX_PAYLOAD = 128 * 1024
    private val name = Regex("[a-zA-Z][a-zA-Z0-9_-]*")
    fun query(draft: String): String? {
        if (!draft.startsWith("/") || draft.length > MAX_QUERY) return null
        val token = draft.drop(1).takeWhile { !it.isWhitespace() }
        return draft.takeIf { token.isEmpty() || name.matches(token) }
    }
    fun parse(draft: String): SlashInvocation? {
        if (!draft.startsWith("/") || draft.length > MAX_PAYLOAD) return null
        val tail = draft.drop(1)
        val token = tail.takeWhile { !it.isWhitespace() }
        if (!name.matches(token)) return null
        return SlashInvocation(token.lowercase(java.util.Locale.ROOT), tail.drop(token.length).trim())
    }
    val local = listOf(
        SlashSuggestion("/new", "Start a new chat"),
        SlashSuggestion("/stop", "Stop the active turn"),
        SlashSuggestion("/model", "Choose the session model"),
        SlashSuggestion("/reasoning", "Set the reasoning effort"),
        SlashSuggestion("/resume", "Browse saved chats"),
        SlashSuggestion("/title", "Rename this chat"),
        SlashSuggestion("/status", "Show current session status"),
        SlashSuggestion("/compress", "Compress conversation context"),
        SlashSuggestion("/yolo", "Toggle approval bypass for this chat"),
        SlashSuggestion("/help", "Show available commands")
    )
    val aliases = mapOf("/reset" to "/new", "/clear" to "/new", "/commands" to "/help",
        "/sessions" to "/resume", "/switch" to "/resume", "/compact" to "/compress")
    // These require additional desktop surfaces/events or operate beyond the current chat.
    val unsupported = setOf("/branch", "/fork", "/wake", "/handoff", "/profile",
        "/skin", "/browser", "/journey", "/learning", "/memory-graph", "/btw", "/bg",
        "/background", "/pet", "/pets", "/hatch", "/generate-pet", "/save")
}
sealed interface SlashDirective {
    /** Acknowledges only the session flag; effective mode must come from fresh info. */
    class YoloChanged : SlashDirective

    class Output(val text: String) : SlashDirective { override fun toString() = "Output([redacted])" }
    class Alias(val target: String) : SlashDirective { override fun toString() = "Alias([redacted])" }
    class Send(val message: String, val display: String?, val notice: String?) : SlashDirective {
        override fun toString() = "Send([redacted])"
    }
    class Prefill(val message: String, val notice: String?) : SlashDirective {
        override fun toString() = "Prefill([redacted])"
    }
    class Compressed(val pending: Boolean, val changed: Boolean, val message: String, val failed: Boolean = false) : SlashDirective {
        override fun toString() = "Compressed([redacted])"
    }
}
enum class SlashNavigation { NEW_CHAT, SESSIONS, MODEL }
class SlashResult(val command: String, val text: String) {
    override fun toString() = "SlashResult([redacted])"
}
