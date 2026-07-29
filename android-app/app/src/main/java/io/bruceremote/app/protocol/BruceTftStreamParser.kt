package io.bruceremote.app.protocol

import java.nio.charset.StandardCharsets

/**
 * Demultiplexes Bruce's normal text shell and its compact TFT drawing stream.
 *
 * Recent Bruce builds expose `display start`. While it is active, the firmware
 * writes packets with this layout into the same serial byte stream as CLI text:
 *
 *     AA | total-size | function | function payload...
 *
 * USB reads may split a packet anywhere or combine text and several packets.
 * This parser keeps incomplete candidates between reads and forwards all bytes
 * outside validated TFT packets unchanged to the normal line parser.
 */
class BruceTftStreamParser(
    private val onTextData: (ByteArray) -> Unit,
    private val onTftPacket: (BruceTftPacket) -> Unit,
) {
    private var pending = ByteArray(0)

    fun accept(data: ByteArray) {
        if (data.isEmpty()) return
        val input = if (pending.isEmpty()) {
            data
        } else {
            ByteArray(pending.size + data.size).also {
                pending.copyInto(it)
                data.copyInto(it, pending.size)
            }
        }
        pending = ByteArray(0)

        var cursor = 0
        while (cursor < input.size) {
            val header = input.indexOfHeader(cursor)
            if (header < 0) {
                emitText(input, cursor, input.size)
                return
            }

            emitText(input, cursor, header)
            if (input.size - header < HEADER_SIZE) {
                pending = input.copyOfRange(header, input.size)
                return
            }

            val packetSize = input[header + 1].toInt() and 0xff
            val function = input[header + 2].toInt() and 0xff
            if (!BruceTftProtocol.isValidShape(function, packetSize)) {
                // A stray 0xAA is not allowed to hide following shell text.
                onTextData(byteArrayOf(input[header]))
                cursor = header + 1
                continue
            }

            if (input.size - header < packetSize) {
                pending = input.copyOfRange(header, input.size)
                return
            }

            val packetBytes = input.copyOfRange(header, header + packetSize)
            onTftPacket(BruceTftPacket(packetBytes))
            cursor = header + packetSize
        }
    }

    /**
     * Discards an incomplete binary packet. Complete text has already been
     * forwarded as it arrived, so it must not be replayed here.
     */
    fun reset() {
        pending = ByteArray(0)
    }

    private fun emitText(source: ByteArray, start: Int, end: Int) {
        if (end > start) onTextData(source.copyOfRange(start, end))
    }

    private fun ByteArray.indexOfHeader(start: Int): Int {
        for (index in start until size) {
            if ((this[index].toInt() and 0xff) == BruceTftProtocol.HEADER) return index
        }
        return -1
    }

    private companion object {
        const val HEADER_SIZE = 3
    }
}

class BruceTftPacket internal constructor(
    val bytes: ByteArray,
) {
    val size: Int
        get() = bytes[1].toInt() and 0xff

    val function: Int
        get() = bytes[2].toInt() and 0xff

    fun unsigned16(offset: Int): Int {
        require(offset >= 0 && offset + 1 < bytes.size)
        return ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)
    }

    fun signed16(offset: Int): Int = unsigned16(offset).toShort().toInt()

    fun unsigned8(offset: Int): Int {
        require(offset in bytes.indices)
        return bytes[offset].toInt() and 0xff
    }

    fun utf8(offset: Int): String {
        require(offset in 0..bytes.size)
        return bytes.copyOfRange(offset, bytes.size).toString(StandardCharsets.UTF_8)
    }
}

object BruceTftProtocol {
    const val HEADER = 0xaa
    const val MAX_PACKET_SIZE = 128

    const val FILL_SCREEN = 0
    const val DRAW_RECT = 1
    const val FILL_RECT = 2
    const val DRAW_ROUND_RECT = 3
    const val FILL_ROUND_RECT = 4
    const val DRAW_CIRCLE = 5
    const val FILL_CIRCLE = 6
    const val DRAW_TRIANGLE = 7
    const val FILL_TRIANGLE = 8
    const val DRAW_ELLIPSE = 9
    const val FILL_ELLIPSE = 10
    const val DRAW_LINE = 11
    const val DRAW_ARC = 12
    const val DRAW_WIDE_LINE = 13
    const val DRAW_CENTRE_STRING = 14
    const val DRAW_RIGHT_STRING = 15
    const val DRAW_STRING = 16
    const val PRINT = 17
    const val DRAW_IMAGE = 18
    const val DRAW_PIXEL = 19
    const val DRAW_FAST_V_LINE = 20
    const val DRAW_FAST_H_LINE = 21
    const val SCREEN_INFO = 99

    private val fixedPacketSizes = mapOf(
        FILL_SCREEN to 5,
        DRAW_RECT to 13,
        FILL_RECT to 13,
        DRAW_ROUND_RECT to 15,
        FILL_ROUND_RECT to 15,
        DRAW_CIRCLE to 11,
        FILL_CIRCLE to 11,
        DRAW_TRIANGLE to 17,
        FILL_TRIANGLE to 17,
        DRAW_ELLIPSE to 13,
        FILL_ELLIPSE to 13,
        DRAW_LINE to 13,
        DRAW_ARC to 19,
        DRAW_WIDE_LINE to 17,
        DRAW_PIXEL to 9,
        DRAW_FAST_V_LINE to 11,
        DRAW_FAST_H_LINE to 11,
        SCREEN_INFO to 8,
    )

    internal fun isValidShape(function: Int, packetSize: Int): Boolean {
        if (packetSize !in 3..MAX_PACKET_SIZE) return false
        fixedPacketSizes[function]?.let { return packetSize == it }
        return when (function) {
            DRAW_CENTRE_STRING,
            DRAW_RIGHT_STRING,
            DRAW_STRING,
            PRINT,
            -> packetSize >= 13

            DRAW_IMAGE -> packetSize >= 12
            else -> false
        }
    }
}
