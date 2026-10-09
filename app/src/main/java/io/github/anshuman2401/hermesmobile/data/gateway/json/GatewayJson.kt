package io.github.anshuman2401.hermesmobile.data.gateway.json

/**
 * Pure Kotlin lightweight JSON AST and parser/serializer.
 * Zero external dependencies; fully deterministic on JVM unit tests and Android runtimes.
 */
sealed interface JsonValue {
    fun asObjectOrNull(): JsonObject? = this as? JsonObject
    fun asArrayOrNull(): JsonArray? = this as? JsonArray
    fun asStringOrNull(): String? = (this as? JsonString)?.value
    fun asBooleanOrNull(): Boolean? = (this as? JsonBoolean)?.value
    fun asDoubleOrNull(): Double? = when (this) {
        is JsonNumber -> value
        is JsonString -> value.toDoubleOrNull()
        else -> null
    }
    fun asLongOrNull(): Long? = when (this) {
        is JsonNumber -> if (isInteger) longValue else null
        is JsonString -> value.toLongOrNull()
        else -> null
    }
    fun asIntOrNull(): Int? = asLongOrNull()?.toInt()

    operator fun get(key: String): JsonValue? = (this as? JsonObject)?.map?.get(key)
    operator fun get(index: Int): JsonValue? = (this as? JsonArray)?.list?.getOrNull(index)

    fun toAny(): Any? = when (this) {
        is JsonNull -> null
        is JsonBoolean -> value
        is JsonString -> value
        is JsonNumber -> if (isInteger && longValue != null) {
            if (longValue in Int.MIN_VALUE..Int.MAX_VALUE) longValue.toInt() else longValue
        } else {
            value
        }
        is JsonArray -> list.map { it.toAny() }
        is JsonObject -> map.mapValues { it.value.toAny() }
    }
}

data class JsonObject(val map: Map<String, JsonValue>) : JsonValue {
    constructor(vararg pairs: Pair<String, JsonValue>) : this(pairs.toMap())
}

data class JsonArray(val list: List<JsonValue>) : JsonValue {
    constructor(vararg elements: JsonValue) : this(elements.toList())
}

data class JsonString(val value: String) : JsonValue
data class JsonNumber(
    val value: Double,
    val raw: String = value.toString(),
    val longValue: Long? = null,
    val isInteger: Boolean = false
) : JsonValue {
    constructor(longVal: Long) : this(
        value = longVal.toDouble(),
        raw = longVal.toString(),
        longValue = longVal,
        isInteger = true
    )

    constructor(doubleVal: Double, raw: String) : this(
        value = doubleVal,
        raw = raw,
        longValue = if (!raw.contains('.') && !raw.contains('e', ignoreCase = true)) raw.toLongOrNull() else null,
        isInteger = !raw.contains('.') && !raw.contains('e', ignoreCase = true) && raw.toLongOrNull() != null
    )
}
data class JsonBoolean(val value: Boolean) : JsonValue
data object JsonNull : JsonValue

class JsonException(message: String, val position: Int = -1) : RuntimeException("$message at position $position")

object GatewayJson {

    fun parse(json: String): JsonValue {
        val parser = Parser(json)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (parser.hasMore()) {
            throw JsonException("Unexpected trailing character '${parser.peek()}'", parser.position)
        }
        return value
    }

    fun parseOrNull(json: String): JsonValue? {
        return try {
            parse(json)
        } catch (_: Exception) {
            null
        }
    }

    fun serialize(value: JsonValue): String {
        val sb = StringBuilder()
        serializeValue(value, sb)
        return sb.toString()
    }

    fun fromAny(any: Any?): JsonValue {
        return when (any) {
            null -> JsonNull
            is JsonValue -> any
            is Boolean -> JsonBoolean(any)
            is Long -> JsonNumber(any)
            is Int -> JsonNumber(any.toLong())
            is Short -> JsonNumber(any.toLong())
            is Byte -> JsonNumber(any.toLong())
            is Number -> {
                val str = any.toString()
                val isInt = !str.contains('.') && !str.contains('e', ignoreCase = true)
                val l = if (isInt) str.toLongOrNull() else null
                JsonNumber(any.toDouble(), str, l, isInt && l != null)
            }
            is String -> JsonString(any)
            is List<*> -> JsonArray(any.map { fromAny(it) })
            is Map<*, *> -> JsonObject(any.entries.associate { it.key.toString() to fromAny(it.value) })
            else -> JsonString(any.toString())
        }
    }

