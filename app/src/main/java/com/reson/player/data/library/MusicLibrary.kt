package com.reson.player.data.library

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.reson.player.data.hasAudioReadPermission
import com.reson.player.domain.LocalLibrary
import com.reson.player.domain.LocalTrackMetadata
import com.reson.player.domain.NasClient
import com.reson.player.domain.NasException
import com.reson.player.domain.NasLogin
import com.reson.player.domain.NasProblem
import com.reson.player.domain.NasProfile
import com.reson.player.domain.NasProfiles
import com.reson.player.domain.Track
import com.reson.player.playback.NasTrackUri
import com.reson.player.domain.revision
import com.reson.player.domain.sourceId
import com.reson.player.domain.walkAudio
import com.reson.player.playback.OfflineTrackUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class DeviceScan { Scanning, Complete, Failed }

internal class MusicLibrary(
    context: Context,
    private val local: LocalLibrary,
    private val profiles: NasProfiles,
    private val nas: NasClient,
) {
    internal val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "music-library.db")
        .addMigrations(OFFLINE_MIGRATION, COLLECTIONS_MIGRATION, OFFLINE_PROGRESS_MIGRATION, ARTWORK_MIGRATION,
            NAS_FOLDERS_MIGRATION, OFFLINE_STORAGE_MIGRATION)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM library_tracks WHERE staged = 1")
                db.execSQL("DELETE FROM artwork WHERE " + LibraryDatabase.UNUSED_ARTWORK)
            }
        })
        .build()
    private val index = database.index()
    private val tags = TrackTagReader(context)
    private val directory = offlineDirectory(context)
    private val nasScan = Mutex()
    private val revisions = MutableStateFlow(0L)
    val changes = revisions.asStateFlow()
    private val indexRevisions = MutableStateFlow(0L)
    val indexChanges = indexRevisions.asStateFlow()
    private val deviceRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deviceScan = merge(local.changes, deviceRefresh).conflate().transform {
        if (!context.hasAudioReadPermission()) return@transform
        emit(DeviceScan.Scanning)
        try {
            scanDevice()
            emit(DeviceScan.Complete)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(DeviceScan.Failed)
        }
    }.retryWhen { cause, _ ->
        if (cause !is Exception) return@retryWhen false
        emit(DeviceScan.Failed)
        deviceRefresh.first()
        true
    }.shareIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.WhileSubscribed(), replay = 1)

    fun refreshDevice() {
        deviceRefresh.tryEmit(Unit)
    }

    fun notifyChanged() {
        revisions.update { it + 1 }
    }

    private fun notifyIndexChanged() {
        indexRevisions.update { it + 1 }
        notifyChanged()
    }

    suspend fun tracks(includeDevice: Boolean): List<Track> = withContext(Dispatchers.IO) {
        val sources = index.nasFolders().groupBy { it.profileId }.mapNotNull { (profileId, folders) ->
            try {
                profiles.login(profileId).profile.sourceId().takeIf { source -> folders.any { it.source == source } }
            } catch (_: NasException) {
                null
            }
        }.toSet()
        val copies = database.offline().copies().filter {
            offlineFile(directory, it.fileName, it.sizeBytes) != null
        }.associateBy { it.track.source to it.track.uri }.toMutableMap()
        val tracks = index.tracks().mapNotNull { entry ->
            if (entry.source !in sources && !(includeDevice && entry.source == DEVICE)) return@mapNotNull null
            val copy = copies.remove(entry.source to entry.uri)
            entry.toTrack(downloaded = copy != null)
        } + copies.values.map { it.toOfflineTrack() }
        tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Track::title).thenBy(Track::uri))
    }

    suspend fun artwork(uri: String, revision: String): ByteArray? = withContext(Dispatchers.IO) {
        val offlineId = OfflineTrackUri.id(uri.toUri())
        if (offlineId == null) index.artwork(uri, revision) else database.offline().artwork(offlineId, revision)
    }

    private suspend fun scanDevice() {
        val files = local.loadTracks()
        val coroutine = currentCoroutineContext()
        scan(DEVICE) { pending ->
            for ((track, revision) in files) {
                coroutine.ensureActive()
                pending.visit(track.uri, revision) { tags.device(track) }
            }
            coroutine.ensureActive()
        }
    }

    suspend fun scanNas(profile: NasProfile, folder: List<String>): Int = withContext(Dispatchers.IO) {
        nasScan.withLock {
            val login = profiles.login(profile.id)
            if (login.profile.sourceId() != profile.sourceId()) throw NasException(NasProblem.Missing)
            scanNasFolder(login, folder)
        }
    }

    suspend fun nasFolders(profile: NasProfile): List<List<String>> = withContext(Dispatchers.IO) {
        index.nasFolders(profile.id).filter { it.source == profile.sourceId() }
            .map { it.uri.toUri().pathSegments.drop(1) }.sortedBy { it.joinToString("/") }
    }

    suspend fun removeNasFolder(profile: NasProfile, folder: List<String>) = withContext(Dispatchers.IO) {
        nasScan.withLock {
            val source = profile.sourceId()
            val root = NasTrackUri.create(profile.id, profile.share, folder).toString()
            val folders = index.nasFolders(profile.id).filter { it.source == source }
            if (folders.none { it.uri == root }) return@withLock
            val remaining = folders.filterNot { it.uri == root }.map { it.uri.toUri().pathSegments }
            database.runInTransaction {
                index.deleteNasFolder(profile.id, root)
                for (track in index.tracks(source)) {
                    val path = track.uri.toUri().pathSegments
                    if (remaining.none { path.isWithin(it) }) index.deleteTrack(track.uri, source)
                }
                database.artwork().pruneUnused()
            }
            notifyIndexChanged()
        }
    }

    suspend fun refreshNas(): String? = withContext(Dispatchers.IO) {
        nasScan.withLock {
            var failure: String? = null
            for (folder in index.nasFolders()) {
                try {
                    val login = profiles.login(folder.profileId)
                    if (login.profile.sourceId() == folder.source) {
                        scanNasFolder(login, folder.uri.toUri().pathSegments.drop(1))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: NasException) {
                    failure = "Couldn’t refresh all NAS folders. ${error.problem.description}"
                }
            }
            failure
        }
    }

    suspend fun saveProfile(login: NasLogin) = updateProfile(login.profile.id) { profiles.save(login) }

    suspend fun removeProfile(id: String) = updateProfile(id) { profiles.remove(id) }

    private suspend fun updateProfile(id: String, update: () -> Unit) = withContext(Dispatchers.IO) {
        nasScan.withLock {
            update()
            try {
                val folders = index.nasFolders(id)
                if (folders.isNotEmpty()) {
                    val current = try {
                        profiles.login(id).profile.sourceId()
                    } catch (error: NasException) {
                        if (error.problem != NasProblem.Missing) throw error
                        null
                    }
                    database.runInTransaction { removeStaleNasFolders(id, current) }
                }
            } finally {
                notifyIndexChanged()
            }
        }
    }

    private fun removeStaleNasFolders(profileId: String, source: String?) {
        val stale = index.nasFolders(profileId).filter { it.source != source }
        if (stale.isEmpty()) return
        stale.map { it.source }.distinct().forEach(index::deleteSource)
        stale.forEach { index.deleteNasFolder(profileId, it.uri) }
        database.artwork().pruneUnused()
    }

    private suspend fun scanNasFolder(login: NasLogin, folder: List<String>): Int {
        val profile = login.profile
        val source = profile.sourceId()
        val scope = LibraryDatabase.NasFolder().apply {
            profileId = profile.id
            this.source = source
            uri = NasTrackUri.create(profile.id, profile.share, folder).toString()
        }
        val coroutine = currentCoroutineContext()
        return scan(source, scope) { pending ->
            nas.readShare(login) { share ->
                share.walkAudio(folder, coroutine::ensureActive) { path, entry ->
                    val uri = NasTrackUri.create(profile.id, profile.share, path).toString()
                    pending.visit(uri, entry.revision.stamp) {
                        val fallback = Track(uri, LocalTrackMetadata.title(null, entry.name), null, null, 0)
                        share.open(path).use { file -> tags.nas(fallback, file, coroutine) }
                    }
                }
            }
            coroutine.ensureActive()
        }
    }

    private fun scan(
        source: String,
        folder: LibraryDatabase.NasFolder? = null,
        collect: (PendingScan) -> Unit,
    ): Int {
        val root = folder?.uri?.toUri()?.pathSegments
        val current = index.tracks(source).filter { root == null || it.uri.toUri().pathSegments.isWithin(root) }
        val pending = PendingScan(source, current)
        var published = false
        try {
            collect(pending)
            pending.flush()
            database.runInTransaction {
                folder?.let {
                    removeStaleNasFolders(it.profileId, source)
                    index.saveNasFolder(it)
                }
                for (uri in pending.unseen.keys) index.deleteTrack(uri, source)
                index.publishStaged(source)
                if (pending.changed || folder != null) database.artwork().pruneUnused()
            }
            published = true
            if (pending.changed || folder != null) notifyIndexChanged()
            return pending.trackCount
        } finally {
            if (!published && pending.wroteStaged) database.runInTransaction {
                index.discardStaged(source)
                database.artwork().pruneUnused()
            }
        }
    }

    private inner class PendingScan(source: String, current: List<LibraryDatabase.TrackInfo>) {
        private val sourceId = source
        val unseen = current.associateBy { it.uri }.toMutableMap()
        private val staged = ArrayList<LibraryDatabase.Entry>(32)
        private val artworkToSave = LinkedHashMap<String, LibraryDatabase.Artwork>()
        var wroteStaged = false
            private set
        val changed: Boolean get() = wroteStaged || unseen.isNotEmpty()
        var trackCount = 0
            private set

        fun visit(uri: String, revision: String, readTags: () -> TrackTags) {
            val previous = unseen.remove(uri)
            trackCount++
            if (previous?.revision == revision) return
            val (track, artwork) = readTags()
            staged.add(LibraryDatabase.Entry().apply {
                this.staged = true
                this.track = LibraryDatabase.TrackInfo().apply {
                    this.uri = uri
                    source = sourceId
                    this.revision = revision
                    title = track.title
                    artist = track.artist
                    album = track.album
                    albumArtist = track.albumArtist
                    durationMs = track.durationMs
                    addedAt = previous?.addedAt ?: System.currentTimeMillis()
                    trackNumber = track.trackNumber
                    discNumber = track.discNumber
                    artworkHash = artwork?.let {
                        val thumbnail = artworkRecord(it)
                        artworkToSave.putIfAbsent(thumbnail.hash, thumbnail)
                        thumbnail.hash
                    }
                }
            })
            if (staged.size == 32) flush()
        }

        fun flush() {
            if (staged.isEmpty()) return
            database.runInTransaction {
                artworkToSave.values.forEach(database.artwork()::save)
                index.stage(staged)
            }
            wroteStaged = true
            staged.clear()
            artworkToSave.clear()
        }
    }

    private companion object {
        const val DEVICE = "device"
    }
}

