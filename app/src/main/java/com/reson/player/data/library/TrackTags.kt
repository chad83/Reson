package com.reson.player.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM
import android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
import android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST
import android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER
import android.media.MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaMetadataRetriever.METADATA_KEY_TITLE
import androidx.core.net.toUri
import com.reson.player.domain.LocalTrackMetadata
import com.reson.player.domain.NasFile
import com.reson.player.domain.Track
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

internal data class TrackTags(val track: Track, val artwork: ByteArray? = null)

internal class TrackTagReader(private val context: Context) {
    fun device(track: Track): TrackTags = read(track) { setDataSource(context, track.uri.toUri()) }

    fun downloaded(track: Track, file: File): TrackTags = read(track) { setDataSource(file.absolutePath) }

    fun nas(track: Track, file: NasFile, coroutine: CoroutineContext): TrackTags {
        val source = NasMetadataSource(file, coroutine)
        val tags = read(track) { setDataSource(source) }
        coroutine.ensureActive()
        source.failure?.let { throw it }
        return tags
    }

    private fun read(track: Track, setSource: MediaMetadataRetriever.() -> Unit): TrackTags = try {
        MediaMetadataRetriever().use { reader ->
            reader.setSource()
            fun tag(key: Int) = LocalTrackMetadata.normalize(reader.extractMetadata(key))
            fun number(key: Int) = tag(key)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            TrackTags(
                track.copy(
                    title = tag(METADATA_KEY_TITLE) ?: track.title,
                    artist = tag(METADATA_KEY_ARTIST) ?: track.artist,
                    album = tag(METADATA_KEY_ALBUM) ?: track.album,
                    albumArtist = tag(METADATA_KEY_ALBUMARTIST) ?: track.albumArtist,
                    trackNumber = number(METADATA_KEY_CD_TRACK_NUMBER),
                    discNumber = number(METADATA_KEY_DISC_NUMBER),
                    durationMs = tag(METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
                        ?: track.durationMs,
                ),
                reader.embeddedPicture?.let(::thumbnail),
            )
        }
    } catch (_: RuntimeException) {
        TrackTags(track)
    } catch (_: IOException) {
        TrackTags(track)
    }

    private fun thumbnail(bytes: ByteArray): ByteArray? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val largest = maxOf(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (largest / options.inSampleSize > 384) options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            ByteArrayOutputStream().use { output ->
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)) output.toByteArray() else null
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/** Reads a limited amount of a remote track while Android probes its tags and embedded artwork. */
private class NasMetadataSource(private val file: NasFile, private val coroutine: CoroutineContext) :
    MediaDataSource() {
    private val buffer = ByteArray(32 * 1024)
    private var start = -1L
    private var length = 0
    private var budget = 8 * 1024 * 1024

    @Volatile
    var failure: IOException? = null
        private set

    override fun getSize(): Long = file.size

    @Synchronized
    override fun readAt(position: Long, target: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0 || position >= file.size || failure != null) return -1
        var copied = 0
        while (copied < size && coroutine.isActive) {
            val cursor = position + copied
            if (cursor >= file.size) break
            if (cursor < start || cursor - start >= length) {
                if (!fill(cursor)) break
            }
            val from = (cursor - start).toInt()
            val count = minOf(size - copied, length - from)
            buffer.copyInto(target, offset + copied, from, from + count)
            copied += count
        }
        return if (copied == 0) -1 else copied
    }

    private fun fill(position: Long): Boolean {
        val requested = minOf(buffer.size.toLong(), file.size - position, budget.toLong()).toInt()
        if (requested == 0) return false
        val read = try {
            file.read(position, buffer, 0, requested)
        } catch (error: IOException) {
            failure = error
            return false
        }
        if (read !in 1..requested) return false
        start = position
        length = read
        budget -= read
        return true
    }

    override fun close() = Unit
}
