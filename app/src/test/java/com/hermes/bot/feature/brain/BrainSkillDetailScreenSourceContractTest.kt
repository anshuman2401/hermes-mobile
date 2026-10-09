package com.hermes.bot.feature.brain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source checks supplement VM/policy behavior; Compose lifecycle rendering is not proven here. */
class BrainSkillDetailScreenSourceContractTest {
    @Test
    fun detailIsReadOnlyAndHasIdentityStatesAndLocalOwnership() {
        val screen = source("com/hermes/bot/feature/brain/BrainSkillDetailScreen.kt")
        val list = source("com/hermes/bot/feature/brain/BrainSkillsScreen.kt")
        assertTrue(screen.contains("Profile: ${'$'}{identity.selectedProfile}"))
        assertTrue(screen.contains("BrainSkillDetailState.Empty"))
        assertTrue(screen.contains("BrainSkillDetailState.Document"))
        assertTrue(screen.contains("BrainSkillDetailState.Unavailable"))
        assertTrue(screen.contains("Back to Skills"))
        assertTrue(list.contains("ViewModelStore()"))
        assertTrue(list.contains("detailStore.clear()"))
        assertTrue(list.contains("DisposableEffect(Unit)"))
        listOf(
            "WebView",
            "Clipboard",
            "onCopy",
            "onRun",
            "onSearch",
            "onRefresh",
            "Text(\"Copy\"",
            "Text(\"Run\"",
            "Text(\"Search\"",
            "Text(\"Refresh\"",
            "contentDescription = \"Copy\"",
            "contentDescription = \"Run\"",
            "contentDescription = \"Search\"",
            "contentDescription = \"Refresh\""
        ).forEach {
            assertTrue("Unexpected detail action: $it", !screen.contains(it))
        }
    }

    @Test
    fun normalDocumentRenderingUsesPresentationMappingsForFormattedBlocks() {
        val renderer = source("com/hermes/bot/feature/brain/components/SkillMarkdownDocument.kt")

        // This is a source-level contract supplement; it intentionally does not claim Compose visual proof.
        listOf("HEADING", "LIST", "QUOTE", "TABLE").forEach { kind ->
            val branch = renderer.substringAfter("SkillDocumentBlockKind.$kind ->")
                .substringBefore("SkillDocumentBlockKind.")
            assertFalse(
                "$kind must not render the whole source as its normal presentation",
                branch.contains("styledInline(block.source)")
            )
        }
        val codeBranch = renderer.substringAfter("SkillDocumentBlockKind.CODE ->")
            .substringBefore("SkillDocumentBlockKind.")
        assertFalse("fenced code must not expose fence delimiters", codeBranch.contains("text = block.source"))
        assertTrue("inline emphasis needs an italic presentation style", renderer.contains("FontStyle.Italic"))
        assertTrue("inline emphasis needs a bold presentation style", renderer.contains("FontWeight.Bold"))
    }

    private fun source(relativePath: String): String {
        val direct = File("src/main/java/$relativePath")
        val fromRepoRoot = File("app/src/main/java/$relativePath")
        val file = if (direct.exists()) direct else fromRepoRoot
        assertTrue("Expected source file to exist: $relativePath", file.exists())
        return file.readText()
    }
}
