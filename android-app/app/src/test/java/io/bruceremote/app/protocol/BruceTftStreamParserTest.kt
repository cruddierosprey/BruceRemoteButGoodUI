package io.bruceremote.app.protocol

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BruceTftStreamParserTest {
    @Test
    fun demultiplexesTextAndPacketAcrossEveryByteBoundary() {
        val text = ByteArrayOutputStream()
        val packets = mutableListOf<BruceTftPacket>()
        val parser = BruceTftStreamParser(text::write, packets::add)
        val screenInfo = byteArrayOf(
            0xaa.toByte(),
            8,
            BruceTftProtocol.SCREEN_INFO.toByte(),
            0,
            240.toByte(),
            0,
            135.toByte(),
            1,
        )
        val stream =
            "Display: Started\r\n".toByteArray() + screenInfo + "# ".toByteArray()

        stream.forEach { parser.accept(byteArrayOf(it)) }

        assertArrayEquals(
            "Display: Started\r\n# ".toByteArray(),
            text.toByteArray(),
        )
        assertEquals(1, packets.size)
        assertEquals(BruceTftProtocol.SCREEN_INFO, packets.single().function)
        assertEquals(240, packets.single().unsigned16(3))
        assertEquals(135, packets.single().unsigned16(5))
        assertEquals(1, packets.single().unsigned8(7))
    }

    @Test
    fun packetPayloadMayContainAnotherHeaderByte() {
        val text = ByteArrayOutputStream()
        val packets = mutableListOf<BruceTftPacket>()
        val parser = BruceTftStreamParser(text::write, packets::add)
        val packet = byteArrayOf(
            0xaa.toByte(),
            14,
            BruceTftProtocol.DRAW_STRING.toByte(),
            0,
            1,
            0,
            2,
            0,
            1,
            0xff.toByte(),
            0xff.toByte(),
            0,
            0,
            0xaa.toByte(),
        )

        parser.accept(packet.copyOfRange(0, 9))
        parser.accept(packet.copyOfRange(9, packet.size))

        assertEquals(0, text.size())
        assertEquals(1, packets.size)
        assertArrayEquals(packet, packets.single().bytes)
    }

    @Test
    fun malformedCandidateDoesNotHideShellText() {
        val text = ByteArrayOutputStream()
        val packets = mutableListOf<BruceTftPacket>()
        val parser = BruceTftStreamParser(text::write, packets::add)
        val input = byteArrayOf(
            'A'.code.toByte(),
            0xaa.toByte(),
            2,
            BruceTftProtocol.SCREEN_INFO.toByte(),
            'Z'.code.toByte(),
        )

        parser.accept(input)

        assertArrayEquals(input, text.toByteArray())
        assertTrue(packets.isEmpty())
    }

    @Test
    fun parsesAdjacentPacketsAndKeepsSignedCoordinates() {
        val packets = mutableListOf<BruceTftPacket>()
        val parser = BruceTftStreamParser(
            onTextData = { error("Unexpected text") },
            onTftPacket = packets::add,
        )
        val first = fixedRectPacket(x = -3, y = 4, color = 0xf800)
        val second = fixedRectPacket(x = 7, y = -8, color = 0x07e0)

        parser.accept(first + second)

        assertEquals(2, packets.size)
        assertEquals(-3, packets[0].signed16(3))
        assertEquals(4, packets[0].signed16(5))
        assertEquals(0xf800, packets[0].unsigned16(11))
        assertEquals(7, packets[1].signed16(3))
        assertEquals(-8, packets[1].signed16(5))
    }

    @Test
    fun resetDropsOnlyAnIncompleteBinaryCandidate() {
        val text = ByteArrayOutputStream()
        val packets = mutableListOf<BruceTftPacket>()
        val parser = BruceTftStreamParser(text::write, packets::add)
        parser.accept(byteArrayOf('O'.code.toByte(), 'K'.code.toByte(), 0xaa.toByte(), 8))

        parser.reset()
        parser.accept("next".toByteArray())

        assertArrayEquals("OKnext".toByteArray(), text.toByteArray())
        assertTrue(packets.isEmpty())
    }

    private fun fixedRectPacket(x: Int, y: Int, color: Int): ByteArray {
        val result = byteArrayOf(
            0xaa.toByte(),
            13,
            BruceTftProtocol.DRAW_RECT.toByte(),
            0,
            0,
            0,
            0,
            0,
            10,
            0,
            11,
            0,
            0,
        )
        put16(result, 3, x)
        put16(result, 5, y)
        put16(result, 11, color)
        return result
    }

    private fun put16(destination: ByteArray, offset: Int, value: Int) {
        destination[offset] = ((value ushr 8) and 0xff).toByte()
        destination[offset + 1] = (value and 0xff).toByte()
    }
}
