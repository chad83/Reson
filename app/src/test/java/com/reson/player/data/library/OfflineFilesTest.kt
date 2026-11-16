package com.reson.player.data.library

import com.reson.player.domain.NasFile
import java.io.EOFException
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun interruptedTransferResumesAtTheLastWrittenByte() {
        val content = ByteArray(30) { it.toByte() }
        val partial = temporary.newFile("track.part")
        val remote = ShortReads(content)
        var checks = 0

        assertThrows(IOException::class.java) {
            copyToPartial(remote, remote.size, partial) {
                if (++checks == 3) throw IOException("Interrupted")
            }
        }
        assertArrayEquals(content.copyOf(10), partial.readBytes())
        val resumed = ShortReads(content)
        val progress = mutableListOf<Long>()
        copyToPartial(resumed, resumed.size, partial, onProgress = { progress.add(it) }) {}

        assertEquals(10L, resumed.positions.first())
        assertEquals(listOf(10L, 15L, 20L, 25L, 30L), progress)
        assertArrayEquals(content, partial.readBytes())
    }

    @Test
    fun rejectsEarlyEofAndNeverResolvesPartialOrTruncatedFilesForPlayback() {
        val remote = ShortReads(byteArrayOf(1, 2, 3))
        val partial = temporary.newFile("track.part")
        assertThrows(EOFException::class.java) { copyToPartial(remote, 4L, partial) {} }

        assertNull(offlineFile(temporary.root, partial.name, 3L))
        val finalName = "${"a".repeat(64)}_${"b".repeat(64)}.audio"
        val completed = File(temporary.root, finalName).apply { writeBytes(partial.readBytes()) }
        assertNull(offlineFile(temporary.root, finalName, 4L))
        assertNull(offlineFile(temporary.root, "../$finalName", 3L))
        assertEquals(completed, offlineFile(temporary.root, finalName, 3L))
    }

    @Test
    fun replacesAnOversizedPartialFromTheBeginning() {
        val partial = temporary.newFile("track.part").apply { writeBytes(ByteArray(30)) }
        val expected = byteArrayOf(4, 7, 9)
        val remote = ShortReads(expected)
        copyToPartial(remote, remote.size, partial) {}

        assertEquals(listOf(0L), remote.positions)
        assertArrayEquals(expected, partial.readBytes())
    }

    @Test
    fun storageCheckUsesRemainingBytesAndStopsBeforeWriting() {
        val partial = temporary.newFile("track.part").apply { writeBytes(byteArrayOf(1, 2)) }
        val remote = ShortReads(byteArrayOf(1, 2, 3, 4, 5))

        assertThrows(IOException::class.java) {
            copyToPartial(remote, remote.size, partial, checkSpace = { remaining ->
                assertEquals(3L, remaining)
                throw IOException("Insufficient storage")
            }) {}
        }

        assertArrayEquals(byteArrayOf(1, 2), partial.readBytes())
        assertEquals(emptyList<Long>(), remote.positions)
    }

    private class ShortReads(private val bytes: ByteArray) : NasFile {
        override val size: Long get() = bytes.size.toLong()
        val positions = mutableListOf<Long>()

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            positions.add(position)
            if (position >= size) return -1
            val count = minOf(5, length, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun close() = Unit
    }
}
