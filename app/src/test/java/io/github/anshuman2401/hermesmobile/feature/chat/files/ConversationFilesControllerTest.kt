package io.github.anshuman2401.hermesmobile.feature.chat.files

import io.github.anshuman2401.hermesmobile.data.files.*
import io.github.anshuman2401.hermesmobile.data.voice.VoiceTestFixture
import io.github.anshuman2401.hermesmobile.feature.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationFilesControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private class Repository(val owner: FileOwner) : ConversationFileRepository {
        var current = true
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        var delivered: FileContent? = null
        override suspend fun capture(storedId: String, runtimeId: String) = if (current) owner else null
        override suspend fun isCurrent(owner: FileOwner) = current
        override suspend fun download(owner: FileOwner, reference: ConversationFile): FileContent {
            calls++
            if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
            return FileContent(reference, "synthetic".toByteArray()).also { delivered = it }
        }
    }
    private suspend fun fixture(): Repository {
        val f = VoiceTestFixture(temp.newFolder()); f.initialize()
        return Repository(FileOwner(f.sessions.captureSendActivityOwner("synthetic-stored", "synthetic-runtime")!!))
    }
    private val messages = listOf(AssistantMessage("message", "[File](report.pdf)"))
    @Test fun listDeduplicatesAndExcludesUserAndStreamingWithoutNetwork() = runTest {
        val repo = fixture()
        val controller = ConversationFilesController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        controller.update("stored", "runtime", true, messages + listOf(UserMessage("user", "[Input](input.pdf)"),
            AssistantMessage("stream", "[Wait](stream.pdf)", AssistantMessageState.STREAMING), AssistantMessage("repeat", "[Repeat](report.pdf)")))
        assertEquals(listOf("report.pdf"), controller.state.value.items.map { it.name }); assertEquals(0, repo.calls)
        controller.show(); assertTrue(controller.state.value.visible); controller.back(); assertFalse(controller.state.value.visible)
    }
    @Test fun toolAndReplyKeepCardsWhileListDeduplicates() = runTest {
        val repo = fixture(); val controller = ConversationFilesController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        val generated = ConversationFileReferences.tool(mapOf("ok" to true, "generated_file" to "report.pdf"))
        controller.update("stored", "runtime", true,
            listOf(ToolActivity("tool", "tool-id", "generate", ToolStatus.COMPLETED, files = generated)) + messages + messages.map { it.copy(id = "repeat") })
        assertEquals(1, controller.state.value.items.size)
        assertEquals(setOf("tool", "message", "repeat"), controller.state.value.byMessage.keys)
        assertTrue(controller.state.value.byMessage.getValue("message").single().created)
        controller.close()
    }
    @Test fun openBackExportAndScopeRejection() = runTest {
        val repo = fixture(); val controller = ConversationFilesController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        controller.update("stored", "runtime", true, messages)
        controller.open(controller.state.value.items.single()); runCurrent()
        assertEquals(1, repo.calls); val content = controller.state.value.content!!
        var exports = 0
        assertTrue(controller.export(content) { assertEquals("synthetic", String(it.bytes)); exports++ })
        repo.current = false
        assertFalse(controller.export(content) { exports++ }); assertEquals(1, exports)
        advanceTimeBy(501); runCurrent()
        assertNull(controller.state.value.content); assertArrayEquals(ByteArray(content.size), content.bytes)
        controller.close()
    }
    @Test fun lateCancelledDownloadCannotReplaceNewSession() = runTest {
        val repo = fixture(); repo.gate = CompletableDeferred(); repo.ignoreCancellation = true
        val controller = ConversationFilesController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        controller.update("stored", "runtime", true, messages)
        controller.open(controller.state.value.items.single()); runCurrent()
        controller.update("other", "other-runtime", true, emptyList())
        repo.gate!!.complete(Unit); runCurrent()
        assertNull(controller.state.value.content); assertTrue(controller.state.value.items.isEmpty())
        repo.delivered?.let { assertArrayEquals(ByteArray(it.size), it.bytes) }
        controller.close()
    }
    @Test fun rebindingClearsOldReferencesBeforeSuspendingProjection() = runTest {
        val repo = fixture(); val controller = ConversationFilesController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        controller.update("stored", "runtime", true, messages)
        val old = controller.state.value.items.single()
        val transition = launch(start = CoroutineStart.UNDISPATCHED) { controller.update("other", "other-runtime", true, emptyList()) }
        controller.open(old)
        assertEquals(0, repo.calls); assertTrue(controller.state.value.items.isEmpty())
        transition.join(); controller.close()
    }
    @Test fun staleListCannotRecaptureNewAuthorityAtClickTime() = runTest {
        val repo = fixture(); val controller = ConversationFilesController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        controller.update("stored", "runtime", true, messages)
        repo.current = false
        controller.open(controller.state.value.items.single()); runCurrent()
        assertEquals(0, repo.calls); assertNull(controller.state.value.content)
        controller.close()
    }
    @Test fun offlineAndUnlistedReferencesNeverDownload() = runTest {
        val repo = fixture(); val controller = ConversationFilesController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        controller.update("stored", null, false, messages)
        controller.open(controller.state.value.items.single()); runCurrent()
        assertEquals(0, repo.calls); assertNotNull(controller.state.value.message)
        controller.open(ConversationFileReferences.reference("unrelated.pdf")!!); runCurrent(); assertEquals(0, repo.calls)
        controller.close()
    }
    @Test fun loadedWindowBudgetAndToolOutputs() = runTest {
        val repo = fixture(); val controller = ConversationFilesController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        val generated = ConversationFileReferences.tool(mapOf("ok" to true, "generated_file" to "generated.png"))
        controller.update("stored", "runtime", true, listOf(ToolActivity("tool", "tool-id", "generate", ToolStatus.COMPLETED, files = generated)) +
            (0..120).map { AssistantMessage("$it", "[File](file$it.pdf)") })
        assertEquals(100, controller.state.value.items.size); assertTrue(controller.state.value.items.first().created)
        assertTrue(controller.state.value.limited); controller.close()
    }
    @Test fun backgroundCancelsLoadingButPreservesCompletedContentForSaveChooser() = runTest {
        val repo = fixture(); val controller = ConversationFilesController(backgroundScope, repo, StandardTestDispatcher(testScheduler))
        controller.update("stored", "runtime", true, messages)
        repo.gate = CompletableDeferred()
        controller.open(controller.state.value.items.single()); runCurrent(); controller.background(); runCurrent()
        assertFalse(controller.state.value.loading); assertNull(controller.state.value.content)
        repo.gate = null
        controller.open(controller.state.value.items.single()); runCurrent()
        val content = controller.state.value.content!!
        controller.background(); assertSame(content, controller.state.value.content)
        controller.back(); assertTrue(controller.state.value.visible); assertNull(controller.state.value.selected)
        controller.back(); assertFalse(controller.state.value.visible)
    }
}
