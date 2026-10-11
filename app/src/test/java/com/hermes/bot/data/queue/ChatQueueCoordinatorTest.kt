package com.hermes.bot.data.queue

import com.hermes.bot.data.commands.SlashInvocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MemoryChatQueueStore : ChatQueueStore {
    var json: String? = null
    var current = true
    var failWrite = false
    var throwOwner = false
    override suspend fun read() = json?.let(ChatQueueCodec::decode)
    override suspend fun isCurrent(): Boolean { if (throwOwner) error("Synthetic storage unavailable"); return current }
    override suspend fun write(state: ChatQueueState): Boolean {
        if (!current || failWrite) return false
        json = ChatQueueCodec.encode(state); return true
    }
}
class ChatQueueCoordinatorTest {
    @Test fun mixedFifoWaitsForCompletionAndAcceptsDuringActive() = runTest {
        val store = MemoryChatQueueStore(); val queue = ChatQueueCoordinator(store); queue.restore()
        queue.enqueue(QueuePayload.Prompt("synthetic first"))
        val gate = CompletableDeferred<Unit>(); val seen = mutableListOf<String>()
        val active = async { queue.runNext({ true }) { seen += it.id; gate.await(); QueueExecutionResult.COMPLETE } }
        testScheduler.runCurrent()
        assertNotNull(queue.state.value.active)
        queue.enqueue(QueuePayload.Command(SlashInvocation("yolo", "on")))
        queue.enqueue(QueuePayload.Prompt("synthetic next"))
        queue.runNext({ true }) { fail("second dispatch during active"); QueueExecutionResult.COMPLETE }
        gate.complete(Unit); active.await()
        assertEquals(2, queue.state.value.waiting.size)
        queue.runNext({ true }) { assertTrue(it.payload is QueuePayload.Command); seen += it.id; QueueExecutionResult.COMPLETE }
        queue.runNext({ true }) { assertTrue(it.payload is QueuePayload.Prompt); seen += it.id; QueueExecutionResult.COMPLETE }
        assertEquals(3, seen.distinct().size)
        assertFalse(queue.state.value.visible)
    }
    @Test fun limitAndStorageFailureNeverAcceptOrLoseAnItem() = runTest {
        val store = MemoryChatQueueStore(); val queue = ChatQueueCoordinator(store); queue.restore()
        repeat(10) { assertTrue(queue.enqueue(QueuePayload.Prompt("synthetic $it"))) }
        assertFalse(queue.enqueue(QueuePayload.Prompt("overflow")))
        assertEquals(10, queue.state.value.waiting.size)
        val first = queue.state.value.waiting.first().id
        store.failWrite = true; queue.cancel(first)
        assertEquals(10, queue.state.value.waiting.size)
        assertEquals(QueuePause.STORAGE, queue.state.value.pause)
    }
    @Test fun editKeepsPositionPausesAndCancellationRestoresState() = runTest {
        val queue = ChatQueueCoordinator(MemoryChatQueueStore()); queue.restore()
        queue.enqueue(QueuePayload.Prompt("one")); queue.enqueue(QueuePayload.Prompt("two"))
        val id = queue.state.value.waiting.first().id
        queue.beginEdit(id); queue.editText("edited")
        queue.runNext({ true }) { fail("dispatch while editing"); QueueExecutionResult.COMPLETE }
        queue.finishEdit(true)
        assertEquals(id, queue.state.value.waiting.first().id)
        assertEquals("edited", (queue.state.value.waiting.first().payload as QueuePayload.Prompt).text)
        assertNull(queue.state.value.pause)
        queue.beginEdit(id); queue.editText("/yolo on"); queue.finishEdit(true)
        assertNotNull(queue.state.value.edit)
        queue.editText("  /yolo on"); queue.finishEdit(true)
        assertNotNull(queue.state.value.edit)
        queue.finishEdit(false)
        assertEquals("edited", (queue.state.value.waiting.first().payload as QueuePayload.Prompt).text)
    }
    @Test fun backgroundPauseDuringEditSurvivesCancel() = runTest {
        val queue = ChatQueueCoordinator(MemoryChatQueueStore()); queue.restore()
        queue.enqueue(QueuePayload.Prompt("synthetic"))
        queue.beginEdit(queue.state.value.waiting.single().id)
        queue.pause(QueuePause.BACKGROUND); queue.finishEdit(false)
        assertEquals(QueuePause.BACKGROUND, queue.state.value.pause)
    }
    @Test fun commandsCannotBeEditedAndCancelHasNoHostEffect() = runTest {
        val queue = ChatQueueCoordinator(MemoryChatQueueStore()); queue.restore()
        queue.enqueue(QueuePayload.Command(SlashInvocation("yolo", "off")))
        val id = queue.state.value.waiting.single().id
        queue.beginEdit(id); assertNull(queue.state.value.edit)
        queue.cancel(id); assertTrue(queue.state.value.waiting.isEmpty())
    }
    @Test fun restoredDispatchIsUnknownAndCannotReplay() = runTest {
        val store = MemoryChatQueueStore(); val first = ChatQueueCoordinator(store); first.restore()
        first.enqueue(QueuePayload.Prompt("synthetic"))
        first.runNext({ true }) {
            val recovered = ChatQueueCoordinator(store); recovered.restore()
            assertEquals(QueueItemState.UNKNOWN, recovered.state.value.active?.state)
            assertFalse(recovered.resume())
            recovered.runNext({ true }) { fail("replayed active write"); QueueExecutionResult.COMPLETE }
            QueueExecutionResult.UNKNOWN
        }
        assertFalse(first.resume())
    }
    @Test fun restoredWaitingQueueNeedsExplicitResumeAndOwnerMustMatch() = runTest {
        val store = MemoryChatQueueStore(); val first = ChatQueueCoordinator(store); first.restore()
        first.enqueue(QueuePayload.Prompt("synthetic"))
        val restored = ChatQueueCoordinator(store); restored.restore()
        assertEquals(QueuePause.RESTORED, restored.state.value.pause)
        restored.runNext({ true }) { fail("auto restored send"); QueueExecutionResult.COMPLETE }
        store.current = false; assertFalse(restored.resume())
        store.current = true; assertTrue(restored.resume())
        restored.runNext({ true }) { QueueExecutionResult.COMPLETE }
        assertTrue(restored.state.value.waiting.isEmpty())
    }
    @Test fun failedItemHoldsLaterItemsAndRetryUsesNewIdentity() = runTest {
        val queue = ChatQueueCoordinator(MemoryChatQueueStore()); queue.restore()
        queue.enqueue(QueuePayload.Prompt("one")); queue.enqueue(QueuePayload.Prompt("two"))
        val id = queue.state.value.waiting.first().id
        queue.runNext({ true }) { QueueExecutionResult.FAILED }
        assertEquals(QueuePause.FAILED, queue.state.value.pause)
        queue.runNext({ true }) { fail("continued after failure"); QueueExecutionResult.COMPLETE }
        queue.retryFailed()
        assertNotEquals(id, queue.state.value.waiting.first().id)
        assertEquals("one", (queue.state.value.waiting.first().payload as QueuePayload.Prompt).text)
        assertEquals(QueuePause.USER, queue.state.value.pause)
    }
    @Test fun activeFailureAtCapacityKeepsEveryAcceptedEntryUntilExplicitRetry() = runTest {
        val store = MemoryChatQueueStore(); val queue = ChatQueueCoordinator(store); queue.restore()
        queue.enqueue(QueuePayload.Prompt("synthetic active"))
        val gate = CompletableDeferred<Unit>()
        val active = async { queue.runNext({ true }) { gate.await(); QueueExecutionResult.FAILED } }
        testScheduler.runCurrent()
        val activeId = queue.state.value.active!!.id
        repeat(10) { assertTrue(queue.enqueue(QueuePayload.Prompt("synthetic waiting $it"))) }
        val waitingIds = queue.state.value.waiting.map { it.id }
        gate.complete(Unit); active.await()
        assertEquals(activeId, queue.state.value.active?.id)
        assertEquals(waitingIds, queue.state.value.waiting.map { it.id })
        assertFalse(queue.enqueue(QueuePayload.Prompt("synthetic overflow")))
        queue.retryFailed()
        assertEquals(activeId, queue.state.value.active?.id)
        queue.cancel(waitingIds.last()); queue.retryFailed()
        assertEquals(10, queue.state.value.waiting.size)
        assertNull(queue.state.value.active)
        assertNotEquals(activeId, queue.state.value.waiting.first().id)
        assertEquals(waitingIds.dropLast(1), queue.state.value.waiting.drop(1).map { it.id })
        assertEquals(QueuePause.USER, queue.state.value.pause)
    }

