package com.hermes.bot.feature.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle

/**
 * One fenced-code token color set (light/dark resolved by the caller from
 * HermesTheme.extendedColors). All shades are contrast-gated against the
 * code-block backgrounds — see Color.kt (ui-reply-format-polish-v1).
 */
data class CodeTokenColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color
)

/**
 * Lightweight fenced-code syntax highlighter (frozen plan
 * ui-reply-format-polish-v1 §1): single-pass, bounded, never-throwing
 * tokenizer → [AnnotatedString]. Language family by the fence label:
 * kotlin/java, python, javascript/typescript, json, bash/shell/sh, yaml;
 * unknown/absent/blank label → zero spans (plain monospace, the pre-PR look).
 *
 * Guarantees (same robustness class as MarkdownParser's D-10 contracts):
 * - Never throws or hangs: an unclosed string/`//`/`#` comment consumes to
 *   the end of its line, an unclosed block comment (`/` + `*`) to the end
 *   of its line;
 *   a closed block comment may span lines; every span stays inside bounds.
 * - Empty code → zero spans.
 * - Per-family keyword lists ≤60 words; only single-line constructs.
 *
 * Colors are passed in via [CodeTokenColors] (theme-resolved by the caller)
 * so the tokenizer stays a pure JVM-testable function.
 */
object SyntaxHighlighter {

