package com.reson.player.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import com.reson.player.domain.Track
import java.util.UUID

private const val PLAYBACK_URI = "reson.playbackUri"
private const val QUEUE_ID = "reson.queueId"
private const val ORIGINAL_URI = "reson.originalUri"
private const val SOURCE = "reson.source"
private const val ARTWORK_REVISION = "reson.artworkRevision"
private const val DURATION = "reson.duration"
private const val DOWNLOADED = "reson.downloaded"
internal val PLAY_NEXT = SessionCommand("com.reson.player.PLAY_NEXT", Bundle.EMPTY)
internal const val NEXT_ITEM = "item"

@OptIn(UnstableApi::class)
internal fun Track.toMediaItem(catalogId: String? = null): MediaItem = MediaItem.Builder()
    .setMediaId(catalogId ?: uri)
    .setUri(uri)
    // Progressive Media3 requests carry this key into DataSpec, including on seeks.
    .setCustomCacheKey(source.takeIf { uri.startsWith("${NasTrackUri.SCHEME}://") })
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtist)
            .setTrackNumber(trackNumber)
            .setDiscNumber(discNumber)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(Bundle().apply {
                if (catalogId != null) putString(PLAYBACK_URI, uri)
                putString(QUEUE_ID, UUID.randomUUID().toString())
                putString(ORIGINAL_URI, originalUri)
                putString(SOURCE, source)
                putString(ARTWORK_REVISION, artworkRevision)
                putLong(DURATION, durationMs)
                putBoolean(DOWNLOADED, downloaded)
            })
            .build(),
    )
    .build()

internal val MediaItem.nasSource: String?
    get() = mediaMetadata.extras?.getString(SOURCE)?.takeIf(String::isNotEmpty)

internal fun MediaItem.queueId(index: Int): String =
    mediaMetadata.extras?.getString(QUEUE_ID) ?: "$index:$mediaId"

internal fun MediaItem.toTrack(
    metadata: MediaMetadata = mediaMetadata,
    duration: Long = 0L,
): Track {
    val extras = mediaMetadata.extras
    return Track(
        uri = extras?.getString(PLAYBACK_URI) ?: mediaId,
        title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Untitled track",
        artist = metadata.artist?.toString(),
        album = metadata.albumTitle?.toString(),
        durationMs = if (duration > 0) duration else extras?.getLong(DURATION) ?: 0L,
        albumArtist = metadata.albumArtist?.toString(),
        trackNumber = metadata.trackNumber,
        discNumber = metadata.discNumber,
        artworkRevision = extras?.getString(ARTWORK_REVISION),
        downloaded = extras?.getBoolean(DOWNLOADED) == true,
        source = extras?.getString(SOURCE).orEmpty(),
        originalUri = extras?.getString(ORIGINAL_URI) ?: extras?.getString(PLAYBACK_URI) ?: mediaId,
    )
}
