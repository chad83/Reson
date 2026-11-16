package com.reson.player.data.library

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.reson.player.domain.LocalTrackMetadata
import com.reson.player.domain.NasClient
import com.reson.player.domain.NasException
import com.reson.player.domain.NasPaths
import com.reson.player.domain.NasProblem
import com.reson.player.domain.NasProfile
import com.reson.player.domain.NasProfiles
import com.reson.player.domain.NasReadShare
import com.reson.player.domain.NasFileRevision
import com.reson.player.domain.Track
import com.reson.player.domain.TrackIdentity
import com.reson.player.domain.revision
import com.reson.player.domain.sha256
import com.reson.player.domain.sourceId
import com.reson.player.playback.NasTrackUri
import com.reson.player.playback.OfflineTrackUri
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class DownloadedTrack(
    val id: String, val originalUri: String, val source: String, val track: Track, val sizeBytes: Long,
    val manual: Boolean = false,
)

internal data class OfflineSnapshot(
    val copies: List<DownloadedTrack> = emptyList(),
    val tasks: List<LibraryDatabase.OfflineTask> = emptyList(),
    val partialBytes: Map<String, Long> = emptyMap(),
)

internal data class DownloadProgress(val taskId: String, val title: String, val bytes: Long, val totalBytes: Long) {
    val fraction: Float get() = (bytes.toDouble() / totalBytes.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
}

internal enum class TransferOutcome { Complete, Continue, RetryNas, Failed }

internal class OfflineDownloads(
    context: Context,
    private val library: MusicLibrary,
    private val profiles: NasProfiles,
    private val nas: NasClient,
) {
    private val dao = library.database.offline()
    private val directory = offlineDirectory(context)
    private val tags = TrackTagReader(context)
    private val work = WorkManager.getInstance(context)
    private val scheduling = Mutex()
    private val transfers = Mutex()
    private val publication = Any()
    private var migratedPartials = false
    private val revisions = MutableStateFlow(0L)
    val changes = revisions.asStateFlow()
    val jobs = work.getWorkInfosByTagFlow(WORK_TAG)
    private val activeProgress = MutableStateFlow<DownloadProgress?>(null)
    val progress = activeProgress.asStateFlow()

    suspend fun snapshot(): OfflineSnapshot = withContext(Dispatchers.IO) {
        val tasks = dao.tasks()
        OfflineSnapshot(
            copies = dao.copies().mapNotNull { copy ->
                if (offlineFile(directory, copy.fileName, copy.sizeBytes) == null) return@mapNotNull null
                copy.toDownloadedTrack()
            },
            tasks = tasks,
            partialBytes = tasks.associate { task ->
                task.id to File(directory, task.id).listFiles().orEmpty().sumOf { it.length() }
            },
        )
    }

    suspend fun download(uri: String, title: String, expectedSource: String? = null, manual: Boolean = true,
        replace: Boolean = false) = enqueue(uri.toUri(), title, folder = false, expectedSource = expectedSource,
        manual = manual, replace = replace)

    suspend fun keepFolder(profile: NasProfile, path: List<String>) =
        enqueue(
            NasTrackUri.create(profile.id, profile.share, path),
            "${profile.name} / ${(listOf(profile.share) + path).joinToString(" / ")}",
            folder = true,
            expectedSource = profile.sourceId(),
        )

    private suspend fun enqueue(uri: Uri, title: String, folder: Boolean, expectedSource: String? = null,
        manual: Boolean = false, replace: Boolean = false) =
        withContext(Dispatchers.IO) {
            scheduling.withLock {
                if (!(if (folder) NasTrackUri.acceptsFolder(uri) else NasTrackUri.accepts(uri))) {
                    throw NasException(NasProblem.Invalid)
                }
                val profile = profiles.login(requireNotNull(uri.authority)).profile
                val source = profile.sourceId()
                if (profile.share != uri.pathSegments.first() || (expectedSource != null && source != expectedSource)) {
                    throw NasException(NasProblem.SourceChanged)
                }
                val key = uri.toString()
                val task = synchronized(publication) {
                    if (manual) dao.protectCopy(key, source)
                    dao.taskFor(key, source, folder)?.also { if (manual) dao.protectTask(it.id) }
                        ?: LibraryDatabase.OfflineTask().apply {
                        id = UUID.randomUUID().toString()
                        this.uri = key
                        this.source = source
                        this.title = title
                        this.folder = folder
                        manualDownload = manual
                        dao.saveTask(this)
                    }
                }
                start(task, replace)
            }
        }

    suspend fun syncNow(id: String) = withContext(Dispatchers.IO) {
        scheduling.withLock {
            val task = dao.task(id) ?: return@withLock
            start(task, replace = true)
        }
    }

    private fun start(task: LibraryDatabase.OfflineTask, replace: Boolean = false) {
        dao.setError(task.id, null)
        try {
            if (task.folder) schedulePeriodic(task.id)
            scheduleOnce(task.id, replace)
        } catch (error: Exception) {
            dao.setError(task.id, "Couldn’t schedule this download.")
            throw error
        } finally {
            notifyChanged()
        }
    }

    suspend fun restoreSchedules() = withContext(Dispatchers.IO) {
        scheduling.withLock {
            synchronized(publication) {
                migratePartials()
                pruneFiles()
            }
            for (task in dao.tasks()) {
                if (task.folder) schedulePeriodic(task.id)
                if (task.lastSynced == 0L && task.error == null) scheduleOnce(task.id)
            }
        }
    }

    suspend fun stop(id: String) = withContext(Dispatchers.IO) {
        scheduling.withLock {
            synchronized(publication) {
                migratePartials()
                dao.deleteTask(id)
                discardParts(id)
            }
            notifyChanged()
            work.cancelAllWorkByTag(taskTag(id)).result.get()
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        synchronized(publication) {
            val copy = dao.copy(id) ?: return@synchronized
            removeCopy(copy)
        }
        notifyChanged(copies = true)
    }

    suspend fun cleanupPreview(playlistTracks: Set<TrackIdentity>): List<DownloadedTrack> = withContext(Dispatchers.IO) {
        synchronized(publication) { unusedCopies(playlistTracks).map { it.toDownloadedTrack() } }
    }

    suspend fun cleanup(ids: Set<String>, playlistTracks: Set<TrackIdentity>): Int = withContext(Dispatchers.IO) {
        var removed = 0
        try {
            synchronized(publication) {
                for (copy in unusedCopies(playlistTracks)) {
                    if (copy.id !in ids) continue
                    removeCopy(copy)
                    removed++
                }
            }
            removed
        } finally {
            if (removed > 0) notifyChanged(copies = true)
        }
    }

    private fun unusedCopies(playlistTracks: Set<TrackIdentity>): List<LibraryDatabase.OfflineInfo> {
        val tasks = dao.tasks()
        val individual = tasks.filterNot { it.folder }.mapTo(hashSetOf()) { TrackIdentity(it.uri, it.source) }
        val folders = tasks.filter { it.folder }.groupBy { it.source }
            .mapValues { (_, roots) -> roots.map { it.uri.toUri().pathSegments } }
        return dao.copies().filter { copy ->
            val track = copy.track
            val identity = TrackIdentity(track.uri, track.source)
            !copy.manualDownload && identity !in individual && identity !in playlistTracks &&
                offlineFile(directory, copy.fileName, copy.sizeBytes) != null && folders[track.source].orEmpty().none { root ->
                    val path = track.uri.toUri().pathSegments
                    path.size > root.size && root.indices.all { path[it] == root[it] }
                }
        }
    }

    private fun removeCopy(copy: LibraryDatabase.OfflineInfo) {
        val file = offlineFile(directory, copy.fileName, copy.sizeBytes)
        if (file != null && !file.delete() && file.exists()) throw IOException("Could not remove download")
        library.database.runInTransaction {
            dao.deleteCopy(copy.id)
            copy.track.artworkHash?.let(library.database.artwork()::prune)
        }
    }

    private fun LibraryDatabase.OfflineInfo.toDownloadedTrack() =
        DownloadedTrack(id, track.uri, track.source, toOfflineTrack(), sizeBytes, manualDownload)

    fun fileForPlayback(uri: Uri, expectedSource: String?): File? {
        val offlineId = OfflineTrackUri.id(uri)
        val copy = if (offlineId != null) {
            dao.copy(offlineId)
        } else {
            if (!NasTrackUri.accepts(uri) || expectedSource.isNullOrEmpty()) return null
            dao.copyFor(uri.toString(), expectedSource)
        } ?: return null
        return offlineFile(directory, copy.fileName, copy.sizeBytes)
    }

    suspend fun transfer(id: String): TransferOutcome = withContext(Dispatchers.IO) {
        transfers.withLock {
            val deadline = SystemClock.elapsedRealtime() + TRANSFER_SLICE_MS
            val task = synchronized(publication) {
                migratePartials()
                dao.task(id)
            } ?: return@withLock TransferOutcome.Complete
            val coroutine = currentCoroutineContext()
            val checkActive = {
                coroutine.ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) throw TransferSliceEnded()
            }
            var pendingChanges = false
            var nextNotification = 0L
            val copySaved = {
                pendingChanges = true
                val now = SystemClock.elapsedRealtime()
                if (now >= nextNotification) {
                    notifyChanged(copies = true)
                    pendingChanges = false
                    nextNotification = now + COPY_NOTIFICATION_INTERVAL_MS
                }
            }
            try {
                checkActive()
                val uri = task.uri.toUri()
                val login = profiles.login(requireNotNull(uri.authority))
                if (login.profile.sourceId() != task.source) throw NasException(NasProblem.Missing)
                nas.readShare(login) { share ->
                    if (task.folder) {
                        syncFolder(task, share, checkActive, copySaved)
                    } else {
                        transferFile(task, share, uri.pathSegments.drop(1), task.uri, null, checkActive, copySaved)
                        synchronized(publication) { dao.deleteTask(id) }
                    }
                }
                TransferOutcome.Complete
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TransferSliceEnded) {
                TransferOutcome.Continue
            } catch (_: FileChangedDuringTransfer) {
                dao.setError(id, "A NAS file changed during the download. Try syncing again.")
                TransferOutcome.RetryNas
            } catch (_: EmptyAudioFile) {
                synchronized(publication) { discardParts(id) }
                dao.setError(id, "The NAS audio file is empty.")
                TransferOutcome.Failed
            } catch (error: NasException) {
                dao.setError(id, error.problem.description)
                if (error.problem in retryable) {
                    TransferOutcome.RetryNas
                } else {
                    synchronized(publication) { discardParts(id) }
                    TransferOutcome.Failed
                }
            } catch (_: IOException) {
                synchronized(publication) { discardParts(id) }
                dao.setError(id, "Couldn’t save the download. Free up storage and try again.")
                TransferOutcome.Failed
            } finally {
                activeProgress.value = null
                synchronized(publication) {
                    if (dao.task(id) == null) discardParts(id)
                }
                notifyChanged(copies = pendingChanges)
            }
        }
    }

    private fun syncFolder(
        task: LibraryDatabase.OfflineTask,
        share: NasReadShare,
        checkActive: () -> Unit,
        copySaved: () -> Unit,
    ) {
        val root = task.uri.toUri()
        synchronized(publication) {
            if (dao.task(task.id) == null) throw CancellationException()
            if (dao.nextSyncEntry(task.id) == null) {
                dao.addSyncEntries(listOf(LibraryDatabase.SyncEntry().apply {
                    taskId = task.id
                    uri = task.uri
                    folder = true
                }))
            }
        }

        while (true) {
            checkActive()
            val entry = dao.nextSyncEntry(task.id) ?: return
            val path = entry.uri.toUri().pathSegments.drop(1)
            var warning: String? = null
            val children = try {
                if (entry.folder) {
                    share.browse(path).filterNot { it.playlist }.map { child ->
                        LibraryDatabase.SyncEntry().apply {
                            taskId = task.id
                            uri = NasTrackUri.create(requireNotNull(root.authority), root.pathSegments.first(),
                                path + child.name).toString()
                            folder = child.directory
                            size = child.size
                            modified = child.lastWriteTime
                        }
                    }
                } else {
                    transferFile(task, share, path, entry.uri, NasFileRevision(entry.size, entry.modified),
                        checkActive, copySaved)
                    emptyList()
                }
            } catch (_: EmptyAudioFile) {
                warning = "Skipped empty audio file: ${path.last()}"
                emptyList()
            } catch (error: NasException) {
                if (entry.uri == task.uri || error.problem !in setOf(NasProblem.Missing, NasProblem.Access)) throw error
                warning = error.problem.description
                emptyList()
            }

            synchronized(publication) {
                if (dao.task(task.id) == null) throw CancellationException()
                val finished = library.database.runInTransaction<Boolean> {
                    if (children.isNotEmpty()) dao.addSyncEntries(children)
                    warning?.let { dao.setSyncWarning(task.id, it) }
                    dao.deleteSyncEntry(task.id, entry.uri)
                    (dao.nextSyncEntry(task.id) == null).also {
                        if (it) dao.markSynced(task.id, System.currentTimeMillis())
                    }
                }
                if (finished) return
            }
        }
    }

    private fun transferFile(
        task: LibraryDatabase.OfflineTask,
        share: NasReadShare,
        path: List<String>,
        uri: String,
        listing: NasFileRevision?,
        checkActive: () -> Unit,
        copySaved: () -> Unit,
    ) {
        checkActive()
        val id = sha256("${task.source}\u0000$uri")
        val current = dao.copy(id)
        if (listing != null && current.matches(listing)) return
        share.openForDownload(path).use { remote ->
            val revision = remote.revision()
            if (revision.size == 0L) throw EmptyAudioFile()
            if (current.matches(revision)) return
            val name = "${id}_${sha256(revision.stamp)}"
            val partialDirectory = File(directory, task.id)
            val partial = File(partialDirectory, "$name.part")
            synchronized(publication) {
                checkActive()
                if (dao.task(task.id) == null) throw CancellationException()
                if (!partialDirectory.isDirectory && !partialDirectory.mkdirs()) {
                    throw IOException("Could not create download directory")
                }
                partialDirectory.listFiles()?.filter { it != partial }?.forEach { it.delete() }
                if (partial.length() > revision.size && !partial.delete()) {
                    throw IOException("Could not remove invalid partial download")
                }
                checkDownloadSpace(partialDirectory, revision.size - partial.length())
            }
            try {
                val title = LocalTrackMetadata.title(null, path.last())
                var nextProgress = 0L
                copyToPartial(remote, revision.size, partial,
                    checkSpace = { checkDownloadSpace(partialDirectory, it) },
                    onProgress = { bytes ->
                        val now = SystemClock.elapsedRealtime()
                        if (now >= nextProgress || bytes == revision.size) {
                            activeProgress.value = DownloadProgress(task.id, title, bytes, revision.size)
                            nextProgress = now + 500L
                        }
                    },
                    checkActive = checkActive,
                )
            } catch (error: EOFException) {
                throw NasException(NasProblem.Unreachable, error)
            }
            if (remote.revision() != revision) {
                partial.delete()
                throw FileChangedDuringTransfer()
            }
            checkActive()
            val fallback = Track(uri, LocalTrackMetadata.title(null, path.last()), null, null, 0)
            val tagged = tags.downloaded(fallback, partial)
            synchronized(publication) {
                checkActive()
                val active = dao.task(task.id) ?: throw CancellationException()
                val currentSource = profiles.login(requireNotNull(uri.toUri().authority)).profile.sourceId()
                if (currentSource != task.source) throw NasException(NasProblem.Missing)
                val previous = dao.copy(id)
                val fileName = "$name.audio"
                Files.move(partial.toPath(), File(directory, fileName).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                val artwork = tagged.artwork?.let(::artworkRecord)
                val copy = LibraryDatabase.OfflineCopy().apply {
                    info.id = id
                    info.fileName = fileName
                    info.sizeBytes = revision.size
                    info.manualDownload = active.manualDownload || previous?.manualDownload == true
                    info.track.apply {
                        this.uri = uri
                        source = task.source
                        this.revision = revision.stamp
                        title = tagged.track.title
                        artist = tagged.track.artist
                        album = tagged.track.album
                        albumArtist = tagged.track.albumArtist
                        durationMs = tagged.track.durationMs
                        addedAt = previous?.track?.addedAt ?: System.currentTimeMillis()
                        trackNumber = tagged.track.trackNumber
                        discNumber = tagged.track.discNumber
                        artworkHash = artwork?.hash
                    }
                }
                library.database.runInTransaction {
                    artwork?.let(library.database.artwork()::save)
                    dao.saveCopy(copy)
                    previous?.track?.artworkHash?.takeIf { it != artwork?.hash }
                        ?.let(library.database.artwork()::prune)
                }
                if (previous != null && previous.fileName != fileName) {
                    offlineFile(directory, previous.fileName, previous.sizeBytes)?.delete()
                }
            }
            copySaved()
            activeProgress.value = null
        }
    }

    private fun LibraryDatabase.OfflineInfo?.matches(revision: NasFileRevision): Boolean =
        this != null && track.revision == revision.stamp && offlineFile(directory, fileName, sizeBytes) != null

    private fun pruneFiles() {
        val copies = dao.copies().map { it.fileName }.toSet()
        val taskIds = dao.tasks().map { it.id }.toSet()
        directory.listFiles()?.forEach {
            when {
                it.isDirectory && NasPaths.validId(it.name) && it.name !in taskIds -> it.deleteRecursively()
                it.extension == "audio" && it.name !in copies -> it.delete()
                it.extension == "part" && it.name.substringBefore('_') !in taskIds -> it.delete()
            }
        }
    }

    private fun migratePartials() {
        if (migratedPartials) return
        directory.listFiles()?.forEach { legacy ->
            if (!legacy.isFile || legacy.extension != "part") return@forEach
            val id = legacy.name.substringBefore('_')
            if (!NasPaths.validId(id) || dao.task(id) == null) {
                legacy.delete()
                return@forEach
            }
            val target = File(directory, id)
            if (target.isDirectory || target.mkdirs()) {
                val partial = File(target, legacy.name.substringAfter('_'))
                if (partial.exists()) legacy.delete() else legacy.renameTo(partial)
            }
        }
        migratedPartials = true
    }

    private fun discardParts(task: String) {
        File(directory, task).deleteRecursively()
    }

    private fun notifyChanged(copies: Boolean = false) {
        revisions.update { it + 1 }
        if (copies) library.notifyChanged()
    }

    private fun scheduleOnce(id: String, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<OfflineWorker>()
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(TASK_KEY to id))
            .addTag(WORK_TAG)
            .addTag(taskTag(id))
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        work.enqueueUniqueWork(taskTag(id), policy, request).result.get()
    }

    private fun schedulePeriodic(id: String) {
        val request = PeriodicWorkRequestBuilder<OfflineWorker>(12, TimeUnit.HOURS)
            .setInitialDelay(12, TimeUnit.HOURS)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(TASK_KEY to id, PERIODIC_KEY to true))
            .addTag(WORK_TAG)
            .addTag(taskTag(id))
            .addTag(PERIODIC_TAG)
            .build()
        work.enqueueUniquePeriodicWork("periodic:${taskTag(id)}", ExistingPeriodicWorkPolicy.KEEP, request)
            .result.get()
    }

    private class TransferSliceEnded : IOException()
    private class FileChangedDuringTransfer : IOException()
    private class EmptyAudioFile : IOException()

    companion object {
        const val TASK_KEY = "offlineTask"
        const val PERIODIC_KEY = "periodic"
        const val PERIODIC_TAG = "offline-periodic"
        private const val WORK_TAG = "reson-offline"
        private const val COPY_NOTIFICATION_INTERVAL_MS = 2_000L
        private const val TRANSFER_SLICE_MS = 5 * 60 * 1000L
        val networkConstraints = Constraints.Builder().setRequiredNetworkRequest(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build(),
            NetworkType.CONNECTED,
        ).build()
        private val retryable = setOf(NasProblem.NoLan, NasProblem.Unreachable, NasProblem.Unresolved, NasProblem.SocketBlocked)
        fun taskTag(id: String) = "offline:$id"
    }
}