private fun List<String>.isWithin(folder: List<String>): Boolean =
    size > folder.size && folder.indices.all { this[it] == folder[it] }

private val NAS_FOLDERS_MIGRATION = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE library_nas_folders RENAME TO library_nas_folders_previous")
        db.execSQL("CREATE TABLE library_nas_folders (profileId TEXT NOT NULL, source TEXT NOT NULL, " +
            "uri TEXT NOT NULL, PRIMARY KEY(profileId, uri))")
        db.execSQL("INSERT INTO library_nas_folders SELECT profileId, source, uri FROM library_nas_folders_previous")
        db.execSQL("DROP TABLE library_nas_folders_previous")
    }
}

private val OFFLINE_STORAGE_MIGRATION = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE library_tracks ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE offline_copies ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE offline_copies ADD COLUMN manualDownload INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE offline_tasks ADD COLUMN manualDownload INTEGER NOT NULL DEFAULT 0")
        // Older copies do not record why they were downloaded. Keep them out of automatic cleanup suggestions.
        db.execSQL("UPDATE offline_copies SET manualDownload = 1")
        db.execSQL("UPDATE offline_tasks SET manualDownload = 1 WHERE folder = 0")
    }
}

internal fun LibraryDatabase.TrackInfo.toTrack(uri: String = this.uri, downloaded: Boolean = false) = Track(
    uri = uri,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    albumArtist = albumArtist,
    trackNumber = trackNumber,
    discNumber = discNumber,
    artworkRevision = revision.takeIf { artworkHash != null },
    downloaded = downloaded,
    source = source.takeUnless { it == "device" }.orEmpty(),
    originalUri = this.uri,
    addedAt = addedAt,
)

