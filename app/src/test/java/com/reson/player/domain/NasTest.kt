package com.reson.player.domain

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.reson.player.data.classifySmbError
import com.reson.player.playback.NasReadRange
import com.reson.player.playback.PositionOutsideNasFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException

class NasTest {
    @Test
    fun acceptsLocalAddressRangesAndRejectsPublicAndLoopbackAddresses() {
        listOf("10.0.2.2", "172.31.1.2", "192.168.1.3", "169.254.1.4", "fd12::4", "fe80::1")
            .forEach { assertTrue(it, NasPaths.isLocalAddress(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "172.32.1.2", "127.0.0.1", "100.64.0.1", "::1", "2001:4860::1", "ff02::1")
            .forEach { assertFalse(it, NasPaths.isLocalAddress(InetAddress.getByName(it))) }
    }

    @Test
    fun matchesOnlyTheConfiguredSubnetIncludingPartialBytePrefixes() {
        fun address(value: String) = InetAddress.getByName(value)

        assertTrue(NasPaths.sameSubnet(address("10.0.2.2"), address("10.0.2.16"), 24))
        assertFalse(NasPaths.sameSubnet(address("10.0.3.2"), address("10.0.2.16"), 24))
        assertTrue(NasPaths.sameSubnet(address("192.168.1.130"), address("192.168.1.140"), 25))
        assertFalse(NasPaths.sameSubnet(address("192.168.1.126"), address("192.168.1.140"), 25))
        assertTrue(NasPaths.sameSubnet(address("fd10:2::88"), address("fd10:2::1"), 64))
        assertFalse(NasPaths.sameSubnet(address("fd10:3::88"), address("fd10:2::1"), 64))
        assertFalse(NasPaths.sameSubnet(address("fd10::1"), address("10.0.2.16"), 24))
        assertFalse(NasPaths.sameSubnet(address("10.0.2.2"), address("10.0.2.16"), 0))
    }

    @Test
    fun onlyAcceptsLocalNamesAndSafeSharePathComponents() {
        listOf("diskstation", "music-box.local", "diskstation.lan", "nas.home.arpa")
            .forEach { assertTrue(it, NasPaths.validHostName(it)) }
        listOf("nas.example.org", "https://nas.local", "nas.local:445", "..", "-nas")
            .forEach { assertFalse(it, NasPaths.validHostName(it)) }
        listOf("日本の音楽", "Song #1.flac", "Album & Singles").forEach {
            assertTrue(it, NasPaths.validComponent(it))
        }
        listOf("", "..", "a/b", "a\\b", "song.wav:stream", "folder\u0000").forEach {
            assertFalse(it, NasPaths.validComponent(it))
        }
    }

    @Test
    fun reportsCredentialPermissionPathAndNetworkFailuresSeparately() {
        fun failure(status: NtStatus) =
            SMBApiException(status.value, SMB2MessageCommandCode.SMB2_SESSION_SETUP, IOException("fixture"))

        assertEquals(NasProblem.Authentication, classifySmbError(failure(NtStatus.STATUS_LOGON_FAILURE)))
        assertEquals(NasProblem.Access, classifySmbError(failure(NtStatus.STATUS_ACCESS_DENIED)))
        assertEquals(NasProblem.Missing, classifySmbError(failure(NtStatus.STATUS_BAD_NETWORK_NAME)))
        assertEquals(NasProblem.Unreachable, classifySmbError(SocketTimeoutException()))
        assertEquals(NasProblem.SocketBlocked, classifySmbError(SocketException("socket failed: EPERM")))
        assertEquals(NasProblem.NonLocal, classifySmbError(IOException(NasException(NasProblem.NonLocal))))
    }

    @Test
    fun readsAtTheRequestedOffsetAndHonorsTheRangeAcrossShortReads() {
        val file = FakeNasFile()
        val buffer = ByteArray(5)
        NasReadRange(file, position = 3, requestedLength = 3).use { range ->
            assertEquals(3L, range.reportedLength)
            assertEquals(2, range.read(buffer, 1, 4))
            assertEquals(1, range.read(buffer, 3, 2))
            assertEquals(0, range.read(buffer, 0, 0))
            assertEquals(-1, range.read(buffer, 0, 2))
        }
        assertArrayEquals(byteArrayOf(0, 3, 4, 5, 0), buffer)
        assertEquals(listOf(3L, 5L), file.offsets)
        assertTrue(file.closed)
    }

    @Test
    fun handlesEndOfFileAndRejectsInvalidSeekPositions() {
        val buffer = ByteArray(4)
        NasReadRange(FakeNasFile(), position = 6, requestedLength = 20).use { range ->
            assertEquals(20L, range.reportedLength)
            assertEquals(2, range.read(buffer, 0, buffer.size))
            assertEquals(-1, range.read(buffer, 0, buffer.size))
        }
        NasReadRange(FakeNasFile(), position = 8, requestedLength = null).use { range ->
            assertEquals(0L, range.reportedLength)
            assertEquals(-1, range.read(buffer, 0, buffer.size))
        }
        assertThrows(PositionOutsideNasFile::class.java) { NasReadRange(FakeNasFile(), 9, null) }
    }

    @Test
    fun propagatesStreamFailureWithoutConsumingTheCurrentOffset() {
        val file = FakeNasFile()
        val buffer = ByteArray(2)
        NasReadRange(file, position = 4, requestedLength = null).use { range ->
            assertEquals(4L, range.reportedLength)
            file.failRead = true
            assertThrows(NasException::class.java) { range.read(buffer, 0, 2) }
            file.failRead = false
            assertEquals(2, range.read(buffer, 0, 2))
        }
        assertEquals(listOf(4L, 4L), file.offsets)
        assertTrue(file.closed)
    }

    private class FakeNasFile : NasFile {
        override val size = 8L
        val offsets = mutableListOf<Long>()
        var failRead = false
        var closed = false

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            offsets += position
            if (failRead) throw NasException(NasProblem.Unreachable)
            val count = minOf(length, 2, (size - position).toInt())
            repeat(count) { buffer[offset + it] = (position + it).toByte() }
            return count
        }

        override fun close() {
            closed = true
        }
    }
}
