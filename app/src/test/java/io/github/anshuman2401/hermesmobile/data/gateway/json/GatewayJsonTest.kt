package io.github.anshuman2401.hermesmobile.data.gateway.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayJsonTest {

    @Test
    fun parse_primitivesAndWhitespace() {
        val parsed = GatewayJson.parse("""
            {
                "str": "hello world",
                "num": 42,
                "float": 3.1415,
                "boolTrue": true,
                "boolFalse": false,
                "nil": null
            }
        """.trimIndent()) as JsonObject

        assertEquals("hello world", parsed["str"]?.asStringOrNull())
        assertEquals(42L, parsed["num"]?.asLongOrNull())
        assertEquals(3.1415, parsed["float"]?.asDoubleOrNull() ?: 0.0, 0.0001)
        assertEquals(true, parsed["boolTrue"]?.asBooleanOrNull())
        assertEquals(false, parsed["boolFalse"]?.asBooleanOrNull())
        assertTrue(parsed["nil"] is JsonNull)
    }

    @Test
    fun parse_escapedStrings() {
        val json = """{"escaped": "line1\nline2\t\"quoted\"\\slash"}"""
        val parsed = GatewayJson.parse(json) as JsonObject
        assertEquals("line1\nline2\t\"quoted\"\\slash", parsed["escaped"]?.asStringOrNull())
    }

    @Test
    fun parse_unicodeEscape() {
        val json = """{"unicode": "\u0048\u0065\u006c\u006c\u006f"}"""
        val parsed = GatewayJson.parse(json) as JsonObject
        assertEquals("Hello", parsed["unicode"]?.asStringOrNull())
    }

    @Test
    fun parse_arraysAndNestedObjects() {
        val json = """
            {
                "items": [1, 2, {"nested": "value"}],
                "emptyArr": [],
                "emptyObj": {}
            }
        """.trimIndent()
        val parsed = GatewayJson.parse(json) as JsonObject
        val items = parsed["items"]?.asArrayOrNull()
        assertNotNull(items)
        assertEquals(3, items?.list?.size)
        assertEquals(1L, items?.list?.get(0)?.asLongOrNull())
        val nested = items?.list?.get(2)?.asObjectOrNull()
        assertEquals("value", nested?.get("nested")?.asStringOrNull())
    }

    @Test
    fun parseOrNull_returnsNullOnMalformed() {
        assertNull(GatewayJson.parseOrNull("{unclosed"))
        assertNull(GatewayJson.parseOrNull("[1, 2,"))
        assertNull(GatewayJson.parseOrNull("random text"))
        assertNull(GatewayJson.parseOrNull(""))
    }

    @Test
    fun serialize_roundTrip() {
        val map = mapOf(
            "jsonrpc" to "2.0",
            "id" to "android-1-1",
            "params" to mapOf(
                "session_id" to "sid-123",
                "text" to "Hello \"world\"\nNext line"
            )
        )
        val jsonVal = GatewayJson.fromAny(map)
        val serialized = GatewayJson.serialize(jsonVal)
        val reParsed = GatewayJson.parse(serialized) as JsonObject

        assertEquals("2.0", reParsed["jsonrpc"]?.asStringOrNull())
        assertEquals("android-1-1", reParsed["id"]?.asStringOrNull())
        val params = reParsed["params"]?.asObjectOrNull()
        assertEquals("sid-123", params?.get("session_id")?.asStringOrNull())
        assertEquals("Hello \"world\"\nNext line", params?.get("text")?.asStringOrNull())
    }
}
