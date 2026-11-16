package com.reson.player.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.work.WorkInfo
import com.reson.player.data.library.CollectionSnapshot
import com.reson.player.data.library.SavedPlaylist
import com.reson.player.domain.Track
import com.reson.player.domain.TrackIdentity
import com.reson.player.data.library.OfflineDownloads
import com.reson.player.data.library.SavedTrackResolver
import com.reson.player.playback.NasTrackUri

private enum class CollectionTab(val label: String) {
    Playlists("Playlists"), Favourites("Favourites"), History("History"),
}

@Composable
internal fun CollectionsScreen(
    state: CollectionsState,
    model: CollectionsViewModel,
    offline: OfflineUiState,
    offlinePlaylists: Set<String>,
    playlistFailures: Map<TrackIdentity, String>,
    sourceByProfile: Map<String, String>,
    resolver: SavedTrackResolver,
    actions: TrackActions,
    playback: PlaybackState,
    loadArtwork: suspend (String, String) -> ByteArray?,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    onPlay: (List<Track>, String) -> Unit,
    onShufflePlay: (List<Track>) -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(CollectionTab.Playlists) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var nameDialog by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importPlaylist(uri)
    }
    val onExported: (android.net.Uri?) -> Unit = { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) model.exportPlaylist(uri, id)
    }
    val exportM3u = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl"), onResult = onExported)
    val exportM3u8 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.apple.mpegurl"),
        onResult = onExported)
    val playlist = state.data.playlists.find { it.id == selectedId }
    val nasTracks = playlist?.tracks.orEmpty().filter { NasTrackUri.accepts(it.originalUri.toUri()) }
    val keepOffline = playlist != null && playlist.id in offlinePlaylists
    fun exportPlaylist(playlist: SavedPlaylist, utf8Extension: Boolean) {
        exportId = playlist.id
        val name = playlist.title.replace('/', '_')
        if (utf8Extension) exportM3u8.launch("$name.m3u8") else exportM3u.launch("$name.m3u")
    }
    val back = { if (playlist == null) onBack() else selectedId = null }
    BackHandler(enabled = playlist != null) { selectedId = null }

    PlayerListScreen(
        title = playlist?.title ?: "Collections",
        onBack = back,
        onLibrary = onLibrary,
        playback = playback,
        onOpenPlayer = onOpenPlayer,
        onTogglePlayback = onTogglePlayback,
        actions = {
            when {
                playlist != null -> ActionMenu("Playlist settings", buildList {
                    if (playlist.rule == null) add(MenuAction("Rename playlist") { nameDialog = true })
                    add(MenuAction("Export M3U8") { exportPlaylist(playlist, utf8Extension = true) })
                    add(MenuAction("Export M3U") { exportPlaylist(playlist, utf8Extension = false) })
                    add(MenuAction(if (keepOffline) "Stop offline sync" else "Keep playlist offline",
                        keepOffline || nasTracks.isNotEmpty() || playlist.rule != null) {
                        model.keepOffline(playlist.id, !keepOffline)
                    })
                    add(MenuAction("Sync offline tracks now", keepOffline && nasTracks.isNotEmpty()) { model.syncOffline() })
                    if (playlist.rule == null) add(MenuAction("Delete playlist") { confirmDelete = true })
                })
                tab == CollectionTab.Playlists -> ActionMenu("Playlist actions", listOf(
                    MenuAction("New playlist") { nameDialog = true },
                    MenuAction("Import M3U or M3U8") { importFile.launch(arrayOf("*/*")) },
                ))
                tab == CollectionTab.History && state.data.history.isNotEmpty() ->
                    TextButton(onClick = { confirmClear = true }) { Text("Clear history") }
            }
        },
    ) {
        if (playlist == null) {
            FlowRow(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (option in CollectionTab.entries) {
                    FilterChip(selected = tab == option, onClick = { tab = option }, label = { Text(option.label) })
                }
            }
        }
        if (keepOffline) {
            val copies = remember(offline.data.copies) { offline.data.copies.mapTo(hashSetOf()) { it.track.identity } }
            val downloaded = nasTracks.count { it.identity in copies }
            Text("$downloaded of ${nasTracks.size} NAS tracks downloaded", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        playlist?.rule?.let { rule ->
            Text(rule.description, Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.readError) {
            Text("Couldn’t read your collections.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        } else if (!state.loading) {
            when {
                playlist != null || tab == CollectionTab.Favourites -> SavedList(
                    saved = playlist?.tracks ?: state.data.favourites,
                    playlist = playlist,
                    keepOffline = keepOffline,
                    offline = offline,
                    playlistFailures = playlistFailures,
                    sourceByProfile = sourceByProfile,
                    model = model,
                    resolver = resolver,
                    actions = actions,
                    playback = playback,
                    loadArtwork = loadArtwork,
                    onPlay = onPlay,
                    onShufflePlay = onShufflePlay,
                )
                tab == CollectionTab.Playlists -> Playlists(state.data.playlists) { selectedId = it }
                else -> History(state.data, resolver, actions, playback, loadArtwork, onPlay)
            }
        }
    }

    if (nameDialog) {
        PlaylistNameDialog(
            title = if (playlist == null) "New playlist" else "Rename playlist",
            initialName = playlist?.title.orEmpty(),
            onDismiss = { nameDialog = false },
            onSave = { name ->
                nameDialog = false
                if (playlist == null) model.create(name) else model.rename(playlist.id, name)
            },
        )
    }
    if (confirmDelete && playlist != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("Remove ${playlist.title} from your playlists?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    selectedId = null
                    model.delete(playlist.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear playback history?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    model.clearHistory()
                }) { Text("Clear history") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Playlists(playlists: List<SavedPlaylist>, onSelect: (String) -> Unit) {
    if (playlists.isEmpty()) {
        EmptyCollection("Create a playlist or add a track from its menu.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        val regular = playlists.filter { it.rule == null }
        val smart = playlists.filter { it.rule != null }
        items(regular, key = { it.id }) { playlist ->
            LibraryRow(
                title = playlist.title,
                subtitle = if (playlist.tracks.size == 1) "1 track" else "${playlist.tracks.size} tracks",
                cover = null,
                loadArtwork = { _, _ -> null },
                onClick = { onSelect(playlist.id) },
            )
        }
        if (smart.isNotEmpty()) {
            item { Text("Smart playlists", Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium) }
            items(smart, key = { it.id }) { playlist ->
                LibraryRow(playlist.title, "${playlist.tracks.size} tracks", cover = null,
                    loadArtwork = { _, _ -> null }, onClick = { onSelect(playlist.id) })
            }
        }
    }
}

@Composable
private fun SavedList(
    saved: List<Track>,
    playlist: SavedPlaylist?,
    keepOffline: Boolean,
    offline: OfflineUiState,
    playlistFailures: Map<TrackIdentity, String>,
    sourceByProfile: Map<String, String>,
    model: CollectionsViewModel,
    resolver: SavedTrackResolver,
    actions: TrackActions,
    playback: PlaybackState,
    loadArtwork: suspend (String, String) -> ByteArray?,
    onPlay: (List<Track>, String) -> Unit,
    onShufflePlay: (List<Track>) -> Unit,
) {
    if (saved.isEmpty()) {
        EmptyCollection(when {
            playlist?.rule != null -> "No tracks match this rule yet."
            playlist == null -> "Add favourites from a track’s menu."
            else -> "Add tracks from their menus."
        })
        return
    }
    val playable = saved.map(resolver::resolve)
    val order = playable.filterNotNull()
    val copies = remember(offline.data.copies) { offline.data.copies.mapTo(hashSetOf()) { it.track.identity } }
    val tasks = remember(offline.data.tasks) { offline.data.tasks.filterNot { it.folder }.associateBy { TrackIdentity(it.uri, it.source) } }
    FlowRow(Modifier.padding(horizontal = 20.dp)) {
        TextButton(enabled = playback.connected && order.isNotEmpty(), onClick = { onPlay(order, order.first().uri) }) {
            Text("Play")
        }
        TextButton(enabled = playback.connected && order.isNotEmpty(), onClick = { onShufflePlay(order) }) {
            Text("Shuffle play")
        }
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        itemsIndexed(saved, key = { _, track -> "${track.source}\u0000${track.originalUri}" }) { index, track ->
            val actual = playable[index]
            val display = actual ?: track
            val menu = actions.items(display, actual != null).toMutableList()
            if (playlist != null && playlist.rule == null) {
                menu += MenuAction("Move up", index > 0) { model.move(playlist.id, track.identity, -1) }
                menu += MenuAction("Move down", index < saved.lastIndex) { model.move(playlist.id, track.identity, 1) }
                menu += MenuAction("Remove from playlist") { model.remove(playlist.id, track.identity) }
            }
            val uri = track.originalUri.toUri()
            val task = tasks[track.identity]
            val jobs = if (task == null) emptyList() else offline.jobs.filter { OfflineDownloads.taskTag(task.id) in it.tags }
            val running = jobs.any { it.state == WorkInfo.State.RUNNING }
            val isReady = track.identity in copies
            val trackSync = keepOffline && NasTrackUri.accepts(uri)
            val error = if (trackSync && !isReady) task?.error ?: playlistFailures[track.identity]
                ?: "This track’s saved NAS connection has changed or was removed.".takeIf { sourceByProfile[uri.authority] != track.source }
            else null
            val status = if (trackSync) when {
                isReady -> "Downloaded"
                running && offline.progress?.taskId == task?.id && offline.progress != null -> "Downloading"
                running -> "Preparing download"
                jobs.isNotEmpty() -> "Waiting to download"
                error != null -> "Download failed"
                else -> "Pending download"
            } else null
            if (playlist != null && error != null && !running) {
                menu += MenuAction("Retry download") { model.retryOffline(playlist.id, track) }
            }
            Column {
                CollectionTrackRow(
                    display, actual != null, listOfNotNull(display.artist ?: "Unknown artist", status).joinToString(" · "),
                    playback, loadArtwork, menu,
                ) { actual?.let { onPlay(order, it.uri) } }
                if (error != null) {
                    Text(error, Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun History(
    data: CollectionSnapshot,
    resolver: SavedTrackResolver,
    actions: TrackActions,
    playback: PlaybackState,
    loadArtwork: suspend (String, String) -> ByteArray?,
    onPlay: (List<Track>, String) -> Unit,
) {
    if (data.history.isEmpty()) {
        EmptyCollection("Tracks you play will appear here.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(data.history, key = { it.id }) { visit ->
            val actual = resolver.resolve(visit.track)
            val display = actual ?: visit.track
            val played = DateUtils.getRelativeTimeSpanString(visit.playedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            val subtitle = listOfNotNull(display.artist, played).joinToString(" · ")
            CollectionTrackRow(
                display, actual != null, subtitle, playback, loadArtwork, actions.items(display, actual != null),
            ) { actual?.let { onPlay(listOf(it), it.uri) } }
        }
    }
}

@Composable
private fun CollectionTrackRow(
    track: Track,
    available: Boolean,
    subtitle: String,
    playback: PlaybackState,
    loadArtwork: suspend (String, String) -> ByteArray?,
    actions: List<MenuAction>,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LibraryRow(
            title = track.title,
            subtitle = if (available) subtitle else "$subtitle · Unavailable",
            cover = track,
            loadArtwork = loadArtwork,
            modifier = Modifier.weight(1f),
            enabled = playback.connected && available,
            durationMs = track.durationMs,
            onClick = onClick,
        )
        ActionMenu("Track actions for ${track.title}", actions)
    }
}

@Composable
private fun EmptyCollection(message: String) {
    Text(message, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun AddToPlaylistDialog(
    track: Track,
    playlists: List<SavedPlaylist>,
    onDismiss: () -> Unit,
    onAdd: (String, Track) -> Unit,
    onCreate: (String, Track) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        PlaylistNameDialog("New playlist", "", onDismiss) { name ->
            onCreate(name, track)
            onDismiss()
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add to playlist") },
            text = {
                if (playlists.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            TextButton(onClick = {
                                onAdd(playlist.id, track)
                                onDismiss()
                            }, modifier = Modifier.fillMaxWidth()) { Text(playlist.title) }
                        }
                    }
                } else Text("Create a playlist for ${track.title}.")
            },
            confirmButton = { TextButton(onClick = { creating = true }) { Text("New playlist") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(100) },
                label = { Text("Playlist name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
