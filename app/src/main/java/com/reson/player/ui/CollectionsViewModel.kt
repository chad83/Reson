package com.reson.player.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.reson.player.ResonApplication
import com.reson.player.data.library.CollectionSnapshot
import com.reson.player.data.library.PlayerCollections
import com.reson.player.data.library.PlaylistDownloads
import com.reson.player.data.library.PlaylistFiles
import com.reson.player.domain.NasProfile
import com.reson.player.domain.Track
import com.reson.player.domain.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CollectionsState(
    val data: CollectionSnapshot = CollectionSnapshot(),
    val loading: Boolean = true,
    val readError: Boolean = false,
    val notice: String? = null,
)

internal class CollectionsViewModel(
    private val collections: PlayerCollections,
    private val files: PlaylistFiles,
    private val downloads: PlaylistDownloads,
) : ViewModel() {
    private val _state = MutableStateFlow(CollectionsState())
    private val refreshes = MutableStateFlow(0L)
    val state = _state.asStateFlow()
    val offlinePlaylists = downloads.enabled
    val offlineFailures = downloads.failures

    init {
        viewModelScope.launch {
            combine(collections.changes, collections.smartChanges, refreshes) { _, _, _ -> Unit }.collectLatest {
                try {
                    val snapshot = collections.snapshot()
                    _state.update { it.copy(data = snapshot, loading = false, readError = false) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(loading = false, readError = true) }
                }
            }
        }
        edit("Couldn’t restore offline playlist sync.") { downloads.sync(replace = false) }
    }

    fun refresh() {
        refreshes.update { it + 1 }
    }

    fun create(title: String, track: Track? = null) = edit { collections.create(title, track) }
    fun rename(id: String, title: String) = edit { collections.rename(id, title) }
    fun delete(id: String) = edit {
        collections.delete(id)
        if (id in offlinePlaylists.value) downloads.setEnabled(id, false)
    }
    fun add(id: String, track: Track) = edit { collections.add(id, track) }
    fun remove(id: String, track: TrackIdentity, onRemoved: (() -> Unit) -> Unit) = edit {
        collections.remove(id, track)?.let { entry ->
            onRemoved({ edit { collections.restore(entry) } })
        }
    }
    fun move(id: String, track: TrackIdentity, offset: Int) = edit { collections.move(id, track, offset) }
    fun setFavourite(track: Track, favourite: Boolean) = edit { collections.setFavourite(track, favourite) }
    fun clearHistory() = edit { collections.clearHistory() }

    fun keepOffline(id: String, enabled: Boolean) = edit("Couldn’t change offline playlist sync.") {
        downloads.setEnabled(id, enabled)
    }

    fun syncOffline() = edit("Couldn’t queue offline playlist sync.") { downloads.sync() }

    fun retryOffline(id: String, track: Track) = edit("Couldn’t retry this track.") { downloads.retry(id, track) }

    fun importPlaylist(uri: Uri) = edit("Couldn’t import this playlist.") { notice(files.importDocument(uri)) }

    fun importNasPlaylist(profile: NasProfile, folder: List<String>, name: String) = edit("Couldn’t import this NAS playlist.") {
        notice(files.importNas(profile, folder, name))
    }

    fun exportPlaylist(uri: Uri, id: String) = edit("Couldn’t export this playlist.") { notice(files.exportDocument(uri, id)) }

    fun clearNotice(message: String) {
        _state.update { if (it.notice == message) it.copy(notice = null) else it }
    }

    private fun notice(message: String) {
        _state.update { it.copy(notice = message) }
    }

    private fun edit(fallback: String = "Couldn’t save changes to your collections.", action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice(fallback)
            }
        }
    }

    companion object {
        fun factory(application: ResonApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    modelClass.cast(CollectionsViewModel(application.collections, application.playlistFiles,
                        application.playlistDownloads))!!
            }
    }
}
