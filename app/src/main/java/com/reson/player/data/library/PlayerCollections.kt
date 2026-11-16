package com.reson.player.data.library

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.reson.player.domain.Track
import com.reson.player.domain.TrackIdentity
import com.reson.player.domain.LibraryView
import com.reson.player.domain.grouped
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SmartPlaylist(val id: String, val title: String, val description: String) {
    UnplayedFavourites("smart:favourites", "Favourites I haven’t played recently", "Favourites absent from the last 30 days of playback history."),
    RecentlyAdded("smart:recent-albums", "Recently added albums", "Up to 10 albums with tracks added to the library in the last 30 days."),
}

internal data class SavedPlaylist(val id: String, val title: String, val tracks: List<Track>, val rule: SmartPlaylist? = null)
internal data class PlaybackVisit(val id: Long, val playedAt: Long, val track: Track)

internal data class CollectionSnapshot(
    val playlists: List<SavedPlaylist> = emptyList(),
    val favourites: List<Track> = emptyList(),
    val history: List<PlaybackVisit> = emptyList(),
) {
    val favouriteIds = favourites.mapTo(hashSetOf(), Track::identity)
}

internal class PlayerCollections(private val library: MusicLibrary, private val canReadDevice: () -> Boolean) {
    private val database = library.database
    private val dao = database.collections()
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val revisions = MutableStateFlow(0L)
    val changes = revisions.asStateFlow()
    private val listRevisions = MutableStateFlow(0L)
    val listChanges = listRevisions.asStateFlow()
    private val historyRevisions = MutableStateFlow(0L)
    val historyChanges = historyRevisions.asStateFlow()
    val smartChanges = combine(library.indexChanges, historyRevisions) { index, history -> index to history }

    suspend fun recentTracks(): List<TrackIdentity> = withContext(io) {
        dao.history().map { TrackIdentity(it.track.uri, it.track.source) }
    }

    suspend fun snapshot(createSmartLists: Boolean = true): CollectionSnapshot = withContext(io) {
        val entries = dao.entries().groupBy { it.playlistId }
        val favourites = entries[FAVOURITES].orEmpty().map { it.track.toTrack() }
        val history = dao.history().map { PlaybackVisit(it.id, it.playedAt, it.track.toTrack()) }
        val smart = if (createSmartLists) smartPlaylists(favourites, history, library.tracks(canReadDevice()),
            System.currentTimeMillis()) else emptyList()
        CollectionSnapshot(
            playlists = dao.playlists().filterNot { it.id == FAVOURITES }.map { list ->
                SavedPlaylist(list.id, list.title, entries[list.id].orEmpty().map { it.track.toTrack() })
            } + smart,
            favourites = favourites,
            history = history,
        )
    }

    suspend fun create(title: String, track: Track? = null) = edit {
        val id = UUID.randomUUID().toString()
        insertPlaylist(id, title)
        if (track != null) insertTrack(id, track)
    }

    suspend fun importPlaylist(title: String, tracks: List<Track>) = edit {
        val id = UUID.randomUUID().toString()
        insertPlaylist(id, title)
        tracks.forEach { insertTrack(id, it) }
    }

    suspend fun rename(id: String, title: String) = edit {
        if (id != FAVOURITES) dao.renamePlaylist(id, validTitle(title))
    }

    suspend fun delete(id: String) = edit {
        if (id != FAVOURITES) dao.deletePlaylist(id)
    }

    suspend fun add(id: String, track: Track) = edit {
        if (dao.hasPlaylist(id)) insertTrack(id, track)
    }

    suspend fun remove(id: String, track: TrackIdentity) = edit {
        dao.deleteEntry(id, track.uri, track.source)
    }

    suspend fun setFavourite(track: Track, favourite: Boolean) = edit {
        if (favourite) {
            insertPlaylist(FAVOURITES, "Favourites")
            insertTrack(FAVOURITES, track)
        } else {
            dao.deleteEntry(FAVOURITES, track.originalUri, track.source)
        }
    }