    private fun serializeValue(value: JsonValue, sb: StringBuilder) {
        when (value) {
            is JsonNull -> sb.append("null")
            is JsonBoolean -> sb.append(value.value)
            is JsonString -> {
                sb.append('"')
                escapeString(value.value, sb)
                sb.append('"')
            }
            is JsonNumber -> sb.append(value.raw)
            is JsonArray -> {
                sb.append('[')
                value.list.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    serializeValue(item, sb)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in value.map) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"')
                    escapeString(k, sb)
                    sb.append("\":")
                    serializeValue(v, sb)
                }
                sb.append('}')
            }
        }
    }

    private fun escapeString(s: String, sb: StringBuilder) {
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
    }

    private class Parser(private val src: String) {
        companion object {
            const val MAX_DEPTH = 64
            const val MAX_STRING_LENGTH = 2 * 1024 * 1024 // 2 MB
            const val MAX_CONTAINER_SIZE = 10_000
        }

        var position = 0

        fun hasMore(): Boolean = position < src.length
        fun peek(): Char = src[position]

        fun skipWhitespace() {
            while (hasMore()) {
                val c = src[position]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    position++
                } else {
                    break
                }
            }
        }

        fun parseValue(depth: Int = 0): JsonValue {
            if (depth > MAX_DEPTH) {
                throw JsonException("Max JSON nesting depth ($MAX_DEPTH) exceeded", position)
            }
            skipWhitespace()
            if (!hasMore()) throw JsonException("Unexpected end of input", position)
            return when (val c = peek()) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonString(parseString())
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonException("Unexpected character '$c'", position)
            }
        }

        fun parseObject(depth: Int = 1): JsonObject {
            expect('{')
            skipWhitespace()
            val map = LinkedHashMap<String, JsonValue>()
            if (hasMore() && peek() == '}') {
                position++
                return JsonObject(map)
            }
            var closed = false
            while (hasMore()) {
                if (map.size >= MAX_CONTAINER_SIZE) {
                    throw JsonException("Max JSON object keys ($MAX_CONTAINER_SIZE) exceeded", position)
                }
                skipWhitespace()
                if (peek() != '"') throw JsonException("Expected string key in object", position)
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue(depth)
                map[key] = value
                skipWhitespace()
                if (hasMore() && peek() == ',') {
                    position++
                    skipWhitespace()
                    continue
                } else if (hasMore() && peek() == '}') {
                    position++
                    closed = true
                    break
                } else {
                    throw JsonException("Expected ',' or '}' in object", position)
                }
            }
            if (!closed) throw JsonException("Unclosed object, expected '}'", position)
            return JsonObject(map)
        }

        fun parseArray(depth: Int = 1): JsonArray {
            expect('[')
            skipWhitespace()
            val list = ArrayList<JsonValue>()
            if (hasMore() && peek() == ']') {
                position++
                return JsonArray(list)
            }
            var closed = false
            while (hasMore()) {
                if (list.size >= MAX_CONTAINER_SIZE) {
                    throw JsonException("Max JSON array size ($MAX_CONTAINER_SIZE) exceeded", position)
                }
                val value = parseValue(depth)
                list.add(value)
                skipWhitespace()
                if (hasMore() && peek() == ',') {
                    position++
                    skipWhitespace()
                    continue
                } else if (hasMore() && peek() == ']') {
                    position++
                    closed = true
                    break
                } else {
                    throw JsonException("Expected ',' or ']' in array", position)
                }
            }
            if (!closed) throw JsonException("Unclosed array, expected ']'", position)
            return JsonArray(list)
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (hasMore()) {
                if (sb.length >= MAX_STRING_LENGTH) {
                    throw JsonException("Max JSON string length ($MAX_STRING_LENGTH) exceeded", position)
                }
                val c = src[position++]
                if (c == '"') {
                    return sb.toString()
                }
                if (c == '\\') {
                    if (!hasMore()) throw JsonException("Unterminated escape sequence", position)
                    when (val esc = src[position++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (position + 4 > src.length) throw JsonException("Incomplete unicode escape", position)
                            val hex = src.substring(position, position + 4)
                            position += 4
                            val code = hex.toIntOrNull(16) ?: throw JsonException("Invalid hex in unicode escape: $hex", position)
                            sb.append(code.toChar())
                        }
                        else -> throw JsonException("Invalid escape character: \\$esc", position)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw JsonException("Unterminated string literal", position)
        }

        fun parseBoolean(): JsonBoolean {
            if (src.startsWith("true", position)) {
                position += 4
                return JsonBoolean(true)
            }
            if (src.startsWith("false", position)) {
                position += 5
                return JsonBoolean(false)
            }
            throw JsonException("Invalid boolean literal", position)
        }

        fun parseNull(): JsonNull {
            if (src.startsWith("null", position)) {
                position += 4
                return JsonNull
            }
            throw JsonException("Invalid null literal", position)
        }

        fun parseNumber(): JsonNumber {
            val start = position
            var hasFractionOrExp = false
            if (peek() == '-') position++
            while (hasMore() && peek() in '0'..'9') position++
            if (hasMore() && peek() == '.') {
                hasFractionOrExp = true
                position++
                while (hasMore() && peek() in '0'..'9') position++
            }
            if (hasMore() && (peek() == 'e' || peek() == 'E')) {
                hasFractionOrExp = true
                position++
                if (hasMore() && (peek() == '+' || peek() == '-')) position++
                while (hasMore() && peek() in '0'..'9') position++
            }
            val raw = src.substring(start, position)
            val d = raw.toDoubleOrNull() ?: throw JsonException("Invalid number: $raw", start)
            val l = if (!hasFractionOrExp) raw.toLongOrNull() else null
            return JsonNumber(
                value = d,
                raw = raw,
                longValue = l,
                isInteger = !hasFractionOrExp && l != null
            )
        }

        private fun expect(ch: Char) {
            if (!hasMore() || src[position] != ch) {
                throw JsonException("Expected '$ch' but got '${if (hasMore()) src[position] else "EOF"}'", position)
            }
            position++
        }
    }
}
