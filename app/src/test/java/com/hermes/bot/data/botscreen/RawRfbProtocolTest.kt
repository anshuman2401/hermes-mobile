package com.hermes.bot.data.botscreen

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class RawRfbProtocolTest {
    private fun serverInit(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { data ->
            data.writeShort(width); data.writeShort(height)
            data.write(ByteArray(16)); data.writeInt(0)
        }
    }.toByteArray()

    @Test fun fragmentedHandshakeAndRawFrameRequestIncrementalUpdate() {
        val sent = mutableListOf<ByteArray>()
        val frames = mutableListOf<ScreenFrame>()
        val parser = RawRfbProtocol(sent::add, frames::add)
        val handshake = "RFB 003.008\n".toByteArray() + byteArrayOf(1, 1) +
            byteArrayOf(0, 0, 0, 0) + serverInit(2, 1)
        handshake.forEach { parser.feed(byteArrayOf(it)) }
        assertEquals("RFB 003.008\n", String(sent.first()))
        assertEquals(3, sent.last()[0].toInt())
        val update = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 2, 0, 1, 0, 0, 0, 0,
            1, 2, 3, 0, 4, 5, 6, 0)
        update.take(17).forEach { parser.feed(byteArrayOf(it)) }
        assertTrue(frames.isEmpty())
        parser.feed(update.drop(17).toByteArray())
        assertEquals(1, frames.size)
        assertArrayEquals(intArrayOf(0xff030201.toInt(), 0xff060504.toInt()), frames.single().pixels)
        assertEquals(1, sent.last()[1].toInt()) // incremental after first frame
        parser.pointer(1, 0, 1)
        assertArrayEquals(byteArrayOf(5, 1, 0, 1, 0, 0), sent.last())
        parser.key(0xff0d, true)
        assertEquals(4, sent.last()[0].toInt())
    }

    @Test fun oversizedGeometryAndUnsupportedSecurityFailBeforeAllocation() {
        val parser = RawRfbProtocol({}, {})
        parser.feed("RFB 003.008\n".toByteArray())
        try { parser.feed(byteArrayOf(1, 2)); fail("expected security mismatch") } catch (_: IllegalArgumentException) {}
        val geometry = RawRfbProtocol({}, {})
        geometry.feed("RFB 003.008\n".toByteArray() + byteArrayOf(1, 1, 0, 0, 0, 0))
        try { geometry.feed(serverInit(5000, 5000)); fail("expected size rejection") } catch (_: IllegalArgumentException) {}
    }
}
