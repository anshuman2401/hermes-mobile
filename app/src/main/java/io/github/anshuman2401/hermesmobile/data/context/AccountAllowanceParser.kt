package io.github.anshuman2401.hermesmobile.data.context

/** Projection of the host formatter, not a provider API or account credential reader. */
object AccountAllowanceParser {
    const val MAX_LINES = 32
    const val MAX_LINE_LENGTH = 1024
    private val window = Regex("^(Session|Weekly|Daily|Monthly|Subscription): (\\d{1,3})% remaining \\((\\d{1,3})% used\\)(?: • (.+))?$")
    private val unavailable = Regex("^(Session|Weekly|Daily|Monthly|Subscription): unavailable(?: • (.+))?$")
    private val banked = Regex("^You have (\\d{1,9}) resets banked - use /usage reset to activate$")

    fun parse(lines: List<String>): AccountAllowance {
        require(lines.size <= MAX_LINES && lines.all { it.length <= MAX_LINE_LENGTH })
        var provider: String? = null
        var resets: Int? = null
        val windows = mutableListOf<AllowanceWindow>()
        val other = mutableListOf<String>()
        for (raw in lines) {
            // Host strings are plain text only; redact secret-looking values.
            val line = io.github.anshuman2401.hermesmobile.data.gateway.GatewayRedactor.redactString(raw).trim()
            if (line.isBlank() || line == "📈 Account limits" || line == "Account limits") continue
            if (line.startsWith("Provider: ")) {
                provider = line.removePrefix("Provider: ").take(128)
                continue
            }
            val resetMatch = banked.matchEntire(line)
            if (resetMatch != null) {
                resets = resetMatch.groupValues[1].toIntOrNull()
                continue
            }
            val match = window.matchEntire(line)
            if (match != null) {
                val remaining = match.groupValues[2].toInt()
                val used = match.groupValues[3].toInt()
                if (remaining in 0..100 && used in 0..100) {
                    val tail = match.groupValues[4].takeIf { it.isNotBlank() }
                    windows.add(AllowanceWindow(match.groupValues[1], remaining,
                        tail?.takeIf { it.startsWith("resets ") }?.removePrefix("resets "),
                        tail?.takeUnless { it.startsWith("resets ") }))
                    continue
                }
            }
            val absent = unavailable.matchEntire(line)
            if (absent != null) {
                windows.add(AllowanceWindow(absent.groupValues[1], null, null,
                    absent.groupValues[2].takeIf { it.isNotBlank() } ?: "Not reported by Hermes"))
            } else other.add(line)
        }
        return AccountAllowance(provider, windows, resets, other)
    }
}
