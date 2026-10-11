package com.hermes.bot.feature.chat

import com.hermes.bot.data.queue.*
import com.hermes.bot.feature.chat.components.QueueControl
import com.hermes.bot.feature.chat.components.queueControl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatQueuePresentationTest {
    @Test fun waitingQueueDisablesAttachmentsWithoutComposerCaption() {
        val state = ChatUiState(runtimeSessionId = "synthetic-runtime", isInitialLoading = false,
            queueEnabled = true, headerConnectionStatus = com.hermes.bot.domain.ConnectionStatus.CONNECTED,
            queue = ChatQueueState(waiting = listOf(QueueItem(payload = QueuePayload.Prompt("Synthetic prompt")))))
        assertFalse(state.canAttachImage)
        assertFalse(state.canAttachFile)
        assertNull(state.attachmentDisabledReason)
    }

    @Test fun restoredWaitingIntentOffersResumeWithoutDispatching() = runTest {
        val store = MemoryChatQueueStore()
        val first = ChatQueueCoordinator(store)
        first.restore()
        first.enqueue(QueuePayload.Prompt("Synthetic waiting prompt"))
        val restored = ChatQueueCoordinator(store)
        restored.restore()

        assertEquals(QueueControl.RESUME, queueControl(restored.state.value, false))
        assertEquals(1, restored.state.value.waiting.size)
        var writes = 0
        restored.runNext({ true }) { writes++; QueueExecutionResult.COMPLETE }
        assertEquals(0, writes)

        assertTrue(restored.resume())
        assertEquals(QueueControl.NONE, queueControl(restored.state.value, false))
        restored.runNext({ true }) { writes++; QueueExecutionResult.COMPLETE }
        assertEquals(1, writes)
        assertEquals(QueueControl.NONE, queueControl(restored.state.value, false))
    }

    @Test fun stoppingStreamKeepsWaitingWorkVisibleAndRequiresResume() = runTest {
        val queue = ChatQueueCoordinator(MemoryChatQueueStore())
        queue.restore()
        queue.enqueue(QueuePayload.Prompt("Synthetic waiting prompt"))
        val id = queue.state.value.waiting.single().id
        assertEquals(QueueControl.NONE, queueControl(queue.state.value, true))
        queue.pause(QueuePause.STOPPED)
        assertEquals(id, queue.state.value.waiting.single().id)
        assertEquals(QueueControl.RESUME, queueControl(queue.state.value, false))
        queue.runNext({ true }) { fail("Stopped work must not dispatch"); QueueExecutionResult.COMPLETE }
    }

    @Test fun uncertainActiveSubmissionRequiresReviewEvenWithWaitingPills() = runTest {
        val store = MemoryChatQueueStore()
        store.json = ChatQueueCodec.encode(ChatQueueState(
            active = QueueItem(payload = QueuePayload.Prompt("Synthetic active prompt"), state = QueueItemState.STREAMING),
            waiting = listOf(QueueItem(payload = QueuePayload.Prompt("Synthetic waiting prompt")))
        ))
        val restored = ChatQueueCoordinator(store)
        restored.restore()
        assertEquals(QueueItemState.UNKNOWN, restored.state.value.active?.state)
        assertEquals(1, restored.state.value.waiting.size)
        assertEquals(QueueControl.REVIEW, queueControl(restored.state.value, false))
        assertEquals(QueueControl.REVIEW, queueControl(restored.state.value, true))
        assertFalse(restored.resume())
        restored.runNext({ true }) { fail("Uncertain submission must not replay"); QueueExecutionResult.COMPLETE }
    }

    @Test fun failedSubmissionKeepsReviewUntilExplicitRetryCreatesNewIntent() = runTest {
        val queue = ChatQueueCoordinator(MemoryChatQueueStore())
        queue.restore()
        queue.enqueue(QueuePayload.Prompt("Synthetic failed prompt"))
        val id = queue.state.value.waiting.single().id
        queue.runNext({ true }) { QueueExecutionResult.FAILED }
        assertEquals(QueueControl.REVIEW, queueControl(queue.state.value, false))
        queue.retryFailed()
        assertNotEquals(id, queue.state.value.waiting.single().id)
        assertEquals(QueueControl.RESUME, queueControl(queue.state.value, false))
        queue.runNext({ true }) { fail("New retry intent still requires resume"); QueueExecutionResult.COMPLETE }
    }

    @Test fun editingRestoredPromptTemporarilyHidesResumeAndPreservesPause() = runTest {
        val store = MemoryChatQueueStore()
        store.json = ChatQueueCodec.encode(ChatQueueState(waiting = listOf(
            QueueItem(payload = QueuePayload.Prompt("Synthetic original prompt")))))
        val queue = ChatQueueCoordinator(store)
        queue.restore()
        queue.beginEdit(queue.state.value.waiting.single().id)
        queue.editText("Synthetic discarded edit")
        assertEquals(QueueControl.NONE, queueControl(queue.state.value, false))
        queue.finishEdit(false)
        assertEquals("Synthetic original prompt", (queue.state.value.waiting.single().payload as QueuePayload.Prompt).text)
        assertEquals(QueueControl.RESUME, queueControl(queue.state.value, false))
    }

    @Test fun storageFailureKeepsAcceptedIntentAndOffersReview() = runTest {
        val store = MemoryChatQueueStore()
        val queue = ChatQueueCoordinator(store)
        queue.restore()
        queue.enqueue(QueuePayload.Prompt("Synthetic retained prompt"))
        store.failWrite = true
        queue.cancel(queue.state.value.waiting.single().id)
        assertEquals(1, queue.state.value.waiting.size)
        assertEquals(QueueControl.REVIEW, queueControl(queue.state.value, false))
        assertEquals(QueueControl.REVIEW, queueControl(queue.state.value, true))
    }

    @Test fun emptyQueueHasNoControlEvenWhenAnOldPauseRemains() {
        assertEquals(QueueControl.NONE, queueControl(ChatQueueState(pause = QueuePause.RESTORED), false))
        assertEquals(QueueControl.NONE, queueControl(ChatQueueState(pause = QueuePause.STOPPED), true))
    }
}