    @Test fun lateProgressCannotOverwriteHeldUnknownOutcome() = runTest {
        val queue = ChatQueueCoordinator(MemoryChatQueueStore()); queue.restore()
        queue.enqueue(QueuePayload.Prompt("synthetic active"))
        val id = queue.state.value.waiting.single().id
        queue.runNext({ true }) { QueueExecutionResult.UNKNOWN }
        queue.progress(id, QueueItemState.STREAMING)
        assertEquals(QueueItemState.UNKNOWN, queue.state.value.active?.state)
        assertEquals(QueuePause.UNKNOWN, queue.state.value.pause)
    }

    @Test fun ownerReadFailurePausesWithoutExecutingOrThrowing() = runTest {
        val store = MemoryChatQueueStore(); val queue = ChatQueueCoordinator(store); queue.restore()
        queue.enqueue(QueuePayload.Prompt("synthetic")); store.throwOwner = true
        queue.runNext({ true }) { fail("executed with unavailable owner"); QueueExecutionResult.COMPLETE }
        assertEquals(QueuePause.STORAGE, queue.state.value.pause)
        assertFalse(queue.resume())
        assertEquals(1, queue.state.value.waiting.size)
    }
    @Test fun codecRejectsFutureSchemasDuplicateIdsAndOversizedEntries() = runTest {
        assertThrows(IllegalArgumentException::class.java) { ChatQueueCodec.decode("{\"schema\":2,\"waiting\":[]}") }
        val item = QueueItem(payload = QueuePayload.Prompt("synthetic"))
        assertThrows(IllegalArgumentException::class.java) { ChatQueueCodec.decode(ChatQueueCodec.encode(ChatQueueState(listOf(item, item)))) }
        val queue = ChatQueueCoordinator(MemoryChatQueueStore()); queue.restore()
        assertFalse(queue.enqueue(QueuePayload.Prompt("x".repeat(ChatQueueState.MAX_TEXT_BYTES + 1))))
        assertFalse(queue.state.value.toString().contains("synthetic"))
    }
}
