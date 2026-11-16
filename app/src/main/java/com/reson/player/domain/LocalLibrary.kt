package com.reson.player.domain

import kotlinx.coroutines.flow.Flow

interface LocalLibrary {
    val changes: Flow<Unit>

    suspend fun loadTracks(): List<DeviceTrack>
}

data class DeviceTrack(val track: Track, val revision: String)

data class PlaybackOrder(val tracks: List<Track>, val startIndex: Int) {
    companion object {
        fun fromLibrary(tracks: List<Track>, selectedUri: String): PlaybackOrder? {
            val index = tracks.indexOfFirst { it.uri == selectedUri }
            return if (index >= 0) PlaybackOrder(tracks, index) else null
        }
    }
}
