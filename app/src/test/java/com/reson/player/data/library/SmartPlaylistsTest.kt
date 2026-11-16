package com.reson.player.data.library

import com.reson.player.domain.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartPlaylistsTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    @Test
    fun favouritesExcludeRecentlyPlayedTracksWithTheSameSource() {
        val played = Track("track", "Song", "Artist", "Album", 0, source = "a")
        val unplayed = played.copy(source = "b")
        val old = played.copy(uri = "other", originalUri = "other")
        val history = listOf(PlaybackVisit(1, now - day, played), PlaybackVisit(2, now - 35 * day, old))

        val playlists = smartPlaylists(listOf(played, unplayed, old), history, emptyList(), now)

        assertEquals(listOf(unplayed, old), playlists.first { it.rule == SmartPlaylist.UnplayedFavourites }.tracks)
    }

    @Test
    fun recentAlbumUsesNewestTrackDateAndIncludesTheFullAlbumInTrackOrder() {
        val older = Track("one", "Opening", "Artist", "Album", 0, trackNumber = 1, addedAt = now - 60 * day)
        val newer = older.copy(uri = "two", originalUri = "two", title = "Finale", trackNumber = 2, addedAt = now - day)
        val legacy = older.copy(uri = "legacy", originalUri = "legacy", album = "Legacy", addedAt = 0)

        val playlists = smartPlaylists(emptyList(), emptyList(), listOf(newer, legacy, older), now)

        assertEquals(listOf(older, newer), playlists.first { it.rule == SmartPlaylist.RecentlyAdded }.tracks)
    }
}
