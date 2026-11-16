package com.reson.player.domain

import java.util.Locale

data class Track(
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val artworkRevision: String? = null,
    val downloaded: Boolean = false,
    val source: String = "",
    val originalUri: String = uri,
    val addedAt: Long = 0,
) {
    val identity: TrackIdentity get() = TrackIdentity(originalUri, source)
}

data class TrackIdentity(val uri: String, val source: String)

object LocalTrackMetadata {
    private val extensions = setOf("mp3", "flac", "aac", "m4a", "ogg", "oga", "wav", "opus", "m4b")

    fun supports(displayName: String?, isMusic: Boolean): Boolean =
        isMusic || displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in extensions

    fun normalize(tag: String?): String? = tag?.trim()?.takeUnless {
        it.isEmpty() || it.equals("<unknown>", ignoreCase = true)
    }

    fun title(tag: String?, displayName: String?): String =
        normalize(tag) ?: normalize(displayName?.substringBeforeLast('.')) ?: "Untitled track"
}
