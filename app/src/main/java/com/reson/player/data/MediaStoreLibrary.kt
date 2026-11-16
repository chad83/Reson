package com.reson.player.data

import android.content.Context
import android.content.ContentUris
import android.database.ContentObserver
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media
import com.reson.player.domain.DeviceTrack
import com.reson.player.domain.LocalLibrary
import com.reson.player.domain.LocalTrackMetadata
import com.reson.player.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

class MediaStoreLibrary(private val context: Context) : LocalLibrary {
    private val resolver = context.contentResolver
    private val collection = Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override val changes = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.buffer(Channel.CONFLATED)

    override suspend fun loadTracks(): List<DeviceTrack> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            Media._ID,
            Media.VOLUME_NAME,
            Media.TITLE,
            Media.DISPLAY_NAME,
            Media.ARTIST,
            Media.ALBUM,
            Media.SIZE,
            Media.DATE_MODIFIED,
            Media.DURATION,
            Media.IS_MUSIC,
        ) + if (Build.VERSION.SDK_INT >= 30) arrayOf(Media.GENERATION_MODIFIED, Media.ALBUM_ARTIST) else emptyArray()
        val selection = listOf(Media.IS_ALARM, Media.IS_NOTIFICATION, Media.IS_RINGTONE)
            .joinToString(" AND ") { "($it = 0 OR $it IS NULL)" }
        val cursor = resolver.query(
            collection, projection, selection, null, "${Media.TITLE} COLLATE NOCASE, ${Media._ID}",
        ) ?: error("MediaStore did not return a cursor")

        cursor.use { rows ->
            val id = rows.getColumnIndexOrThrow(Media._ID)
            val volume = rows.getColumnIndexOrThrow(Media.VOLUME_NAME)
            val title = rows.getColumnIndexOrThrow(Media.TITLE)
            val displayName = rows.getColumnIndexOrThrow(Media.DISPLAY_NAME)
            val artist = rows.getColumnIndexOrThrow(Media.ARTIST)
            val album = rows.getColumnIndexOrThrow(Media.ALBUM)
            val albumArtist = if (Build.VERSION.SDK_INT >= 30) rows.getColumnIndex(Media.ALBUM_ARTIST) else -1
            val size = rows.getColumnIndexOrThrow(Media.SIZE)
            val modified = rows.getColumnIndexOrThrow(Media.DATE_MODIFIED)
            val generation = if (Build.VERSION.SDK_INT >= 30) rows.getColumnIndex(Media.GENERATION_MODIFIED) else -1
            val duration = rows.getColumnIndexOrThrow(Media.DURATION)
            val isMusic = rows.getColumnIndexOrThrow(Media.IS_MUSIC)
            val coroutine = currentCoroutineContext()
            val versions = mutableMapOf<String, String>()

            buildList {
                while (rows.moveToNext()) {
                    coroutine.ensureActive()
                    val name = rows.getString(displayName)
                    if (!LocalTrackMetadata.supports(name, rows.getInt(isMusic) != 0)) continue
                    val volumeName = rows.getString(volume) ?: continue
                    val uri = ContentUris.withAppendedId(Media.getContentUri(volumeName), rows.getLong(id))
                    val track = Track(
                        uri = uri.toString(),
                        title = LocalTrackMetadata.title(rows.getString(title), name),
                        artist = LocalTrackMetadata.normalize(rows.getString(artist)),
                        album = LocalTrackMetadata.normalize(rows.getString(album)),
                        durationMs = rows.getLong(duration).coerceAtLeast(0),
                        albumArtist = if (albumArtist >= 0) LocalTrackMetadata.normalize(rows.getString(albumArtist)) else null,
                    )
                    val version = versions.getOrPut(volumeName) {
                        if (Build.VERSION.SDK_INT >= 30) MediaStore.getVersion(context, volumeName)
                        else MediaStore.getVersion(context)
                    }
                    val fileGeneration = if (generation >= 0) rows.getLong(generation).toString() else ""
                    val revision = "$version:${rows.getLong(modified)}:${rows.getLong(size)}:$fileGeneration"
                    add(DeviceTrack(track, revision))
                }
            }
        }
    }
}
