package com.reson.player.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibraryTest {
    @Test
    fun titlePrefersTagThenFilenameThenFallback() {
        assertEquals("Song", LocalTrackMetadata.title(" Song ", "file.mp3"))
        assertEquals("Live.2026", LocalTrackMetadata.title("<unknown>", "Live.2026.flac"))
        assertEquals("Untitled track", LocalTrackMetadata.title(" ", null))
    }

    @Test
    fun artistAndAlbumDropMissingMediaStoreTags() {
        assertNull(LocalTrackMetadata.normalize("<UNKNOWN>"))
        assertNull(LocalTrackMetadata.normalize("   "))
        assertEquals("Artist", LocalTrackMetadata.normalize(" Artist "))
    }

    @Test
    fun recognizesSupportedFormatsEvenWhenMediaStoreDidNotClassifyThemAsMusic() {
        listOf("MP3", "FLAC", "AAC", "M4A", "OGG", "WAV").forEach { format ->
            assertTrue(LocalTrackMetadata.supports("sample.$format", isMusic = false))
        }
        assertFalse(LocalTrackMetadata.supports("sample.txt", isMusic = false))
        assertFalse(LocalTrackMetadata.supports(null, isMusic = false))
        assertTrue(LocalTrackMetadata.supports("sample.webm", isMusic = true))
    }

    @Test
    fun queuesInLibraryOrderStartingFromTheSelectedUri() {
        val tracks = (1..3).map {
            Track("content://media/external_primary/audio/media/$it", "Same title", null, null, 0)
        }
        val order = PlaybackOrder.fromLibrary(tracks, tracks[1].uri)
        assertEquals(1, order?.startIndex)
        assertEquals(tracks, order?.tracks)
        assertNull(PlaybackOrder.fromLibrary(tracks, "removed"))
        assertNull(PlaybackOrder.fromLibrary(emptyList(), tracks.first().uri))
    }
}
