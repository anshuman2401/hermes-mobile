package com.hermes.bot.feature.chat

import androidx.lifecycle.ViewModelStore
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.queue.QueueItemState
import com.hermes.bot.data.queue.VaultChatQueueStore
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real protocol/repository/partitioned vault with explicit software keys and synthetic transport only. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatQueueStoredHistoryTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun scopedStoredHistoryQueueWaitsForTerminalBeforeSecondProtocolWrite() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            var writes = 0
            f.transport.setRpcHandler("prompt.submit") { id, params ->
                assertEquals("synthetic-runtime", params["session_id"]?.asStringOrNull())
                writes++
                f.transport.respondSuccess(id, """{"status":"streaming"}""")
            }
            val ledger = PendingTurnLedger(f.vault)
            val chat = ChatViewModel("synthetic-session", sessionRepository = f.repository,
                gatewayProtocolAdapter = f.gateway, vaultStorage = f.vault, pendingTurnLedger = ledger,
                conversationCacheRepository = f.cache,
                chatQueueFactory = { id -> VaultChatQueueStore(f.vault, id) })
            val owner = ViewModelStore().apply { put("synthetic", chat) }
            try {
                chat.setQueueVisibility(true)
                f.awaitLoaded(chat)
                awaitState(chat) { it.queue.loaded }
                chat.updateDraft("Synthetic queued first"); chat.submitPrompt()
                val active = awaitState(chat) { it.queue.active?.state == QueueItemState.STREAMING }
                val firstTurn = active.queue.active!!.pendingTurnId!!
                chat.updateDraft("Synthetic queued second"); chat.submitPrompt()
                awaitState(chat) { it.queue.waiting.size == 1 }
                assertEquals(1, writes)
                f.transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"session_id":"synthetic-runtime","seq":1,"type":"message.complete","payload":{"status":"complete","text":"Synthetic queue final"}}}""")
                val next = awaitState(chat) { it.queue.active?.state == QueueItemState.STREAMING &&
                    it.queue.active?.pendingTurnId != firstTurn && it.queue.waiting.isEmpty() }
                assertEquals(2, writes)
                assertTrue(next.timeline.filterIsInstance<AssistantMessage>().any {
                    it.text == "Synthetic queue final" && it.state == AssistantMessageState.COMPLETE
                })
                assertEquals(com.hermes.bot.data.local.PendingTurnState.RESOLVED, ledger.getPendingTurn(firstTurn)?.state)
            } finally { owner.clear() }
        }
    }

    private suspend fun awaitState(chat: ChatViewModel, predicate: (ChatUiState) -> Boolean): ChatUiState =
        withContext(Dispatchers.Default) { withTimeout(5000) { chat.uiState.first(predicate) } }
}
