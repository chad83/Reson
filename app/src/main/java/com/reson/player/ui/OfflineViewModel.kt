package com.reson.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.reson.player.ResonApplication
import com.reson.player.data.library.DownloadedTrack
import com.reson.player.data.library.DownloadProgress
import com.reson.player.data.library.LibraryDatabase
import com.reson.player.data.library.OfflineDownloads
import com.reson.player.data.library.OfflineSnapshot
import com.reson.player.data.library.PlaylistDownloads
import com.reson.player.domain.NasException
import com.reson.player.domain.NasProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class OfflineUiState(
    val data: OfflineSnapshot = OfflineSnapshot(),
    val jobs: List<WorkInfo> = emptyList(),
    val loading: Boolean = true,
    val readError: Boolean = false,
    val notice: String? = null,
    val progress: DownloadProgress? = null,
    val cleanupPreview: List<DownloadedTrack>? = null,
) {
    val queued: Int get() = jobs.count { it.state != WorkInfo.State.RUNNING && OfflineDownloads.PERIODIC_TAG !in it.tags }
    val transferring: Boolean get() = jobs.any { it.state == WorkInfo.State.RUNNING }
    fun copyFor(uri: String, source: String): DownloadedTrack? =
        data.copies.find { it.originalUri == uri && it.source == source }

    fun taskFor(uri: String, source: String, folder: Boolean): LibraryDatabase.OfflineTask? =
        data.tasks.find { it.uri == uri && it.source == source && it.folder == folder }
}

internal class OfflineViewModel(private val downloads: OfflineDownloads, private val playlists: PlaylistDownloads) : ViewModel() {
    private val _state = MutableStateFlow(OfflineUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            downloads.changes.collectLatest {
                try {
                    val snapshot = downloads.snapshot()
                    _state.update { it.copy(data = snapshot, loading = false, readError = false) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(loading = false, readError = true) }
                }
            }
        }
        viewModelScope.launch {
            downloads.jobs.collect { jobs ->
                _state.update { it.copy(jobs = jobs.filterNot { job -> job.state.isFinished }) }
            }
        }
        viewModelScope.launch {
            downloads.progress.collect { progress -> _state.update { it.copy(progress = progress) } }
        }
        act("Couldn’t restore download and sync jobs.") { downloads.restoreSchedules() }
    }

    fun download(uri: String, title: String, source: String? = null) =
        act("Couldn’t queue the download.", "Download queued") { downloads.download(uri, title, source) }

    fun keepFolder(profile: NasProfile, path: List<String>) =
        act("Couldn’t schedule folder sync.", "Folder sync queued") { downloads.keepFolder(profile, path) }

    fun syncNow(id: String) = act("Couldn’t restart the transfer.") { downloads.syncNow(id) }

    fun stop(id: String) = act("Couldn’t stop the transfer.") { downloads.stop(id) }

    fun remove(id: String) = act("Couldn’t remove the download.") { downloads.remove(id) }

    fun previewCleanup() = act("Couldn’t check which downloads can be removed.") {
        val candidates = playlists.cleanupPreview()
        _state.update { it.copy(cleanupPreview = candidates) }
    }

    fun dismissCleanup() { _state.update { it.copy(cleanupPreview = null) } }

    fun cleanup(ids: Set<String>) = act("Couldn’t remove all selected downloads.") {
        dismissCleanup()
        val removed = playlists.cleanup(ids)
        val notice = when (removed) {
            0 -> "No downloads were removed."
            1 -> "Removed 1 unused download."
            else -> "Removed $removed unused downloads."
        }
        _state.update { it.copy(notice = notice) }
    }

    fun clearNotice(message: String) {
        _state.update { if (it.notice == message) it.copy(notice = null) else it }
    }

    private fun act(errorMessage: String, successMessage: String? = null, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                if (successMessage != null) _state.update { it.copy(notice = successMessage) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? NasException)?.problem?.description ?: errorMessage
                _state.update { it.copy(notice = message) }
            }
        }
    }

    companion object {
        fun factory(application: ResonApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    modelClass.cast(OfflineViewModel(application.offline, application.playlistDownloads))!!
            }
    }
}
