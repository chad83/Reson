package com.reson.player.playback.car

import com.reson.player.data.library.SavedPlaylist
import com.reson.player.domain.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarCatalogTest {
    @Test
    fun rootsAreBrowsableAndSelectingAnAlbumTrackKeepsAlbumOrder() {
        val first = track(1).copy(title = "Zebra", trackNumber = 1)
        val second = track(2).copy(title = "Ant", trackNumber = 2)
        val catalog = CarCatalog(listOf(second, first), emptyList(), emptyList())

        assertEquals(CarCatalog.ROOT_CHILDREN.map { it.id }, catalog.folder("car:root").children.map { it.id })
        val album = catalog.folder("car:albums").children.single() as CarItem.Folder
        val selected = requireNotNull(catalog.select(album.children[1].id))
        assertEquals(listOf(first, second), selected.songs.map { it.track })
        assertEquals(1, selected.index)
        assertNull(catalog.select(second.uri))
        assertTrue(selected.songs.none { it.id.contains("content:") })
    }

    @Test
    fun largeFoldersStayBrowsableWithoutClientPagination() {
        val catalog = CarCatalog((1..205).map(::track), emptyList(), emptyList())
        val pages = catalog.folder("car:songs").children.map { it as CarItem.Folder }

        assertEquals(listOf(100, 100, 5), pages.map { it.children.size })
        val selected = requireNotNull(catalog.select(pages.last().children.last().id))
        assertEquals(5, selected.songs.size)
        assertEquals(4, selected.index)
    }

    @Test
    fun voiceRequestsFindPlaylistsAndTagsAndCanResumeRecentMusic() {
        val first = track(1)
        val downloaded = track(2).copy(title = "Downloaded song", downloaded = true)
        val catalog = CarCatalog(
            listOf(first, downloaded),
            listOf(SavedPlaylist("saved", "Drive mix", listOf(downloaded, first))),
            listOf(first),
        )
        val playlist = catalog.voiceSelection("play it", "Drive mix", null, null, null)
        assertEquals(listOf(downloaded, first), playlist?.songs?.map { it.track })
        assertEquals(downloaded, catalog.search("downloaded Song").first().track)
        assertEquals(first, catalog.defaultSelection(listOf(first.identity), false)?.let { it.songs[it.index].track })
        assertEquals(downloaded, catalog.defaultSelection(emptyList(), true)?.let { it.songs[it.index].track })
        assertNull(catalog.defaultSelection(emptyList(), false))
    }

    @Test
    fun carTrackIdsKeepLogicalSourceIdentityAcrossStreamingAndDownloads() {
        val stream = Track("reson-nas://nas/share/a.mp3", "Song", null, null, 0, source = "server-a")
        val offline = stream.copy(uri = "reson-offline://download", downloaded = true)
        val other = stream.copy(source = "server-b")
        fun id(track: Track) = CarCatalog(listOf(track), emptyList(), emptyList()).folder("car:songs").children.single().id

        assertEquals(id(stream), id(offline))
        assertNotEquals(id(stream), id(other))
    }

    @Test
    fun anUnaccentedArtistVoiceRequestPlaysTheWholeAccentedArtistGroup() {
        val first = track(1).copy(artist = "Mylène Farmer")
        val second = track(2).copy(artist = "Mylene Farmer")
        val catalog = CarCatalog(listOf(first, second), emptyList(), emptyList())

        val result = catalog.voiceSelection("play it", null, null, "Mylene Farmer", null)

        assertEquals(listOf(first, second), result?.songs?.map { it.track })
    }

    @Test
    fun anUnknownVoiceHintWithABlankQueryDoesNotStartAnUnrelatedTrack() {
        val catalog = CarCatalog(listOf(track(1)), emptyList(), emptyList())

        assertNull(catalog.voiceSelection("", null, null, "Unknown artist", null))
        assertNull(catalog.voiceSelection(" ", "Unknown playlist", null, null, null))
    }

    private fun CarCatalog.folder(id: String) = item(id) as CarItem.Folder

    private fun track(number: Int) =
        Track("content://media/external/audio/media/$number", "Track $number", "Artist", "Album", 1_000)
}
