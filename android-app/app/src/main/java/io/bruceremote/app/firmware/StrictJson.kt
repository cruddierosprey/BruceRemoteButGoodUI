package io.bruceremote.app.firmware

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface JsonValue

internal data class JsonObject(val fields: LinkedHashMap<String, JsonValue>) : JsonValue

internal data class JsonArray(val values: List<JsonValue>) : JsonValue

internal data class JsonString(val value: String) : JsonValue

internal data class JsonInteger(val value: Long) : JsonValue

internal data class JsonBoolean(val value: Boolean) : JsonValue

internal data object JsonNull : JsonValue

/**
 * Small, deliberately limited JSON parser for signed catalog manifests.
 *
 * It rejects duplicate object keys, non-integer numbers, malformed UTF-8,
 * unpaired surrogates, trailing input, excessive nesting and large containers.
 * This avoids the permissive behavior and duplicate-key ambiguity of many
 * general JSON parsers.
 */
internal object StrictJson {
    private const val MAXIMUM_DEPTH = 12
    private const val MAXIMUM_CONTAINER_ENTRIES = 256
    private const val MAXIMUM_STRING_CHARACTERS = 4096

    fun parseObject(bytes: ByteArray, maximumBytes: Int): JsonObject {
        if (bytes.size > maximumBytes) {
            throw FirmwarePackageException.ManifestTooLarge(
                bytes.size.toLong(),
                maximumBytes.toLong(),
            )
        }

        val text = try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw FirmwarePackageException.InvalidUtf8(error)
        }

