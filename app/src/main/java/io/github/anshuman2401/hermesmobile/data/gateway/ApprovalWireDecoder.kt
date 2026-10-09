package io.github.anshuman2401.hermesmobile.data.gateway

import io.github.anshuman2401.hermesmobile.data.approval.*
import io.github.anshuman2401.hermesmobile.data.gateway.json.*

/** No partial commands: an oversized/malformed request is never actionable. */
internal object ApprovalWireDecoder {
    fun id(value: JsonValue?): String? = value?.asStringOrNull()?.takeIf {
        it.isNotBlank() && it.length <= 256 && it.none { c -> c.isISOControl() } }
    fun request(params: JsonObject, serverId: String? = null, expectedRuntime: String? = null): ApprovalRequest? {
        val runtime = id(params["session_id"]) ?: expectedRuntime ?: return null
        if (expectedRuntime != null && runtime != expectedRuntime) return null
        val requestId = id(params["request_id"]) ?: return null
        for (key in listOf("smart_denied", "allow_session", "allow_permanent")) {
            if (params.map.containsKey(key) && params[key] !is JsonBoolean) return null
        }
        for (key in listOf("command", "description")) {
            if (params.map.containsKey(key) && params[key] !is JsonString) return null
        }
        val command = params["command"]?.asStringOrNull() ?: ""
        val description = params["description"]?.asStringOrNull() ?: ""
        if (command.length > 16384 || description.length > 16384) return null
        val details = if (command.isBlank() || command.trim().startsWith("<")) description else command
        if (details.isBlank()) return null
        val allowed = if (params.map.containsKey("choices")) {
            val array = params["choices"] as? JsonArray ?: return null
            if (array.list.size > 4) return null
            array.list.map { value -> when (value.asStringOrNull()) {
                "once" -> ApprovalChoice.ONCE; "session" -> ApprovalChoice.SESSION
                "always" -> ApprovalChoice.ALWAYS; "deny" -> ApprovalChoice.DENY; else -> return null
            } }.toSet()
        } else buildSet {
            add(ApprovalChoice.ONCE); add(ApprovalChoice.DENY)
            if (params["smart_denied"]?.asBooleanOrNull() != true && params["allow_session"]?.asBooleanOrNull() != false) {
                add(ApprovalChoice.SESSION)
                if (params["allow_permanent"]?.asBooleanOrNull() != false) add(ApprovalChoice.ALWAYS)
            }
        }
        val choices = allowed.filterNot { choice ->
            (choice == ApprovalChoice.ALWAYS && params["allow_permanent"]?.asBooleanOrNull() == false) ||
            (choice in setOf(ApprovalChoice.SESSION, ApprovalChoice.ALWAYS) &&
                (params["smart_denied"]?.asBooleanOrNull() == true || params["allow_session"]?.asBooleanOrNull() == false))
        }.toSet()
        if (choices.isEmpty()) return null
        return ApprovalRequest(requestId, runtime, GatewayRedactor.redactString(details), choices, serverId)
    }
    fun pending(root: JsonValue, runtime: String): List<ApprovalRequest>? {
        val array = (root as? JsonObject)?.get("approvals") as? JsonArray ?: return null
        if (array.list.size > 128) return null
        val requests = array.list.map { request(it as? JsonObject ?: return null, expectedRuntime = runtime) ?: return null }
        if (requests.map { it.requestId }.distinct().size != requests.size) return null
        return requests
    }
    fun choice(choice: ApprovalChoice) = when(choice) {
        ApprovalChoice.ONCE -> "once"; ApprovalChoice.SESSION -> "session"
        ApprovalChoice.ALWAYS -> "always"; ApprovalChoice.DENY -> "deny"
    }
}
