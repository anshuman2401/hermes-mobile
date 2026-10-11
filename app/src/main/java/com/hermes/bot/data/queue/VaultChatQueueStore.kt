package com.hermes.bot.data.queue

import com.hermes.bot.data.local.*
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.commands.SlashInvocation

/** Never changes owner after capture. No runtime IDs or credentials enter the record. */
class VaultChatQueueStore(private val vault: VaultStorage, private val storedId: () -> String?) : ChatQueueStore {
    private var partition: CacheAccessPartition? = null
    private var conversation: String? = null
    private var expectedJson: String? = null
    override suspend fun read(): ChatQueueState? {
        val owner = (vault.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition
            ?: error("Queue owner unavailable")
        val id = storedId()?.takeIf { it.isNotBlank() } ?: error("Conversation unavailable")
        if (partition != null) check(partition == owner && conversation == id)
        partition = owner; conversation = id
        val json = vault.readChatQueue(owner, id)
        val state = json?.let(ChatQueueCodec::decode)
        expectedJson = json
        return state
    }
    override suspend fun isCurrent(): Boolean = partition != null && conversation == storedId() &&
        (vault.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition == partition
    override suspend fun write(state: ChatQueueState): Boolean {
        val owner = partition ?: return false
        val id = conversation ?: return false
        val json = ChatQueueCodec.encode(state)
        val saved = isCurrent() && vault.writeChatQueue(owner, id, json, expectedJson)
        if (saved) expectedJson = json
        return saved
    }
}

internal object ChatQueueCodec {
    fun encode(state: ChatQueueState): String {
        fun item(entry: QueueItem): Map<String, Any?> = linkedMapOf(
            "id" to entry.id, "state" to entry.state.name, "pendingTurnId" to entry.pendingTurnId,
            "kind" to if (entry.payload is QueuePayload.Prompt) "prompt" else "command",
            "text" to (entry.payload as? QueuePayload.Prompt)?.text,
            "name" to (entry.payload as? QueuePayload.Command)?.invocation?.name,
            "argument" to (entry.payload as? QueuePayload.Command)?.invocation?.argument)
        return GatewayJson.serialize(GatewayJson.fromAny(mapOf("schema" to 1,
            "waiting" to state.waiting.map(::item), "active" to state.active?.let(::item),
            "pause" to state.pause?.name)))
    }
    fun decode(json: String): ChatQueueState {
        require(json.toByteArray().size <= 2 * 1024 * 1024)
        val root = GatewayJson.parseOrNull(json)?.asObjectOrNull() ?: error("Invalid queue")
        require(root["schema"]?.asIntOrNull() == 1)
        fun item(value: com.hermes.bot.data.gateway.json.JsonValue): QueueItem {
            val obj = value.asObjectOrNull() ?: error("Invalid entry")
            val id = obj["id"]?.asStringOrNull()?.takeIf { it.isNotBlank() && it.length <= 128 } ?: error("Invalid identity")
            val payload = when (obj["kind"]?.asStringOrNull()) {
                "prompt" -> QueuePayload.Prompt(obj["text"]?.asStringOrNull() ?: error("Missing prompt"))
                "command" -> QueuePayload.Command(SlashInvocation(obj["name"]?.asStringOrNull() ?: error("Missing command"),
                    obj["argument"]?.asStringOrNull() ?: error("Missing arguments")))
                else -> error("Invalid kind")
            }
            val text = when (payload) { is QueuePayload.Prompt -> payload.text; is QueuePayload.Command -> payload.invocation.text }
            require(text.isNotBlank() && text.toByteArray().size <= ChatQueueState.MAX_TEXT_BYTES)
            return QueueItem(id, payload, QueueItemState.valueOf(obj["state"]?.asStringOrNull() ?: error("Missing state")),
                obj["pendingTurnId"]?.asStringOrNull())
        }
        val rows = root["waiting"]?.asArrayOrNull()?.list ?: error("Missing waiting entries")
        require(rows.size <= ChatQueueState.MAX_WAITING)
        val waiting = rows.map(::item)
        require(waiting.all { it.state == QueueItemState.WAITING })
        val active = root["active"]?.takeIf { it.asObjectOrNull() != null }?.let(::item)
        val ids = waiting.map { it.id } + listOfNotNull(active?.id)
        require(ids.toSet().size == ids.size)
        return ChatQueueState(waiting, active, root["pause"]?.asStringOrNull()?.let(QueuePause::valueOf))
    }
}
