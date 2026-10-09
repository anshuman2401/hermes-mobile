package io.github.anshuman2401.hermesmobile.feature.chat

import java.util.Locale

/**
 * Pure stateless decision policy for Step Detail (Level 2) per PRD v2 (owner-approved).
 * Governs dual-box and single-box detail layouts, 200-char preview truncation,
 * cwd extraction, word counts, and copy-all text generation.
 * All methods are pure and JVM-testable.
 */
object ToolDetailPolicy {

    /** Display-only preview cap per PRD v2 § 3 (Step Detail). */
    const val PREVIEW_CHAR_CAP: Int = 200

    /** Escaped fallback text for malformed or absent payloads. */
    const val DETAILS_UNAVAILABLE: String = "Details unavailable"

    private val CWD_ARG_REGEX = Regex("""(?i)"?(?:cwd|working_directory|dir)"?\s*[:=]\s*"([^"]+)"""")
    private val CWD_PLAIN_REGEX = Regex("""(?i)(?:cwd|working_directory|dir)\s*[:=]\s*([/\w\-\.\~]+)""")

    /**
     * Extracts host-provided cwd from tool args or context without fabricating (PRD v2 § 3).
     * Returns null when absent so the cwd row is hidden.
     */
    fun extractCwd(activity: ToolActivity): String? {
        // 1. Try extracting from argsSummary
        val args = activity.argsSummary
        if (!args.isNullOrBlank()) {
            val quotedMatch = CWD_ARG_REGEX.find(args)
            if (quotedMatch != null) {
                return quotedMatch.groupValues[1].trim()
            }
            val plainMatch = CWD_PLAIN_REGEX.find(args)
            if (plainMatch != null) {
                return plainMatch.groupValues[1].trim()
            }
        }

        // 2. Try context if it explicitly specifies cwd or is a filesystem path
        val ctx = activity.context?.trim()
        if (!ctx.isNullOrBlank()) {
            val quotedMatch = CWD_ARG_REGEX.find(ctx)
            if (quotedMatch != null) {
                return quotedMatch.groupValues[1].trim()
            }
            val plainMatch = CWD_PLAIN_REGEX.find(ctx)
            if (plainMatch != null) {
                return plainMatch.groupValues[1].trim()
            }
            if (ctx.startsWith("/") || ctx.startsWith("~/")) {
                return ctx
            }
        }

        return null
    }

    /**
     * Returns the tool input content to display (args/context).
     * Returns null if no host-provided input is available (e.g. historical resumed steps).
     */
    fun formatInputContent(activity: ToolActivity): String? {
        val args = if (activity.storedSource?.argumentsPresent == true)
            activity.argsSummary?.ifEmpty { "\"\"" }
        else activity.argsSummary?.takeUnless { it.isBlank() || it == "(no arguments)" }
        val context = activity.context?.takeUnless { it.isBlank() }
        return when {
            args != null && context != null && args != context -> "Context: $context\nArguments: $args"
            args != null -> args
            context != null -> context
            else -> null
        }
    }

    /**
     * Returns the tool output content to display.
     * Returns null if no output is available (historical steps or still running).
     */
    fun formatOutputContent(activity: ToolActivity): String? {
        if (activity.storedSource != null) return activity.resultSummary?.ifEmpty { "\"\"" }
        return activity.resultSummary?.takeUnless { it.isBlank() || it == "(empty result)" }
    }

    /**
     * Computes word count for reasoning text per PRD v2 § 3.
     */
    fun wordCount(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return text.trim().split(Regex("""\s+""")).size
    }

    /**
     * Returns whether the text length exceeds the 200-char preview cap.
     */
    fun exceedsPreviewCap(text: String?, cap: Int = PREVIEW_CHAR_CAP): Boolean {
        return (text?.length ?: 0) > cap
    }

    /**
     * Returns the preview string (at most [cap] chars with ellipsis if longer).
     */
    fun previewText(text: String?, cap: Int = PREVIEW_CHAR_CAP): String {
        if (text.isNullOrEmpty()) return ""
        return if (text.length <= cap) text else text.substring(0, cap) + "…"
    }

    /**
     * Generates the "Copy all" text for a tool step, reasoning entry, or generic activity.
     * Always includes full untruncated content regardless of preview collapse state.
     */
    fun formatCopyAll(step: ToolBlockStep): String {
        return when (step) {
            is ToolBlockStep.Tool -> {
                val act = step.activity
                val cwd = extractCwd(act)
                val input = formatInputContent(act)
                val output = formatOutputContent(act)
                buildString {
                    appendLine("Tool: ${act.name}")
                    appendLine("Status: ${if (step.isRunning) "Running" else "Completed"}")
                    if (act.durationSeconds != null) {
                        appendLine("Duration: ${String.format(Locale.ROOT, "%.1fs", act.durationSeconds)}")
                    }
                    if (cwd != null) {
                        appendLine("Working directory: $cwd")
                    }
                    if (input != null) {
                        appendLine("Input:\n$input")
                    }
                    if (output != null) {
                        appendLine("Output:\n$output")
                    }
                }.trimEnd()
            }

            is ToolBlockStep.Reasoning -> {
                val words = wordCount(step.reasoning.text)
                buildString {
                    appendLine(step.name)
                    appendLine("Words: $words")
                    appendLine("Content:\n${step.reasoning.text}")
                }.trimEnd()
            }

            is ToolBlockStep.Generic -> {
                buildString {
                    appendLine("Event: ${step.generic.eventType}")
                    appendLine("Summary: ${step.generic.summary}")
                    if (!step.generic.payloadPreview.isNullOrBlank()) {
                        appendLine("Payload:\n${step.generic.payloadPreview}")
                    }
                }.trimEnd()
            }

            is ToolBlockStep.Note -> {
                buildString {
                    appendLine("Agent note")
                    appendLine(step.note.text)
                }.trimEnd()
            }
        }
    }
}
