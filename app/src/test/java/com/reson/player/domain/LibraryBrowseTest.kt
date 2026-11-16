package com.reson.player.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBrowseTest {
    @Test
    fun searchesAcrossTagsAndPreservesEachSourceInThePlaybackOrder() {
        val device = Track("content://media/external/audio/media/1", "Morning Light", "Ana", "Blue Sky", 0)
        val nas = device.copy(uri = "reson-nas://profile/music/Morning%20Light.mp3")
        val other = device.copy(uri = "content://media/external/audio/media/2", album = "Dawn")
        val results = listOf(device, nas, other).matching(" BLUE   light ")

        assertEquals(listOf(device, nas), results)
        assertEquals(PlaybackOrder(results, 1), PlaybackOrder.fromLibrary(results, nas.uri))
    }

    @Test
    fun groupsCompilationTracksByAlbumArtistAndKeepsDifferentAlbumsSeparate() {
        val first = Track("one", "Zebra", "Ana", "Collected", 0, "Various Artists", trackNumber = 1)
        val second = Track("two", "Ant", "Ben", "collected", 0, "Various Artists", trackNumber = 2)
        val nextDisc = first.copy(uri = "three", title = "Bee", discNumber = 2)
        val differentAlbum = first.copy(uri = "four", albumArtist = null)
        val groups = listOf(second, nextDisc, differentAlbum, first).grouped(LibraryView.Albums)

        assertEquals(listOf("Ana", "Various Artists"), groups.map { it.artist })
        assertEquals(listOf(first, second, nextDisc), groups.last().tracks)
    }

    @Test
    fun artistGroupsUseTrackArtistAndFallBackToAlbumArtist() {
        val first = Track("one", "First", "Ana", "Collected", 0, "Various Artists")
        val second = Track("two", "Second", null, "Collected", 0, "Ana", artworkRevision = "2")
        val unknown = Track("three", "Third", null, null, 0)
        val groups = listOf(first, second, unknown).grouped(LibraryView.Artists)

        assertEquals(listOf("Ana", "Unknown artist"), groups.map { it.title })
        assertEquals(second, groups.first().cover)
        assertEquals(listOf(first, second), groups.first().tracks)
    }

    @Test
    fun artistGroupsMergeLatinAccentVariantsAndKeepTheAccentedDisplayName() {
        val plain = Track("one", "First", "Mylene Farmer", "Album", 0)
        val accented = plain.copy(uri = "two", artist = "Mylène Farmer")
        val decomposed = plain.copy(uri = "three", artist = null, albumArtist = "Myle\u0300ne Farmer")
        val tracks = listOf(plain, accented, decomposed)

        val artist = tracks.grouped(LibraryView.Artists).single()
        assertEquals("Mylène Farmer", artist.title)
        assertEquals(tracks, artist.tracks)
        assertEquals("Mylène Farmer", tracks.grouped(LibraryView.Albums).single().artist)
        assertEquals(tracks, tracks.matching("MYLENE farmer"))
        assertEquals(tracks, tracks.matching("mylène farmer"))
    }

    @Test
    fun unicodeMetadataKeepsItsDisplayTextAndNonLatinLettersStayDistinct() {
        val arabic = Track("one", "القمر", "أم كلثوم", null, 0)
        val cyrillic = Track("two", "Звезда", "Йона", null, 0)
        val chinese = Track("three", "月亮", "王菲", null, 0)
        val distinct = cyrillic.copy(uri = "four", artist = "Иона")
        val decomposed = cyrillic.copy(uri = "five", artist = "И\u0306она")
        val tracks = listOf(arabic, cyrillic, chinese, distinct, decomposed)

        val groups = tracks.grouped(LibraryView.Artists).associateBy(LibraryGroup::title)
        assertEquals(setOf("أم كلثوم", "Йона", "王菲", "Иона"), groups.keys)
        assertEquals(listOf(cyrillic, decomposed), groups.getValue("Йона").tracks)
        assertEquals(listOf(cyrillic, decomposed), tracks.matching("йона"))
        assertEquals(listOf(distinct), tracks.matching("иона"))
        assertEquals(listOf(arabic), tracks.matching("أم القمر"))
        assertEquals(listOf(chinese), tracks.matching("王菲 月亮"))
    }
}
