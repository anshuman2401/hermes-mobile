package io.github.anshuman2401.hermesmobile.data.files

import org.junit.Assert.*
import org.junit.Test

class ConversationFilesTest {
    @Test fun explicitAssistantReferencesWithoutCodeOrProseScraping() {
        val result = ConversationFileReferences.assistant("""
            [Brief](reports/brief.pdf) ![Cover](/synthetic/cover.png)
            [Same](reports/brief.pdf) [External](https://example.test/a.pdf)
            Ordinary prose mentions /synthetic/not-shared.pdf
            `[Example](/synthetic/example.pdf)`
            ```
            [Also an example](/synthetic/code.pdf)
            ```
            MEDIA:/synthetic/data.csv
        """.trimIndent())
        assertEquals(listOf("brief.pdf", "cover.png", "data.csv"), result.map { it.name })
        assertTrue(result.none { it.created })
    }
    @Test fun validatesPathsAndNamesWithoutOriginChanges() {
        listOf("../secret.txt", "/a/../b.txt", "file://example.test/a.pdf", "http://example.test/x.pdf", "//example.test/x.pdf",
            "data:text/plain,hello", "a.pdf?token=x", "a\nb.pdf", "/.env", "/a/.private/key.txt", "a.pem", "credentials.json", "a%2f..%2fsecret.txt",
            "x".repeat(2049), "#media:https%3A%2F%2Fexample.test%2Fa.pdf").forEach { assertNull(it, ConversationFileReferences.reference(it)) }
        assertEquals("hello world.pdf", ConversationFileReferences.reference("file:///synthetic/hello%20world.pdf")!!.name)
        assertEquals("a+b.csv", ConversationFileReferences.reference("a+b.csv")!!.name)
        assertEquals("report.pdf", ConversationFileReferences.reference("#media:%2Fsynthetic%2Freport.pdf")!!.name)
    }
    @Test fun successfulToolOutputsMustHaveExplicitProvenance() {
        assertTrue(ConversationFileReferences.tool(mapOf("output_file" to "report.pdf")).isEmpty())
        assertTrue(ConversationFileReferences.tool(mapOf("ok" to false, "output_file" to "report.pdf")).isEmpty())
        assertTrue(ConversationFileReferences.tool(mapOf("ok" to true, "error" to "synthetic", "generated_file" to "report.pdf")).isEmpty())
        val files = ConversationFileReferences.tool(mapOf("success" to true, "files_created" to listOf("a.pdf", "a.pdf"), "output_path" to "b.csv"))
        assertEquals(2, files.size); assertTrue(files[0].created); assertFalse(files[1].created)
        assertEquals("a.pdf", ConversationFileReferences.tool("""{"ok":true,"generated_file":"a.pdf"}""").single().name)
        assertTrue(ConversationFileReferences.tool(mapOf("ok" to true, "stdout" to "/synthetic/log.pdf")).isEmpty())
    }
    @Test fun writeFileContractDoesNotTurnReadResultsIntoOutputs() {
        val payload = mapOf("bytes_written" to 30, "files_modified" to listOf("written.md"))
        assertTrue(ConversationFileReferences.tool(payload, "read_file").isEmpty())
        assertEquals("written.md", ConversationFileReferences.tool(payload, "write_file").single().name)
        assertFalse(ConversationFileReferences.tool(payload, "write_file").single().created)
    }
    @Test fun referenceAndMessageBudgetsAndRedactedToString() {
        val files = ConversationFileReferences.assistant((0..150).joinToString(" ") { "[file](out/file$it.pdf)" })
        assertEquals(100, files.size)
        assertTrue(ConversationFileReferences.assistant("a".repeat(FileLimits.MESSAGE_CHARS + 1)).isEmpty())
        assertEquals(8, ConversationFileReferences.tool(mapOf("ok" to true, "files_created" to (0..50).map { "file$it.pdf" })).size)
        assertFalse(files.first().toString().contains("file0"))
        assertFalse(FileContent(files.first(), "synthetic-content".toByteArray()).toString().contains("synthetic-content"))
    }
}