    suspend fun move(id: String, track: TrackIdentity, offset: Int) = edit {
        val entries = dao.entries(id)
        val from = entries.indexOfFirst { it.track.uri == track.uri && it.track.source == track.source }
        if (from < 0) return@edit
        val entry = entries[from]
        val adjacent = entries.getOrNull(from + offset) ?: return@edit
        dao.moveEntry(id, track.uri, track.source, adjacent.position)
        dao.moveEntry(id, adjacent.track.uri, adjacent.track.source, entry.position)
    }

    fun recordPlayback(track: Track, playedAt: Long) {
        scope.launch {
            try {
                edit(listsChanged = false) {
                    dao.insertHistory(CollectionTables.History().apply {
                        this.playedAt = playedAt
                        this.track = track.toSavedTrack()
                    })
                    dao.trimHistory(HISTORY_LIMIT - 1)
                }
            } catch (_: RuntimeException) {
                Log.w("Reson", "Could not record playback history")
            }
        }
    }

    suspend fun clearHistory() = edit(listsChanged = false) { dao.clearHistory() }

    private suspend fun edit(listsChanged: Boolean = true, change: () -> Unit) = withContext(io) {
        database.runInTransaction(change)
        revisions.update { it + 1 }
        if (listsChanged) listRevisions.update { it + 1 }
        else historyRevisions.update { it + 1 }
    }

    private fun insertPlaylist(id: String, title: String) {
        dao.insertPlaylist(CollectionTables.Playlist().apply {
            this.id = id
            this.title = validTitle(title)
        })
    }

    private fun insertTrack(id: String, track: Track) {
        dao.insertEntry(CollectionTables.Entry().apply {
            playlistId = id
            this.track = track.toSavedTrack()
            position = dao.nextPosition(id)
        })
    }

    private fun validTitle(title: String): String = title.trim().also { require(it.isNotEmpty() && it.length <= 100) }

    companion object {
        const val FAVOURITES = "favourites"
        private const val HISTORY_LIMIT = 200
    }
}

internal fun smartPlaylists(favourites: List<Track>, history: List<PlaybackVisit>, library: List<Track>, now: Long): List<SavedPlaylist> {
    val recent = now - 30L * 24 * 60 * 60 * 1_000
    val played = history.filter { it.playedAt >= recent }.mapTo(hashSetOf()) { it.track.identity }
    val unplayed = favourites.filterNot { it.identity in played }
    val albums = library.filter { it.album != null }.grouped(LibraryView.Albums)
        .map { it to it.tracks.maxOf(Track::addedAt) }
        .filter { (_, addedAt) -> addedAt > 0 && addedAt >= recent }
        .sortedByDescending { it.second }.take(10).flatMap { it.first.tracks }
    return listOf(SmartPlaylist.UnplayedFavourites to unplayed, SmartPlaylist.RecentlyAdded to albums)
        .map { (rule, tracks) -> SavedPlaylist(rule.id, rule.title, tracks, rule) }
}

private fun Track.toSavedTrack() = CollectionTables.SavedTrack().also { saved ->
    saved.uri = originalUri
    saved.source = source
    saved.title = title
    saved.artist = artist
    saved.album = album
    saved.albumArtist = albumArtist
    saved.trackNumber = trackNumber
    saved.discNumber = discNumber
    saved.durationMs = durationMs
}

private fun CollectionTables.SavedTrack.toTrack() =
    Track(uri, title, artist, album, durationMs, albumArtist, trackNumber, discNumber, source = source)

internal val COLLECTIONS_MIGRATION = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val trackColumns = """
            uri TEXT NOT NULL, source TEXT NOT NULL, title TEXT NOT NULL, artist TEXT, album TEXT,
            albumArtist TEXT, trackNumber INTEGER, discNumber INTEGER, durationMs INTEGER NOT NULL
        """.trimIndent()
        db.execSQL("CREATE TABLE player_playlists (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)")
        db.execSQL("""
            CREATE TABLE player_playlist_tracks (
                playlistId TEXT NOT NULL, position INTEGER NOT NULL, $trackColumns,
                PRIMARY KEY(playlistId, uri, source),
                FOREIGN KEY(playlistId) REFERENCES player_playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE player_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, playedAt INTEGER NOT NULL, $trackColumns
            )
        """.trimIndent())
    }
}