internal fun LibraryDatabase.OfflineInfo.toOfflineTrack(): Track =
    track.toTrack(OfflineTrackUri.create(id).toString(), downloaded = true)

private val OFFLINE_MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE offline_copies (
                id TEXT NOT NULL PRIMARY KEY, fileName TEXT NOT NULL, sizeBytes INTEGER NOT NULL,
                uri TEXT NOT NULL, source TEXT NOT NULL, revision TEXT NOT NULL, title TEXT NOT NULL,
                artist TEXT, album TEXT, albumArtist TEXT, durationMs INTEGER NOT NULL, trackNumber INTEGER,
                discNumber INTEGER, hasArtwork INTEGER NOT NULL, artwork BLOB
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_offline_copies_uri_source ON offline_copies(uri, source)")
        db.execSQL("""
            CREATE TABLE offline_tasks (
                id TEXT NOT NULL PRIMARY KEY, uri TEXT NOT NULL, source TEXT NOT NULL, title TEXT NOT NULL,
                folder INTEGER NOT NULL, error TEXT, lastSynced INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_offline_tasks_uri_source_folder ON offline_tasks(uri, source, folder)")
    }
}

private val OFFLINE_PROGRESS_MIGRATION = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE offline_tasks ADD COLUMN syncWarning TEXT")
        db.execSQL("""
            CREATE TABLE offline_sync_entries (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                taskId TEXT NOT NULL, uri TEXT NOT NULL, folder INTEGER NOT NULL,
                size INTEGER NOT NULL, modified INTEGER NOT NULL,
                FOREIGN KEY (taskId) REFERENCES offline_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_offline_sync_entries_taskId_uri ON offline_sync_entries(taskId, uri)")
        db.execSQL("CREATE INDEX index_offline_sync_entries_taskId_sequence ON offline_sync_entries(taskId, sequence)")
    }
}
