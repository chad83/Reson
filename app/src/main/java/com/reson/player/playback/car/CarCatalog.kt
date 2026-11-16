package com.reson.player.playback.car

import com.reson.player.data.library.SavedPlaylist
import com.reson.player.domain.LibraryView
import com.reson.player.domain.Track
import com.reson.player.domain.TrackIdentity
import com.reson.player.domain.grouped
import com.reson.player.domain.librarySearchKey
import com.reson.player.domain.matching
import com.reson.player.domain.sha256

internal sealed interface CarItem {
    val id: String

    data class Folder(override val id: String, val title: String, val children: List<CarItem> = emptyList()) : CarItem
    data class Song(override val id: String, val track: Track) : CarItem
}

internal data class CarSelection(val songs: List<CarItem.Song>, val index: Int)

internal class CarCatalog(tracks: List<Track>, playlists: List<SavedPlaylist>, favourites: List<Track>) {
    val hasLibraryTracks = tracks.isNotEmpty()
    private val items = mutableMapOf<String, CarItem>()
    private val queues = mutableMapOf<String, List<CarItem.Song>>()
    private val primarySongs = linkedMapOf<TrackIdentity, CarItem.Song>()
    private val playlistSongs = mutableListOf<Pair<String, List<CarItem.Song>>>()
    private val albumSongs = mutableListOf<Pair<String, List<CarItem.Song>>>()
    private val artistSongs = mutableListOf<Pair<String, List<CarItem.Song>>>()
    private val downloads: List<CarItem.Song>

    init {
        songFolder("car:songs", "Songs", tracks)
        val albums = groupFolders(tracks, LibraryView.Albums, "album", albumSongs)
        val artists = groupFolders(tracks, LibraryView.Artists, "artist", artistSongs)
        folder(LIBRARY.id, LIBRARY.title, listOf(
            requireNotNull(items["car:songs"]),
            folder("car:albums", "Albums", albums),
            folder("car:artists", "Artists", artists),
        ))
        downloads = songFolder(DOWNLOADS.id, DOWNLOADS.title, tracks.filter(Track::downloaded))
        val playlistFolders = playlists.map { list ->
            val id = "car:playlist:${sha256(list.id)}"
            playlistSongs += list.title to songFolder(id, list.title, list.tracks)
            requireNotNull(items[id])
        }
        folder(PLAYLISTS.id, PLAYLISTS.title, playlistFolders)
        songFolder(FAVOURITES.id, FAVOURITES.title, favourites)
        folder(ROOT.id, ROOT.title, ROOT_CHILDREN.map { requireNotNull(items[it.id]) })
    }

    fun item(id: String): CarItem? = items[id]

    fun select(id: String): CarSelection? {
        val songs = queues[id] ?: return null
        val index = songs.indexOfFirst { it.id == id }
        return if (index < 0) null else CarSelection(songs, index)
    }

    fun defaultSelection(recent: List<TrackIdentity>, allowFirstTrack: Boolean): CarSelection? {
        val song = recent.firstNotNullOfOrNull { primarySongs[it] }
            ?: if (allowFirstTrack) downloads.firstOrNull() ?: primarySongs.values.firstOrNull() else null
        return song?.let { select(it.id) }
    }

    fun search(query: String): List<CarItem.Song> {
        val term = query.trim().take(MAX_QUERY)
        val songs = primarySongs.values
        val matchingIds = songs.map(CarItem.Song::track).matching(term).mapTo(hashSetOf(), Track::identity)
        val matched = songs.filter { it.track.identity in matchingIds }.sortedByDescending {
            it.track.title.equals(term, ignoreCase = true)
        }
        val matchingPlaylists = if (term.isEmpty()) emptyList() else playlistSongs
            .filter { (name, _) -> name.contains(term, ignoreCase = true) }
            .flatMap { it.second }
        return (matched + matchingPlaylists).distinctBy { it.track.identity }.take(MAX_CHILDREN)
    }

    fun voiceSelection(query: String, playlist: String?, album: String?, artist: String?, title: String?): CarSelection? {
        fun exact(groups: List<Pair<String, List<CarItem.Song>>>, name: String?): CarSelection? =
            name?.takeIf(String::isNotBlank)?.let { term ->
                groups.firstOrNull { it.first.equals(term, ignoreCase = true) }
                    ?.second?.firstOrNull()?.let { select(it.id) }
            }

        fun exactArtist(name: String?): CarSelection? {
            val key = name?.trim()?.takeIf(String::isNotEmpty)?.librarySearchKey() ?: return null
            return artistSongs.firstOrNull { it.first.librarySearchKey().equals(key, ignoreCase = true) }
                ?.second?.firstOrNull()?.let { select(it.id) }
        }

        if (!title.isNullOrBlank()) {
            search(title).firstOrNull()?.let { return select(it.id) }
        }
        return exact(playlistSongs, playlist) ?: exact(albumSongs, album) ?: exactArtist(artist)
            ?: exact(playlistSongs, query) ?: exact(albumSongs, query) ?: exactArtist(query)
            ?: query.takeIf(String::isNotBlank)?.let { search(it).firstOrNull() }?.let { select(it.id) }
    }

    private fun groupFolders(
        tracks: List<Track>,
        view: LibraryView,
        type: String,
        voiceGroups: MutableList<Pair<String, List<CarItem.Song>>>,
    ): List<CarItem> = tracks.grouped(view).map { group ->
        val id = "car:$type:${sha256(group.key)}"
        val display = listOfNotNull(group.title, group.artist).joinToString(" · ")
        voiceGroups += group.title to songFolder(id, display, group.tracks)
        requireNotNull(items[id])
    }

    private fun songFolder(id: String, title: String, tracks: List<Track>): List<CarItem.Song> {
        val songs = tracks.distinctBy(Track::identity).map { track ->
            val key = sha256("$id\u0000${track.source}\u0000${track.originalUri}")
            CarItem.Song("car:track:$key", track).also { song ->
                items[song.id] = song
                primarySongs.putIfAbsent(track.identity, song)
            }
        }
        folder(id, title, songs)
        return songs
    }

    // Car hosts may request every child at once, so large lists get visible subfolders.
    private fun folder(id: String, title: String, children: List<CarItem>): CarItem.Folder {
        val shown = if (children.size <= MAX_CHILDREN) {
            val songs = children.filterIsInstance<CarItem.Song>()
            for (song in songs) queues[song.id] = songs
            children
        } else {
            val perPage = maxOf(MAX_CHILDREN, (children.size - 1) / MAX_CHILDREN + 1)
            children.chunked(perPage).mapIndexed { page, chunk ->
                val start = page * perPage + 1
                val end = start + chunk.size - 1
                val label = if (start == end) "$start" else "$start–$end"
                folder("$id:page:${page + 1}", label, chunk)
            }
        }
        return CarItem.Folder(id, title, shown).also { items[id] = it }
    }

    companion object {
        const val MAX_CHILDREN = 100
        private const val MAX_QUERY = 200
        val ROOT = CarItem.Folder("car:root", "Reson")
        val LIBRARY = CarItem.Folder("car:library", "Library")
        val DOWNLOADS = CarItem.Folder("car:downloads", "Downloads")
        val PLAYLISTS = CarItem.Folder("car:playlists", "Playlists")
        val FAVOURITES = CarItem.Folder("car:favourites", "Favourites")
        val ROOT_CHILDREN = listOf(LIBRARY, DOWNLOADS, PLAYLISTS, FAVOURITES)
    }
}