    private val KOTLIN_JAVA_KEYWORDS = setOf(
        "abstract", "as", "break", "case", "catch", "class", "const", "continue",
        "do", "else", "false", "final", "finally", "for", "fun", "if", "import",
        "in", "interface", "is", "null", "object", "package", "private",
        "protected", "public", "return", "static", "super", "switch", "this",
        "throw", "true", "try", "val", "var", "void", "when", "while"
    )
    private val PYTHON_KEYWORDS = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue",
        "def", "del", "elif", "else", "except", "False", "finally", "for",
        "from", "global", "if", "import", "in", "is", "lambda", "None",
        "nonlocal", "not", "or", "pass", "raise", "return", "True", "try",
        "while", "with", "yield"
    )
    private val JS_TS_KEYWORDS = setOf(
        "async", "await", "break", "case", "catch", "class", "const",
        "continue", "default", "delete", "do", "else", "export", "extends",
        "false", "finally", "for", "function", "if", "import", "in",
        "instanceof", "interface", "let", "new", "null", "of", "return",
        "static", "super", "switch", "this", "throw", "true", "try", "type",
        "typeof", "undefined", "var", "void", "while", "yield"
    )
    private val JSON_LITERALS = setOf("true", "false", "null")
    private val BASH_KEYWORDS = setOf(
        "break", "case", "cd", "continue", "do", "done", "echo", "elif",
        "else", "esac", "exit", "export", "fi", "for", "function", "if",
        "in", "local", "return", "set", "shift", "source", "then", "unset",
        "until", "while"
    )
    private val YAML_KEYS = setOf(
        "apiVersion", "args", "command", "containers", "description", "env",
        "entrypoint", "id", "if", "image", "jobs", "kind", "labels", "limits",
        "matrix", "metadata", "mode", "name", "needs", "on", "outputs",
        "path", "ports", "requests", "resources", "restartPolicy", "run",
        "runs-on", "selector", "services", "shell", "source", "spec",
        "steps", "strategy", "target", "template", "timeout-minutes", "type",
        "uses", "version", "volumes", "working-directory"
    )

    private val FAMILIES: Map<String, (String, CodeTokenColors) -> AnnotatedString> = mapOf(
        "kotlin" to { c, t -> walk(c, t, KOTLIN_JAVA_KEYWORDS, hashIsComment = false, yamlKeys = null) },
        "java" to { c, t -> walk(c, t, KOTLIN_JAVA_KEYWORDS, hashIsComment = false, yamlKeys = null) },
        "python" to { c, t -> walk(c, t, PYTHON_KEYWORDS, hashIsComment = true, yamlKeys = null) },
        "javascript" to { c, t -> walk(c, t, JS_TS_KEYWORDS, hashIsComment = false, yamlKeys = null) },
        "typescript" to { c, t -> walk(c, t, JS_TS_KEYWORDS, hashIsComment = false, yamlKeys = null) },
        "json" to { c, t -> walk(c, t, JSON_LITERALS, hashIsComment = false, yamlKeys = null) },
        "bash" to { c, t -> walk(c, t, BASH_KEYWORDS, hashIsComment = true, yamlKeys = null) },
        "shell" to { c, t -> walk(c, t, BASH_KEYWORDS, hashIsComment = true, yamlKeys = null) },
        "sh" to { c, t -> walk(c, t, BASH_KEYWORDS, hashIsComment = true, yamlKeys = null) },
        "yaml" to { c, t -> walk(c, t, emptySet(), hashIsComment = true, yamlKeys = YAML_KEYS) }
    )

    /** Maps a fence label to its walker; null/blank/unknown label → null. */
    fun familyFor(language: String?): ((String, CodeTokenColors) -> AnnotatedString)? =
        language?.trim()?.lowercase()?.let { FAMILIES[it] }

    /**
     * Highlights [code] for [language]; unknown/absent/blank label or empty
     * code → zero spans (plain text, identical to the pre-PR monospace look).
     */
    fun highlight(code: String, language: String?, tokens: CodeTokenColors): AnnotatedString {
        if (code.isEmpty()) return buildAnnotatedString { append(code) }
        val family = familyFor(language) ?: return buildAnnotatedString { append(code) }
        return try {
            family(code, tokens)
        } catch (_: Exception) {
            // D-10 class: never throw out of the highlighter; degrade to plain.
            buildAnnotatedString { append(code) }
        }
    }

    private fun keywordStyle(tokens: CodeTokenColors) = SpanStyle(color = tokens.keyword)
    private fun stringStyle(tokens: CodeTokenColors) = SpanStyle(color = tokens.string)
    private fun commentStyle(tokens: CodeTokenColors) =
        SpanStyle(color = tokens.comment, fontStyle = FontStyle.Italic)
    private fun numberStyle(tokens: CodeTokenColors) = SpanStyle(color = tokens.number)

    /**
     * Single-pass walk shared by all families. Per-family toggles:
     * [hashIsComment] makes `#` start a comment (python/bash/yaml);
     * [yamlKeys] (when non-null) styles `key:` tokens as keywords.
     */
    private fun walk(
        code: String,
        tokens: CodeTokenColors,
        keywords: Set<String>,
        hashIsComment: Boolean,
        yamlKeys: Set<String>?
    ): AnnotatedString = buildAnnotatedString {
        val len = code.length
        var plainStart = 0
        fun flushPlain(until: Int) {
            if (until > plainStart) append(code.substring(plainStart, until))
        }
        var i = 0
        while (i < len) {
            val c = code[i]
            // 1. Comments: // … (to EOL), /* … */ (closed; unclosed → EOL),
            //    # … (to EOL) for the hash-comment families.
            if (c == '/' && i + 1 < len && code[i + 1] == '/') {
                flushPlain(i)
                val end = code.indexOf('\n', i).let { if (it == -1) len else it }
                withStyle(commentStyle(tokens)) { append(code.substring(i, end)) }
                plainStart = end
                i = end
                continue
            }
            if (c == '/' && i + 1 < len && code[i + 1] == '*') {
                val close = code.indexOf("*/", i + 2)
                val end = if (close == -1) {
                    code.indexOf('\n', i).let { if (it == -1) len else it }
                } else {
                    close + 2
                }
                flushPlain(i)
                withStyle(commentStyle(tokens)) { append(code.substring(i, end)) }
                plainStart = end
                i = end
                continue
            }
            if (c == '#' && hashIsComment) {
                flushPlain(i)
                val end = code.indexOf('\n', i).let { if (it == -1) len else it }
                withStyle(commentStyle(tokens)) { append(code.substring(i, end)) }
                plainStart = end
                i = end
                continue
            }
            // 2. Strings '…' / "…"; an unclosed string consumes to end of line.
            if (c == '"' || c == '\'') {
                val lineEnd = code.indexOf('\n', i).let { if (it == -1) len else it }
                var j = i + 1
                while (j < lineEnd) {
                    if (code[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (code[j] == c) {
                        j++
                        break
                    }
                    j++
                }
                val end = minOf(j, lineEnd)
                flushPlain(i)
                withStyle(stringStyle(tokens)) { append(code.substring(i, end)) }
                plainStart = end
                i = end
                continue
            }
            // 3. Numbers (int/float/hex runs starting on a digit).
            if (c.isDigit()) {
                val start = i
                while (i < len && (code[i].isDigit() || code[i] == '.' || code[i] == '_' ||
                        code[i] == 'x' || code[i] == 'X' ||
                        code[i] in 'a'..'f' || code[i] in 'A'..'F')
                ) i++
                flushPlain(start)
                withStyle(numberStyle(tokens)) { append(code.substring(start, i)) }
                plainStart = i
                continue
            }
            // 4. Identifiers: keywords, or (yaml) bare keys before ':'.
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < len && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '-')) i++
                val word = code.substring(start, i)
                val isKeyword = word in keywords
                val isYamlKey = yamlKeys != null &&
                    i < len && code[i] == ':' &&
                    (start == 0 || !code[start - 1].isLetterOrDigit()) &&
                    word in yamlKeys
                if (isKeyword || isYamlKey) {
                    flushPlain(start)
                    withStyle(keywordStyle(tokens)) { append(word) }
                    plainStart = i
                }
                continue
            }
            i++
        }
        flushPlain(len)
    }
}