        val parser = Parser(text)
        val value = parser.parseDocument()
        return value as? JsonObject
            ?: throw FirmwarePackageException.JsonSyntax(0, "top-level value must be an object")
    }

    private class Parser(private val source: String) {
        private var position = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            if (position == source.length) {
                syntax("document is empty")
            }
            val value = parseValue(depth = 0)
            skipWhitespace()
            if (position != source.length) {
                syntax("trailing data is not allowed")
            }
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAXIMUM_DEPTH) {
                syntax("nesting depth exceeds $MAXIMUM_DEPTH")
            }
            if (position >= source.length) {
                syntax("unexpected end of input")
            }
            return when (source[position]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonString(parseString())
                't' -> parseLiteral("true", JsonBoolean(true))
                'f' -> parseLiteral("false", JsonBoolean(false))
                'n' -> parseLiteral("null", JsonNull)
                '-', in '0'..'9' -> JsonInteger(parseInteger())
                else -> syntax("unexpected character '${source[position]}'")
            }
        }

        private fun parseObject(depth: Int): JsonObject {
            expect('{')
            skipWhitespace()
            val fields = LinkedHashMap<String, JsonValue>()
            if (consumeIf('}')) {
                return JsonObject(fields)
            }

            while (true) {
                if (fields.size >= MAXIMUM_CONTAINER_ENTRIES) {
                    syntax("object contains more than $MAXIMUM_CONTAINER_ENTRIES fields")
                }
                if (peek() != '"') {
                    syntax("object key must be a string")
                }
                val keyOffset = position
                val key = parseString()
                if (fields.containsKey(key)) {
                    throw FirmwarePackageException.JsonSyntax(
                        keyOffset,
                        "duplicate object key '$key'",
                    )
                }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = parseValue(depth)
                skipWhitespace()
                when {
                    consumeIf('}') -> return JsonObject(fields)
                    consumeIf(',') -> {
                        skipWhitespace()
                        if (peek() == '}') {
                            syntax("trailing comma is not allowed")
                        }
                    }
                    else -> syntax("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            expect('[')
            skipWhitespace()
            val values = ArrayList<JsonValue>()
            if (consumeIf(']')) {
                return JsonArray(values)
            }

            while (true) {
                if (values.size >= MAXIMUM_CONTAINER_ENTRIES) {
                    syntax("array contains more than $MAXIMUM_CONTAINER_ENTRIES entries")
                }
                values += parseValue(depth)
                skipWhitespace()
                when {
                    consumeIf(']') -> return JsonArray(values)
                    consumeIf(',') -> {
                        skipWhitespace()
                        if (peek() == ']') {
                            syntax("trailing comma is not allowed")
                        }
                    }
                    else -> syntax("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (position < source.length) {
                val character = source[position++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> appendEscape(result)
                    character.code < 0x20 -> syntax("unescaped control character in string")
                    Character.isHighSurrogate(character) -> {
                        if (position >= source.length ||
                            !Character.isLowSurrogate(source[position])
                        ) {
                            syntax("unpaired high surrogate in string")
                        }
                        result.append(character)
                        result.append(source[position++])
                    }
                    Character.isLowSurrogate(character) ->
                        syntax("unpaired low surrogate in string")
                    else -> result.append(character)
                }
                if (result.length > MAXIMUM_STRING_CHARACTERS) {
                    syntax("string exceeds $MAXIMUM_STRING_CHARACTERS characters")
                }
            }
            syntax("unterminated string")
        }

        private fun appendEscape(result: StringBuilder) {
            if (position >= source.length) {
                syntax("unterminated escape sequence")
            }
            when (val escaped = source[position++]) {
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val first = parseUnicodeCodeUnit()
                    when {
                        Character.isHighSurrogate(first) -> {
                            if (position + 2 > source.length ||
                                source[position] != '\\' ||
                                source[position + 1] != 'u'
                            ) {
                                syntax("escaped high surrogate is not followed by a low surrogate")
                            }
                            position += 2
                            val second = parseUnicodeCodeUnit()
                            if (!Character.isLowSurrogate(second)) {
                                syntax("escaped high surrogate is not followed by a low surrogate")
                            }
                            result.append(first)
                            result.append(second)
                        }
                        Character.isLowSurrogate(first) ->
                            syntax("unpaired escaped low surrogate")
                        else -> result.append(first)
                    }
                }
                else -> syntax("unsupported escape '\\$escaped'")
            }
        }

        private fun parseUnicodeCodeUnit(): Char {
            if (position + 4 > source.length) {
                syntax("incomplete unicode escape")
            }
            var value = 0
            repeat(4) {
                val digit = source[position++].digitToIntOrNull(16)
                    ?: syntax("unicode escape contains a non-hexadecimal character")
                value = (value shl 4) or digit
            }
            return value.toChar()
        }

        private fun parseInteger(): Long {
            val start = position
            consumeIf('-')
            if (position >= source.length) {
                syntax("incomplete number")
            }
            when (source[position]) {
                '0' -> {
                    position++
                    if (position < source.length && source[position] in '0'..'9') {
                        syntax("leading zero is not allowed")
                    }
                }
                in '1'..'9' -> {
                    while (position < source.length && source[position] in '0'..'9') {
                        position++
                    }
                }
                else -> syntax("invalid integer")
            }

            if (position < source.length &&
                (source[position] == '.' || source[position] == 'e' || source[position] == 'E')
            ) {
                syntax("only integer JSON numbers are accepted")
            }
            return source.substring(start, position).toLongOrNull()
                ?: throw FirmwarePackageException.JsonSyntax(start, "integer is outside signed 64-bit range")
        }

        private fun <T : JsonValue> parseLiteral(literal: String, value: T): T {
            if (!source.regionMatches(position, literal, 0, literal.length)) {
                syntax("invalid literal")
            }
            position += literal.length
            return value
        }

        private fun expect(character: Char) {
            if (position >= source.length || source[position] != character) {
                syntax("expected '$character'")
            }
            position++
        }

        private fun consumeIf(character: Char): Boolean {
            if (position < source.length && source[position] == character) {
                position++
                return true
            }
            return false
        }

        private fun peek(): Char? = source.getOrNull(position)

        private fun skipWhitespace() {
            while (position < source.length) {
                when (source[position]) {
                    ' ', '\t', '\r', '\n' -> position++
                    else -> return
                }
            }
        }

        private fun syntax(detail: String): Nothing =
            throw FirmwarePackageException.JsonSyntax(position, detail)
    }
}
