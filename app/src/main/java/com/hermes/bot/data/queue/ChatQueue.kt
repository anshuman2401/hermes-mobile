package com.hermes.bot.data.queue

import com.hermes.bot.data.commands.SlashInvocation
import java.util.UUID

sealed interface QueuePayload {
    class Prompt(val text: String) : QueuePayload { override fun toString() = "Prompt([redacted])" }
    class Command(val invocation: SlashInvocation) : QueuePayload {
        val completion: CommandCompletion = when (invocation.name) {
            "yolo" -> CommandCompletion.YOLO_STATE
            "model", "reasoning" -> CommandCompletion.SESSION_SETTING
            "compress", "compact" -> CommandCompletion.COMPRESSION
            else -> CommandCompletion.HOST_DIRECTIVE
        }
        override fun toString() = "Command([redacted])"
    }
}
enum class CommandCompletion { YOLO_STATE, SESSION_SETTING, COMPRESSION, HOST_DIRECTIVE }
enum class QueueItemState { WAITING, SENDING, STREAMING, RUNNING_COMMAND, CONFIRMING, FAILED, UNKNOWN }
enum class QueuePause { USER, RESTORED, BACKGROUND, DISCONNECTED, STOPPED, FAILED, UNKNOWN, EDITING, STORAGE, CONFIRMATION }
class QueueItem(val id: String = UUID.randomUUID().toString(), val payload: QueuePayload,
    val state: QueueItemState = QueueItemState.WAITING, val pendingTurnId: String? = null) {
    fun withState(state: QueueItemState, pendingTurnId: String? = this.pendingTurnId) = QueueItem(id, payload, state, pendingTurnId)
    override fun toString() = "QueueItem([redacted], state=$state)"
}
class QueueEdit(val id: String, val text: String) { override fun toString() = "QueueEdit([redacted])" }
data class ChatQueueState(
    val waiting: List<QueueItem> = emptyList(), val active: QueueItem? = null,
    val pause: QueuePause? = null, val loaded: Boolean = false, val edit: QueueEdit? = null,
    val error: String? = null
) {
    val occupiedSlots: Int get() = waiting.size + if (active?.state in setOf(QueueItemState.FAILED, QueueItemState.UNKNOWN)) 1 else 0
    val isFull: Boolean get() = occupiedSlots >= MAX_WAITING
    val visible: Boolean get() = waiting.isNotEmpty() || active != null || edit != null || error != null
    override fun toString() = "ChatQueueState(waiting=${waiting.size}, active=${active?.state}, pause=$pause)"
    companion object { const val MAX_WAITING = 10; const val MAX_TEXT_BYTES = 128 * 1024 }
}
enum class QueueExecutionResult { COMPLETE, FAILED, UNKNOWN, CONFIRMATION }
interface ChatQueueStore {
    suspend fun read(): ChatQueueState?
    suspend fun write(state: ChatQueueState): Boolean
    suspend fun isCurrent(): Boolean
}
