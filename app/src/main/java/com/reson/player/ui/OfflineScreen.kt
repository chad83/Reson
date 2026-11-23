package com.reson.player.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.reson.player.data.library.LibraryDatabase
import com.reson.player.data.library.DownloadProgress
import com.reson.player.data.library.OfflineDownloads
import com.reson.player.domain.Track

@Composable
internal fun OfflineScreen(
    offline: OfflineUiState,
    playback: PlaybackState,
    onBrowseNas: () -> Unit,
    onPlay: (List<Track>, String) -> Unit,
    trackMenu: @Composable (Track) -> Unit,
    loadArtwork: suspend (String, String) -> ByteArray?,
    onSync: (String) -> Unit,
    onStop: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPreviewCleanup: () -> Unit,
    onDismissCleanup: () -> Unit,
    onCleanup: (Set<String>) -> Unit,
    onReconnectPlayback: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val tracks = remember(offline.data.copies) { offline.data.copies.map { it.track } }
    val folders = offline.data.tasks.filter { it.folder }
    val requests = offline.data.tasks.filterNot { it.folder }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Downloads", Modifier.weight(1f).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onBrowseNas) { Text("Browse NAS") }
                }
                if (offline.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (offline.readError) {
                        item {
                            Text("Couldn’t read downloads.", Modifier.padding(horizontal = 8.dp), color = colors.error)
                        }
                    }
                    if (playback.connectionFailed) {
                        item { TextButton(onClick = onReconnectPlayback) { Text("Reconnect playback") } }
                    }
                    if (!offline.loading && !offline.readError && folders.isEmpty() && requests.isEmpty() && tracks.isEmpty()) {
                        item {
                            Text("Download a NAS track or keep a NAS folder offline.",
                                Modifier.padding(8.dp), color = colors.onSurfaceVariant)
                        }
                    }
                    if (folders.isNotEmpty()) {
                        item { OfflineHeading("Synced folders") }
                        items(folders, key = { it.id }) {
                            OfflineTaskRow(it, offline.jobs, offline.data.partialBytes[it.id] ?: 0L,
                                offline.progress?.takeIf { progress -> progress.taskId == it.id }, onSync, onStop)
                        }
                    }
                    if (requests.isNotEmpty()) {
                        item { OfflineHeading("Track transfers") }
                        items(requests, key = { it.id }) {
                            OfflineTaskRow(it, offline.jobs, offline.data.partialBytes[it.id] ?: 0L,
                                offline.progress?.takeIf { progress -> progress.taskId == it.id }, onSync, onStop)
                        }
                    }
                    if (tracks.isNotEmpty()) {
                        item {
                            val total = Formatter.formatShortFileSize(context, offline.data.copies.sumOf { it.sizeBytes })
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("$total saved on this device", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = onPreviewCleanup) { Text("Review cleanup") }
                            }
                        }
                        item { OfflineHeading("Saved tracks") }
                        items(offline.data.copies, key = { it.id }) { copy ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                LibraryRow(
                                    title = copy.track.title,
                                    subtitle = listOfNotNull(
                                        copy.track.artist,
                                        Formatter.formatShortFileSize(context, copy.sizeBytes),
                                        "Protected from cleanup".takeIf { copy.manual },
                                    ).joinToString(" · "),
                                    cover = copy.track,
                                    loadArtwork = loadArtwork,
                                    current = playback.mediaId == copy.track.uri ||
                                        (playback.mediaId == copy.originalUri && playback.hasDownload),
                                    enabled = playback.connected,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onPlay(tracks, copy.track.uri) },
                                )
                                trackMenu(copy.track)
                                TextButton(onClick = { removeId = copy.id }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }
    offline.cleanupPreview?.let { candidates ->
        val total = Formatter.formatShortFileSize(context, candidates.sumOf { it.sizeBytes })
        AlertDialog(
            onDismissRequest = onDismissCleanup,
            title = { Text("Unused downloads") },
            text = {
                if (candidates.isEmpty()) Text("There are no unused downloads to remove.")
                else Column {
                    Text("Remove ${candidates.size} downloads to free $total. Manually kept copies and active syncs are protected.")
                    LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = 8.dp)) {
                        items(candidates, key = { it.id }) { copy ->
                            Text(copy.track.title, Modifier.padding(vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                if (candidates.isNotEmpty()) TextButton(onClick = { onCleanup(candidates.mapTo(hashSetOf()) { it.id }) }) {
                    Text("Remove unused")
                }
            },
            dismissButton = { TextButton(onClick = onDismissCleanup) { Text(if (candidates.isEmpty()) "Done" else "Cancel") } },
        )
    }
    offline.data.copies.find { it.id == removeId }?.let { copy ->
        AlertDialog(
            onDismissRequest = { removeId = null },
            title = { Text("Remove download?") },
            text = { Text("${copy.track.title} will be removed from this device. A synced folder may download it again.") },
            confirmButton = {
                TextButton(onClick = {
                    removeId = null
                    onRemove(copy.id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OfflineHeading(title: String) {
    Text(title, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun OfflineTaskRow(
    task: LibraryDatabase.OfflineTask,
    jobs: List<WorkInfo>,
    partialBytes: Long,
    progress: DownloadProgress?,
    onSync: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val related = jobs.filter { OfflineDownloads.taskTag(task.id) in it.tags }
    val running = related.any { it.state == WorkInfo.State.RUNNING }
    val waiting = related.any { OfflineDownloads.PERIODIC_TAG !in it.tags }
    val status = when {
        running && task.folder -> "Syncing"
        running -> "Downloading"
        waiting -> "Queued · waiting to run"
        task.folder && task.lastSynced > 0 -> "Automatic sync enabled"
        task.folder -> "Waiting for first sync"
        else -> "Ready to retry"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(task.title, style = MaterialTheme.typography.titleSmall)
        Text(status, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (progress != null) {
            DownloadProgressBar(progress)
        } else if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        } else if (partialBytes > 0) {
            val size = Formatter.formatShortFileSize(LocalContext.current, partialBytes)
            Text("$size in unfinished downloads", style = MaterialTheme.typography.bodySmall)
        }
        task.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
        FlowRow {
            if (task.folder || (!running && !waiting) || task.error != null) {
                TextButton(onClick = { onSync(task.id) }, enabled = !running) {
                    Text(if (task.folder) "Sync now" else "Retry")
                }
            }
            TextButton(onClick = { onStop(task.id) }) {
                Text(if (task.folder) "Stop syncing" else "Cancel download")
            }
        }
    }
}

@Composable
internal fun DownloadProgressBar(progress: DownloadProgress) {
    val context = LocalContext.current
    Text("${Formatter.formatShortFileSize(context, progress.bytes)} / ${Formatter.formatShortFileSize(context, progress.totalBytes)}",
        style = MaterialTheme.typography.bodySmall)
    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
}
