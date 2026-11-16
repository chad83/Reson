package com.reson.player.playback.car

import androidx.core.net.toUri
import com.reson.player.ResonApplication
import com.reson.player.data.hasAudioReadPermission
import com.reson.player.data.library.SavedTrackResolver
import com.reson.player.data.library.smartPlaylists
import com.reson.player.domain.NasException
import com.reson.player.domain.Track
import com.reson.player.domain.sourceId
import com.reson.player.playback.NasTrackUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class CarCatalogSource(private val app: ResonApplication) {
    private data class Revision(val library: Long, val lists: Long, val history: Long, val device: Boolean, val nas: Boolean)

    private val lock = Mutex()
    private var cached: Pair<Revision, CarCatalog>? = null

    suspend fun current(): CarCatalog = withContext(Dispatchers.IO) {
        lock.withLock {
            val nasAvailable = try {
                app.localNasNetwork.requireAccess()
                true
            } catch (_: NasException) {
                false
            }
            val revision = Revision(
                app.library.changes.value, app.collections.listChanges.value,
                app.collections.historyChanges.value,
                app.hasAudioReadPermission(), nasAvailable,
            )
            cached?.takeIf { it.first == revision }?.let { return@withLock it.second }
            val tracks = app.library.tracks(revision.device)
            val sources = if (nasAvailable) {
                try {
                    app.nasProfiles.list().associate { it.id to it.sourceId() }
                } catch (_: NasException) {
                    emptyMap()
                }
            } else emptyMap()
            val resolver = SavedTrackResolver(tracks, sources)
            val saved = app.collections.snapshot(createSmartLists = false)
            val playlists = saved.playlists + smartPlaylists(saved.favourites, saved.history, tracks, System.currentTimeMillis())
            fun available(track: Track): Boolean =
                nasAvailable || track.downloaded || !NasTrackUri.accepts(track.uri.toUri())
            fun resolve(list: List<Track>): List<Track> =
                list.mapNotNull(resolver::resolve).filter(::available)
            val catalog = CarCatalog(
                tracks.filter(::available),
                playlists.map { it.copy(tracks = resolve(it.tracks)) },
                resolve(saved.favourites),
            )
            cached = revision to catalog
            catalog
        }
    }
}
