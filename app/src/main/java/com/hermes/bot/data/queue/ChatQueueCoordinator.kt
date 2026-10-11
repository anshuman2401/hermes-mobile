package com.hermes.bot.data.queue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns local intent; only the existing scoped writers may execute entries. */
class ChatQueueCoordinator(private val store: ChatQueueStore) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ChatQueueState())
    val state = mutable.asStateFlow()
    private var pauseBeforeEdit: QueuePause? = null

    private suspend fun ownerCurrent(): Boolean = try { store.isCurrent() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(pause = QueuePause.STORAGE, error = "Queue storage unavailable.")
            false
        }

    private suspend fun save(next: ChatQueueState): Boolean {
        val ok = try { store.write(next) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        if (ok) mutable.value = next.copy(error = null)
        else mutable.value = mutable.value.copy(pause = QueuePause.STORAGE, error = "Could not save the queue. Your text has been kept.")
        return ok
    }
    suspend fun restore() = mutex.withLock {
        if (mutable.value.loaded && mutable.value.pause != QueuePause.STORAGE) return@withLock
        try {
            val saved = store.read()
            val next = saved?.copy(active = saved.active?.withState(QueueItemState.UNKNOWN),
                pause = if (saved.active != null) QueuePause.UNKNOWN else if (saved.waiting.isNotEmpty()) QueuePause.RESTORED else null,
                loaded = true, edit = null) ?: ChatQueueState(loaded = true)
            save(next)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = ChatQueueState(loaded = true, pause = QueuePause.STORAGE, error = "Queue storage unavailable.") }
    }
    suspend fun enqueue(payload: QueuePayload): Boolean = mutex.withLock {
        val current = mutable.value
        val text = when (payload) { is QueuePayload.Prompt -> payload.text; is QueuePayload.Command -> payload.invocation.text }
        if (!current.loaded || current.isFull || text.isBlank() || text.toByteArray().size > ChatQueueState.MAX_TEXT_BYTES) {
            mutable.value = current.copy(error = if (current.isFull) "Queue full (10). Cancel an item before adding another." else "Enter a valid message or command.")
            return@withLock false
        }
        save(current.copy(waiting = current.waiting + QueueItem(payload = payload)))
    }
    suspend fun notice(message: String) = mutex.withLock { mutable.value = mutable.value.copy(error = message) }
    suspend fun pause(reason: QueuePause) = mutex.withLock {
        val current = mutable.value
        if (current.edit != null) {
            pauseBeforeEdit = reason
            return@withLock
        }
        if (current.loaded && current.pause != reason) save(current.copy(pause = reason))
    }
    suspend fun resume(): Boolean = mutex.withLock {
        val current = mutable.value
        if (!current.loaded || current.edit != null || current.active?.state in setOf(QueueItemState.UNKNOWN, QueueItemState.FAILED, QueueItemState.CONFIRMING) || !ownerCurrent()) return@withLock false
        save(current.copy(pause = null))
    }
    suspend fun cancel(id: String) = mutex.withLock {
        val current = mutable.value
        if (current.waiting.none { it.id == id }) return@withLock
        save(current.copy(waiting = current.waiting.filterNot { it.id == id },
            edit = current.edit?.takeUnless { it.id == id }, pause = if (current.edit?.id == id) pauseBeforeEdit else current.pause))
    }
    suspend fun beginEdit(id: String) = mutex.withLock {
        val current = mutable.value
        if (current.edit != null) return@withLock
        val item = current.waiting.find { it.id == id } ?: return@withLock
        val prompt = item.payload as? QueuePayload.Prompt ?: return@withLock
        pauseBeforeEdit = current.pause
        save(current.copy(pause = QueuePause.EDITING, edit = QueueEdit(id, prompt.text)))
    }
    suspend fun editText(text: String) = mutex.withLock {
        val current = mutable.value
        current.edit?.let { mutable.value = current.copy(edit = QueueEdit(it.id, text)) }
    }
    suspend fun finishEdit(commit: Boolean) = mutex.withLock {
        val current = mutable.value
        val edit = current.edit ?: return@withLock
        if (commit && (edit.text.isBlank() || edit.text.toByteArray().size > ChatQueueState.MAX_TEXT_BYTES || edit.text.trim().startsWith("/"))) {
            mutable.value = current.copy(error = "Enter a nonempty text prompt; commands cannot replace a queued prompt.")
            return@withLock
        }
        save(current.copy(waiting = if (commit) current.waiting.map {
            if (it.id == edit.id) QueueItem(it.id, QueuePayload.Prompt(edit.text.trim())) else it
        } else current.waiting, edit = null, pause = pauseBeforeEdit))
    }
    suspend fun canContinue(id: String): Boolean = mutex.withLock {
        mutable.value.active?.id == id && mutable.value.active?.state !in setOf(QueueItemState.FAILED, QueueItemState.UNKNOWN) &&
            mutable.value.pause != QueuePause.STORAGE && ownerCurrent()
    }
    suspend fun progress(id: String, state: QueueItemState, pendingTurnId: String? = null) = mutex.withLock {
        val current = mutable.value
        if (current.active?.id == id && current.active.state !in setOf(QueueItemState.FAILED, QueueItemState.UNKNOWN))
            save(current.copy(active = current.active.withState(state, pendingTurnId ?: current.active.pendingTurnId)))
    }
    /** Only the owning ViewModel may supply authoritative completion evidence. */
    suspend fun resolveCompletedPrompt(id: String): Boolean = mutex.withLock {
        val current = mutable.value
        if (current.active?.id != id || current.active.payload !is QueuePayload.Prompt ||
            current.active.state != QueueItemState.UNKNOWN || !ownerCurrent()) return@withLock false
        save(current.copy(active = null, pause = current.pause.takeUnless {
            it == QueuePause.UNKNOWN || it == QueuePause.DISCONNECTED
        }))
    }
    suspend fun dismissActive() = mutex.withLock {
        val current = mutable.value
        if (current.active?.state in setOf(QueueItemState.FAILED, QueueItemState.UNKNOWN, QueueItemState.CONFIRMING))
            save(current.copy(active = null, pause = QueuePause.USER))
    }
    suspend fun retryFailed() = mutex.withLock {
        val current = mutable.value
        val item = current.active?.takeIf { it.state == QueueItemState.FAILED } ?: return@withLock
        if (current.waiting.size >= ChatQueueState.MAX_WAITING) {
            mutable.value = current.copy(error = "Cancel a waiting item before retrying.")
            return@withLock
        }
        save(current.copy(active = null, waiting = listOf(QueueItem(payload = item.payload)) + current.waiting, pause = QueuePause.USER))
    }
    suspend fun runNext(eligible: () -> Boolean, execute: suspend (QueueItem) -> QueueExecutionResult) {
        val item = mutex.withLock {
            val current = mutable.value
            if (!current.loaded || current.pause != null || current.active != null || current.waiting.isEmpty() || !eligible() || !ownerCurrent()) return
            val first = current.waiting.first()
            val active = first.withState(if (first.payload is QueuePayload.Command) QueueItemState.RUNNING_COMMAND else QueueItemState.SENDING)
            // A restored active item is uncertain, even if the process died before the write.
            if (!save(current.copy(waiting = current.waiting.drop(1), active = active))) return
            active
        }
        val result = try {
            if (eligible() && ownerCurrent()) execute(item) else QueueExecutionResult.FAILED
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { complete(item.id, QueueExecutionResult.UNKNOWN) }
            throw cancelled
        } catch (_: Exception) { QueueExecutionResult.UNKNOWN }
        complete(item.id, result)
    }
    private suspend fun complete(id: String, result: QueueExecutionResult) = mutex.withLock {
        val current = mutable.value
        if (current.active?.id != id) return@withLock
        save(when (result) {
            QueueExecutionResult.COMPLETE -> current.copy(active = null)
            QueueExecutionResult.FAILED -> current.copy(active = current.active.withState(QueueItemState.FAILED), pause = QueuePause.FAILED)
            QueueExecutionResult.UNKNOWN -> current.copy(active = current.active.withState(QueueItemState.UNKNOWN), pause = QueuePause.UNKNOWN)
            QueueExecutionResult.CONFIRMATION -> current.copy(active = current.active.withState(QueueItemState.CONFIRMING), pause = QueuePause.CONFIRMATION)
        })
    }
}
