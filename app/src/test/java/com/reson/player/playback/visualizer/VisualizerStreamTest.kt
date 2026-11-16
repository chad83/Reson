package com.reson.player.playback.visualizer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualizerStreamTest {
    @Test
    fun packetsPreserveStereoAndDuplicateMonoWithoutConsumingPlayerInput() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(123).putShort(-456).putShort(789).putShort(-100).flip()
        val stereo = ByteBuffer.wrap(stereoPacket(input, 0, 2, 2, 48000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48000, stereo.int)
        assertEquals(2, stereo.int)
        assertArrayEquals(shortArrayOf(123, -456, 789, -100), ShortArray(4) { stereo.short })

        val mono = ByteBuffer.wrap(stereoPacket(input, 2, 2, 1, 44100)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44100, mono.int)
        assertEquals(2, mono.int)
        assertArrayEquals(shortArrayOf(-456, -456, 789, 789), ShortArray(4) { mono.short })
        assertEquals(0, input.position())
    }

    @Test
    fun aSlowBrowserReceivesRecentPacketsAndCloseDiscardsPendingAudio() {
        var closed = 0
        val stream = TelevisionStream { closed++ }
        repeat(25) { stream.offer(byteArrayOf(it.toByte())) }

        assertEquals(9, stream.read())
        stream.close()
        stream.close()

        assertEquals(-1, stream.read())
        assertEquals(1, closed)
    }
}
