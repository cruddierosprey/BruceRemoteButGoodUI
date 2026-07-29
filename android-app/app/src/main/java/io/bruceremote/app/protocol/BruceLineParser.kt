package io.bruceremote.app.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Incremental parser for Bruce's text serial protocol.
 *
 * Bruce terminates normal output with newlines, then emits "# " without a
 * trailing newline. USB bulk reads can split either sequence at any byte, so
 * prompt detection must operate on the buffered byte stream rather than on
 * individual callbacks.
 */
class BruceLineParser(
    private val listener: Listener,
) {
    interface Listener {
        fun onLine(line: String)
        fun onPrompt()
    }

    private val lineBuffer = ByteArrayOutputStream()

    fun accept(data: ByteArray) {
        data.forEach { rawByte ->
            val value = rawByte.toInt() and 0xff
            when (value) {
                '\r'.code -> Unit
                '\n'.code -> emitLine()
                else -> {
                    lineBuffer.write(value)
                    if (isPrompt()) {
                        lineBuffer.reset()
                        listener.onPrompt()
                    } else if (lineBuffer.size() >= MAX_BUFFERED_LINE_BYTES) {
                        emitLine()
                    }
                }
            }
        }
    }

    fun flushPartial() {
        if (lineBuffer.size() > 0) {
            emitLine()
        }
    }

    fun reset() {
        lineBuffer.reset()
    }

    private fun isPrompt(): Boolean {
        if (lineBuffer.size() != 2) return false
        val bytes = lineBuffer.toByteArray()
        return bytes[0] == '#'.code.toByte() && bytes[1] == ' '.code.toByte()
    }

    private fun emitLine() {
        val text = lineBuffer.toByteArray().toString(StandardCharsets.UTF_8)
        lineBuffer.reset()
        listener.onLine(text)
    }

    private companion object {
        const val MAX_BUFFERED_LINE_BYTES = 32 * 1024
    }
}
