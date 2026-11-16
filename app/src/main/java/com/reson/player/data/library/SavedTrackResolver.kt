package com.reson.player.data.library

import androidx.core.net.toUri
import com.reson.player.domain.Track
import com.reson.player.playback.NasTrackUri

internal class SavedTrackResolver(library: List<Track>, private val nasSources: Map<String, String>) {
    private val indexed = library.associateBy(Track::identity)

    fun resolve(track: Track): Track? {
        indexed[track.identity]?.let { return it }
        val uri = track.originalUri.toUri()
        return track.takeIf {
            NasTrackUri.accepts(uri) && nasSources[uri.authority] == track.source
        }
    }
}
