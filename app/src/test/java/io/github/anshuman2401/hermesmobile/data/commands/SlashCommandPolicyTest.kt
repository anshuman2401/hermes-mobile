package io.github.anshuman2401.hermesmobile.data.commands

import io.github.anshuman2401.hermesmobile.feature.chat.SkillInvocationDisplay
import org.junit.Assert.*
import org.junit.Test

class SlashCommandPolicyTest {
    @Test fun onlyIndexZeroSlashStartsSuggestions() {
        for (draft in listOf(" /status", "\n/status", "ask /status", "hello\n/status", "/usr/local", "https://example.invalid/")) {
            assertNull(SlashCommandPolicy.query(draft))
            assertNull(SlashCommandPolicy.parse(draft))
        }
        for (draft in listOf("/", "/st", "/reasoning high", "/goal multi\nline")) {
            assertEquals(draft, SlashCommandPolicy.query(draft))
        }
    }
    @Test fun preservesMultilineArgumentsAndCanonicalizesOnlyName() {
        val parsed = SlashCommandPolicy.parse("/GOAL ship   it\n  carefully")!!
        assertEquals("goal", parsed.name)
        assertEquals("ship   it\n  carefully", parsed.argument)
        assertNull(SlashCommandPolicy.parse("/"))
        assertNull(SlashCommandPolicy.parse("/ some words"))
    }
    @Test fun queryAndPayloadBoundsAreIndependent() {
        assertNull(SlashCommandPolicy.query("/goal "+"x".repeat(SlashCommandPolicy.MAX_QUERY)))
        assertNotNull(SlashCommandPolicy.parse("/goal "+"x".repeat(3000)))
        assertNull(SlashCommandPolicy.parse("/goal "+"x".repeat(SlashCommandPolicy.MAX_PAYLOAD)))
    }
    @Test fun skillScaffoldingNeverBecomesVisibleBody() {
        val raw = "[IMPORTANT: The user has invoked the \"review\" skill. The full skill content is loaded below.]\nMODEL ONLY BODY\nThe user has provided the following instruction alongside the skill invocation: fix   it\n\n[Runtime note: hidden]"
        assertEquals("/review fix it", SkillInvocationDisplay.text(raw))
        assertEquals("ordinary prose", SkillInvocationDisplay.text("ordinary prose"))
        assertEquals("Skill invocation", SkillInvocationDisplay.text("[IMPORTANT: The user has invoked the \"broken"))
    }
}
