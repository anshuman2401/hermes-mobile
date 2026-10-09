package io.github.anshuman2401.hermesmobile.data.gateway.fake

import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject

/**
 * Sanitized host-shape fixtures seeded from INT-001 certified shapes on evidence commit 476fa2c.
 * Loaded dynamically from tracked test resources under fixtures/v0.21.1/.
 * Zero real host literals, IPs, tokens, or credentials.
 */
object FakeGatewayFixtures {

    fun loadFixture(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/v0.21.1/$name")
            ?: error("Fixture not found in test resources: fixtures/v0.21.1/$name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun extractResult(fixtureName: String, parentKey: String = "response"): String {
        val json = loadFixture(fixtureName)
        val parsed = GatewayJson.parse(json) as JsonObject
        val parent = parsed[parentKey] as? JsonObject ?: parsed
        val result = parent["result"] ?: error("No result in $fixtureName at $parentKey")
        return GatewayJson.serialize(result)
    }

    val READY_FRAME: String by lazy { loadFixture("gateway-ready.json").trim() }

    val TEST_EPOCH_1: String by lazy {
        val parsed = GatewayJson.parse(READY_FRAME) as JsonObject
        val params = parsed["params"] as JsonObject
        val payload = params["payload"] as JsonObject
        payload["replay_epoch"]?.asStringOrNull() ?: "<redacted-replay-epoch-uuid>"
    }

    const val TEST_EPOCH_2 = "test-replay-epoch-uuid-2"
    const val TEST_RUNTIME_SID = "TEST-RUNTIME-SID-1"

    val CAPABILITIES_SUCCESS: String by lazy {
        extractResult("gateway-capabilities.json")
    }

    const val CAPABILITIES_INCOMPATIBLE = """{"per_session_exclusive_submit": false}"""

    val SESSION_LIST_RESPONSE: String by lazy {
        extractResult("session-list.json")
    }

    val SESSION_CREATE_RESPONSE: String by lazy {
        extractResult("session-create.json")
    }

    val SESSION_RESUME_UNPERSISTED: String by lazy {
        extractResult("session-resume.json", parentKey = "response_live_unpersisted")
    }

    val SESSION_RESUME_PERSISTED: String by lazy {
        extractResult("session-resume.json", parentKey = "response_persisted")
    }

    val SESSION_RESUME_NOT_FOUND_ERROR: String by lazy {
        val json = loadFixture("session-resume.json")
        val parsed = GatewayJson.parse(json) as JsonObject
        val neg = parsed["negative_response_not_found"] as JsonObject
        val err = neg["error"] as JsonObject
        GatewayJson.serialize(err)
    }

    val PROMPT_SUBMIT_RESPONSE: String by lazy {
        extractResult("prompt-stream.json", parentKey = "initial_rpc_response")
    }

    val REPLAY_SINCE_RESPONSE: String by lazy {
        extractResult("replay-since.json")
    }

    val REPLAY_TRUNCATED_RESPONSE: String by lazy {
        """
        {
          "count": 1,
          "epoch": "$TEST_EPOCH_1",
          "latest_seq": 50,
          "truncated": true,
          "events": [
            {
              "type": "message.delta",
              "payload": { "text": "gap" },
              "seq": 50,
              "session_id": "TEST-RUNTIME-SID-1"
            }
          ]
        }
        """.trimIndent()
    }

    val REPLAY_EPOCH_MISMATCH_RESPONSE: String by lazy {
        """
        {
          "count": 1,
          "epoch": "different-epoch-uuid-999",
          "latest_seq": 10,
          "truncated": false,
          "events": [
            {
              "type": "message.delta",
              "payload": { "text": "mismatch" },
              "seq": 10,
              "session_id": "TEST-RUNTIME-SID-1"
            }
          ]
        }
        """.trimIndent()
    }
    val SESSION_LIST_RESPONSE_PR: String by lazy {
        """
        {
          "sessions": [
            {
              "id": "sess-test-001",
              "title": "android-test-session-1",
              "preview": "Test preview snippet",
              "started_at": 1757817000.0,
              "message_count": 2,
              "source": "android"
            },
            {
              "id": "sess-test-002",
              "title": "android-test-session-2",
              "preview": "Another preview",
              "started_at": 1757816500.0,
              "message_count": 14,
              "source": "tui"
            }
          ]
        }
        """.trimIndent()
    }

    val SESSION_CREATE_RESPONSE_PR: String by lazy {
        """
        {
          "session_id": "runtime-sid-created-1",
          "stored_session_id": "durable-key-created-1",
          "message_count": 0,
          "messages": [],
          "info": {
            "model": "sanitized-test-model",
            "lazy": true,
            "profile_name": "default"
          }
        }
        """.trimIndent()
    }

    val READY_FRAME_NO_HEARTBEAT_NO_CHANGE_EVENTS: String by lazy {
        """
        {
          "jsonrpc": "2.0",
          "method": "event",
          "params": {
            "type": "gateway.ready",
            "payload": {
              "skin": {
                "theme": "default",
                "custom_css": null
              },
              "change_events": false,
              "heartbeat": false,
              "replay_epoch": "$TEST_EPOCH_1"
            }
          }
        }
        """.trimIndent()
    }

    val REPLAY_COUNT_INCONSISTENCY_RESPONSE: String by lazy {
        """
        {
          "count": 2,
          "epoch": "$TEST_EPOCH_1",
          "latest_seq": 1,
          "truncated": false,
          "events": [
            {
              "type": "message.delta",
              "payload": {
                "text": "only-one"
              },
              "seq": 1,
              "session_id": "runtime-sid-1"
            }
          ]
        }
        """.trimIndent()
    }

    val REPLAY_LATEST_SEQ_INCONSISTENCY_RESPONSE: String by lazy {
        """
        {
          "count": 1,
          "epoch": "$TEST_EPOCH_1",
          "latest_seq": 5,
          "truncated": false,
          "events": [
            {
              "type": "message.delta",
              "payload": {
                "text": "seq-one"
              },
              "seq": 1,
              "session_id": "runtime-sid-1"
            }
          ]
        }
        """.trimIndent()
    }

    val REPLAY_WITH_UNKNOWN_EVENT_RESPONSE: String by lazy {
        """
        {
          "count": 1,
          "epoch": "$TEST_EPOCH_1",
          "latest_seq": 1,
          "truncated": false,
          "events": [
            {
              "type": "custom.future.telemetry",
              "payload": {
                "metric": "latency",
                "val": 42
              },
              "seq": 1,
              "session_id": "runtime-sid-1"
            }
          ]
        }
        """.trimIndent()
    }
}
