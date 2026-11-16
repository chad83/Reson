package com.reson.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.reson.player.domain.Track
import com.reson.player.domain.TrackIdentity

internal data class MenuAction(val label: String, val enabled: Boolean = true, val invoke: () -> Unit)

internal class TrackActions(
    private val favourites: Set<TrackIdentity>,
    private val playbackConnected: Boolean,
    private val onQueue: (Track) -> Unit,
    private val onPlayNext: (Track) -> Unit,
    private val onFavourite: (Track, Boolean) -> Unit,
    private val onPlaylist: (Track) -> Unit,
) {
    fun items(track: Track, playable: Boolean = true): List<MenuAction> {
        val favourite = track.identity in favourites
        return listOf(
            MenuAction("Play next", playbackConnected && playable) { onPlayNext(track) },
            MenuAction("Add to queue", playbackConnected && playable) { onQueue(track) },
            MenuAction(if (favourite) "Remove from favourites" else "Add to favourites") {
                onFavourite(track, !favourite)
            },
            MenuAction("Add to playlist") { onPlaylist(track) },
        )
    }
}

@Composable
internal fun ActionMenu(label: String, items: List<MenuAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GlyphButton(label, Symbol.More, { expanded = true })
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (item in items) {
                DropdownMenuItem(
                    text = { Text(item.label) },
                    enabled = item.enabled,
                    onClick = {
                        expanded = false
                        item.invoke()
                    },
                )
            }
        }
    }
}
