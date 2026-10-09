package com.hermes.bot.feature.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED-first tests for the fenced-code syntax highlighter (frozen plan
 * ui-reply-format-polish-v1 §1). JVM-only: pins the tokenizer contracts —
 * keyword/string/comment/number spans per language family, unclosed
 * constructs consuming to end of line (never throwing), unknown/absent
 * labels yielding zero spans, and empty code yielding zero spans.
 */
class SyntaxHighlighterTest {

    /** Test-local token palette (production values come from the theme). */
    private fun tokens() = CodeTokenColors(
        keyword = Color(0xFF7B1FA2),
        string = Color(0xFF2E7D32),
        comment = Color(0xFF455A64),
        number = Color(0xFFBF360C)
    )

    private fun spanTexts(code: String, language: String?) =
        SyntaxHighlighter.highlight(code, language, tokens())
            .spanStyles
            .map { code.substring(it.start, it.end) }

    // --- per-family tokens -------------------------------------------------

    @Test
    fun kotlin_keywords_strings_comments_numbers_getSpans() {
        val code = "val x = 1 // note\nreturn \"hi\""
        val texts = spanTexts(code, "kotlin")
        assertTrue("keyword 'val' span", texts.contains("val"))
        assertTrue("keyword 'return' span", texts.contains("return"))
        assertTrue("string span", texts.contains("\"hi\""))
        assertTrue("comment span", texts.contains("// note"))
        assertTrue("number span", texts.contains("1"))
    }

    @Test
    fun python_hash_comments_keywords_and_literals() {
        val code = "def f():\n    return True  # done"
        val texts = spanTexts(code, "python")
        assertTrue(texts.contains("def"))
        assertTrue(texts.contains("return"))
        assertTrue(texts.contains("True"))
        assertTrue("hash comment span", texts.contains("# done"))
    }

    @Test
    fun javascript_line_comment_and_keyword() {
        val code = "const x = 2; // init"
        val texts = spanTexts(code, "javascript")
        assertTrue(texts.contains("const"))
        assertTrue(texts.contains("// init"))
        assertTrue(texts.contains("2"))
    }

    @Test
    fun json_strings_numbers_and_bare_literals() {
        val code = "{\"k\": 42, \"flag\": true}"
        val texts = spanTexts(code, "json")
        assertTrue("key string span", texts.contains("\"k\""))
        assertTrue("number span", texts.contains("42"))
        assertTrue("literal span", texts.contains("true"))
    }

    @Test
    fun bash_hash_comment_and_keywords() {
        val code = "echo hi # install\nif [ -f x ]; then cd /tmp; fi"
        val texts = spanTexts(code, "bash")
        assertTrue(texts.contains("echo"))
        assertTrue(texts.contains("if"))
        assertTrue(texts.contains("then"))
        assertTrue(texts.contains("cd"))
        assertTrue(texts.contains("# install"))
    }

    @Test
    fun yaml_keys_comments_and_numbers() {
        val code = "name: app # meta\nversion: 1"
        val texts = spanTexts(code, "yaml")
        assertTrue("yaml key 'name' span", texts.contains("name"))
        assertTrue("yaml key 'version' span", texts.contains("version"))
        assertTrue(texts.contains("# meta"))
        assertTrue(texts.contains("1"))
    }

    // --- robustness (D-10 class: never throws, never hangs) -----------------

    @Test
    fun unclosed_string_consumesToLineEnd_withoutCrash() {
        val code = "val s = \"never closed"
        val texts = spanTexts(code, "kotlin")
        assertTrue("unclosed string consumes to end of line", texts.contains("\"never closed"))
    }

    @Test
    fun unclosed_blockComment_consumesToEnd_withoutCrash() {
        val code = "val x = 1 /* open"
        val texts = spanTexts(code, "kotlin")
        assertTrue(texts.contains("/* open"))
    }

    @Test
    fun unclosed_hash_comment_consumesToLineEnd() {
        val code = "x = 1 # open"
        val texts = spanTexts(code, "python")
        assertTrue(texts.contains("# open"))
    }

    @Test
    fun emptyCode_yieldsZeroSpans() {
        assertEquals(0, SyntaxHighlighter.highlight("", "kotlin", tokens()).spanStyles.size)
    }

    @Test
    fun unknownLanguage_yieldsZeroSpans() {
        assertEquals(0, spanTexts("SELECT * FROM t", "sql").size)
    }

    @Test
    fun absentOrBlankLabel_yieldsZeroSpans() {
        assertEquals(0, spanTexts("x = 1", null).size)
        assertEquals(0, spanTexts("x = 1", "").size)
    }

    @Test
    fun fenceLabel_isCaseInsensitive() {
        val texts = spanTexts("val x = 1", "Kotlin")
        assertTrue(texts.contains("val"))
    }

    @Test
    fun commentSpan_isItalic() {
        val code = "x  // note"
        val span = SyntaxHighlighter.highlight(code, "javascript", tokens())
            .spanStyles
            .first { code.substring(it.start, it.end) == "// note" }
        assertEquals(FontStyle.Italic, span.item.fontStyle)
    }

    @Test
    fun spans_stayWithinCodeBounds() {
        val code = "val x = \"s\" # not-bash\nval y = 2"
        for (span in SyntaxHighlighter.highlight(code, "kotlin", tokens()).spanStyles) {
            assertTrue(span.start in 0..code.length && span.end in 0..code.length && span.start < span.end)
        }
    }
}