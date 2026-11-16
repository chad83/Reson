package com.reson.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.reson.player.domain.Track

@Composable
internal fun QueueScreen(
    items: List<QueueItem>,
    playback: PlaybackState,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    onPlay: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    loadArtwork: suspend (String, String) -> ByteArray?,
) {
    PlayerListScreen("Queue", onBack, onLibrary) {
        PlaybackModes(playback, onShuffle, onRepeat, Modifier.padding(horizontal = 20.dp))
        if (items.isEmpty()) {
            Text("The queue is empty.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (playback.shuffle && items.size > 1) {
                Text("Turn off shuffle to reorder the queue.", Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LibraryRow(
                            title = item.track.title,
                            subtitle = item.track.artist ?: "Unknown artist",
                            cover = item.track,
                            loadArtwork = loadArtwork,
                            modifier = Modifier.weight(1f),
                            current = item.id == playback.currentQueueId,
                            enabled = playback.connected,
                            durationMs = item.track.durationMs,
                            onClick = { onPlay(item.id) },
                        )
                        ActionMenu("Queue actions for ${item.track.title}", listOf(
                            MenuAction("Move up", !playback.shuffle && index > 0) { onMove(item.id, -1) },
                            MenuAction("Move down", !playback.shuffle && index < items.lastIndex) { onMove(item.id, 1) },
                            MenuAction("Remove from queue") { onRemove(item.id) },
                        ))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaybackModes(
    playback: PlaybackState,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = playback.shuffle,
            enabled = playback.connected,
            onClick = onShuffle,
            label = { Text(if (playback.shuffle) "Shuffle on" else "Shuffle off") },
        )
        FilterChip(
            selected = playback.repeatMode != Player.REPEAT_MODE_OFF,
            enabled = playback.connected,
            onClick = onRepeat,
            label = { Text(playback.repeatLabel) },
        )
    }
}

@Composable
internal fun PlayerListScreen(
    title: String,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    playback: PlaybackState? = null,
    onOpenPlayer: () -> Unit = {},
    onTogglePlayback: () -> Unit = {},
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(bottomBar = {
        if (playback?.mediaId != null) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                MiniPlayer(playback, onOpenPlayer, onTogglePlayback)
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphButton("Back", Symbol.Back, onBack)
                    Text(title, Modifier.weight(1f).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    actions()
                    TextButton(onClick = onLibrary) { Text("Library") }
                }
                content()
            }
        }
    }
}
