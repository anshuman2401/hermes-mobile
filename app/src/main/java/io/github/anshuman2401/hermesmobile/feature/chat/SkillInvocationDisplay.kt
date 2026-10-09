package io.github.anshuman2401.hermesmobile.feature.chat

/** Older hosts may return model-facing skill scaffolding in user history. */
object SkillInvocationDisplay {
    fun text(raw: String): String {
        val prefix = "[IMPORTANT: The user has invoked the \""
        if (!raw.startsWith(prefix)) return raw
        val end = raw.indexOf('"', prefix.length)
        if (end < 0) return "Skill invocation"
        val name = raw.substring(prefix.length, end).trim()
        if (name.isBlank() || name.length > 256) return "Skill invocation"
        val label = if (name.startsWith("/")) name else "/$name"
        val bundle = raw.contains(" skill bundle,")
        val marker = if (bundle) "\nUser instruction: " else
            "The user has provided the following instruction alongside the skill invocation: "
        val start = if (bundle) raw.indexOf(marker) else raw.lastIndexOf(marker)
        if (start < 0) return label
        val tail = raw.substring(start + marker.length)
        val stop = tail.indexOf(if (bundle) "\n\n[Loaded as part of the " else "\n\n[Runtime note:")
        val instruction = (if (stop < 0) tail else tail.substring(0, stop)).trim().replace(Regex("\\s+"), " ")
        return label + if (instruction.isBlank()) "" else " $instruction"
    }
}
