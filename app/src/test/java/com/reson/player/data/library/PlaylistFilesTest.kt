package com.reson.player.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistFilesTest {
    @Test
    fun extendedPlaylistPreservesOrderAndUtf8Names() {
        val file = "\uFEFF#EXTM3U\r\n#EXTINF:180,A\r\n./Rock/Été.mp3\r\n\r\n../Jazz/Two.flac\r\n"

        assertEquals(listOf("./Rock/Été.mp3", "../Jazz/Two.flac"), playlistEntries(file.encodeToByteArray()))
    }

    @Test
    fun legacyPlaylistAcceptsWindowsEncoding() {
        assertEquals(listOf("Été.mp3"), playlistEntries("Été.mp3".toByteArray(charset("windows-1252"))))
    }

    @Test
    fun nasPlaylistCanResolveParentFoldersInsideItsShare() {
        assertEquals(listOf("Albums", "Artist", "Song.mp3"),
            relativePlaylistPath("..\\Albums\\Artist\\Song.mp3", listOf("Playlists")))
        assertNull(relativePlaylistPath("../../Song.mp3", listOf("Playlists")))
        assertNull(relativePlaylistPath("C:\\Music\\Song.mp3", listOf("Playlists")))
    }
}
