package com.reson.player.data.library

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.reson.player.ResonApplication
import com.reson.player.domain.NasException
import com.reson.player.domain.Track
import com.reson.player.domain.TrackIdentity
import com.reson.player.playback.NasTrackUri
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

internal class PlaylistDownloads(
    context: Context,
    private val collections: PlayerCollections,
    private val downloads: OfflineDownloads,
) {
    private val preferences = context.getSharedPreferences("playlist_downloads", Context.MODE_PRIVATE)
    private val enabledIds = MutableStateFlow(preferences.getStringSet("playlists", null).orEmpty().toSet())
    val enabled = enabledIds.asStateFlow()
    private val trackFailures = MutableStateFlow<Map<TrackIdentity, String>>(emptyMap())
    val failures = trackFailures.asStateFlow()
    private val work = WorkManager.getInstance(context)
    private val lock = Mutex()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
            merge(collections.listChanges.drop(1).map { Unit }, collections.smartChanges.drop(1)
                .filter { enabledIds.value.any { it.startsWith("smart:") } }.map { Unit }).collect {
                try {
                    sync()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("Reson", "Could not schedule offline playlists", error)
                }
            }
        }
    }

    suspend fun setEnabled(id: String, keepOffline: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val ids = if (keepOffline) enabledIds.value + id else enabledIds.value - id
            save(ids)
            schedule(replace = keepOffline)
        }
    }

    suspend fun sync(replace: Boolean = true) = withContext(Dispatchers.IO) {
        lock.withLock { schedule(replace) }
    }

    suspend fun retry(id: String, track: Track) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (id in enabledIds.value) enqueue(track, replace = true)
        }
    }

    suspend fun cleanupPreview(): List<DownloadedTrack> = withContext(Dispatchers.IO) {
        lock.withLock { downloads.cleanupPreview(coveredTracks()) }
    }

    suspend fun cleanup(ids: Set<String>): Int = withContext(Dispatchers.IO) {
        lock.withLock { downloads.cleanup(ids, coveredTracks()) }
    }

    private suspend fun coveredTracks(): Set<TrackIdentity> {
        val ids = enabledIds.value
        if (ids.isEmpty()) return emptySet()
        return collections.snapshot(includeSmart(ids)).playlists.asSequence().filter { it.id in ids }
            .flatMap { it.tracks.asSequence() }.mapTo(hashSetOf(), Track::identity)
    }

    private fun includeSmart(ids: Set<String>): Boolean = SmartPlaylist.entries.any { it.id in ids }

    internal suspend fun enqueueMissing() = withContext(Dispatchers.IO) {
        val playlists = lock.withLock {
            val saved = collections.snapshot(includeSmart(enabledIds.value)).playlists
            val ids = enabledIds.value.intersect(saved.mapTo(hashSetOf(), SavedPlaylist::id))
            if (ids != enabledIds.value) save(ids)
            saved.filter { it.id in ids }
        }
        val seen = hashSetOf<TrackIdentity>()
        for (playlist in playlists) {
            for (track in playlist.tracks) {
                currentCoroutineContext().ensureActive()
                val uri = track.originalUri.toUri()
                if (!NasTrackUri.accepts(uri)) continue
                lock.withLock {
                    if (playlist.id !in enabledIds.value || !seen.add(track.identity) ||
                        downloads.fileForPlayback(uri, track.source) != null
                    ) return@withLock
                    enqueue(track)
                }
            }
        }
        trackFailures.update { failures -> failures.filterKeys { it in seen } }
    }

    private suspend fun enqueue(track: Track, replace: Boolean = false) {
        try {
            downloads.download(track.originalUri, track.title, track.source, manual = false, replace = replace)
            trackFailures.update { it - track.identity }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = (error as? NasException)?.problem?.description ?: "Couldn’t queue this download."
            trackFailures.update { it + (track.identity to reason) }
        }
    }

    private fun save(ids: Set<String>) {
        check(preferences.edit().putStringSet("playlists", ids).commit())
        enabledIds.value = ids
    }

    private fun schedule(replace: Boolean) {
        if (enabledIds.value.isEmpty()) {
            work.cancelUniqueWork(SYNC_NOW).result.get()
            work.cancelUniqueWork(PERIODIC_SYNC).result.get()
            return
        }
        val immediate = OneTimeWorkRequestBuilder<PlaylistDownloadWorker>()
            .setConstraints(OfflineDownloads.networkConstraints).build()
        val periodic = PeriodicWorkRequestBuilder<PlaylistDownloadWorker>(12, TimeUnit.HOURS)
            .setConstraints(OfflineDownloads.networkConstraints).setInitialDelay(12, TimeUnit.HOURS).build()
        work.enqueueUniquePeriodicWork(PERIODIC_SYNC, ExistingPeriodicWorkPolicy.KEEP, periodic).result.get()
        work.enqueueUniqueWork(SYNC_NOW, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, immediate)
            .result.get()
    }

    private companion object {
        const val SYNC_NOW = "playlist-downloads"
        const val PERIODIC_SYNC = "playlist-downloads-periodic"
    }
}

class PlaylistDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as ResonApplication).playlistDownloads.enqueueMissing()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("Reson", "Could not queue offline playlist tracks", error)
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
