package com.reson.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reson.player.domain.LibraryGroup
import com.reson.player.domain.LibraryView
import com.reson.player.domain.Track
import com.reson.player.domain.grouped
import com.reson.player.domain.matching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryScreen(
    library: LibraryState,
    playback: PlaybackState,
    onGrantAccess: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onOpenNas: () -> Unit,
    onSettings: () -> Unit,
    nasStatus: String?,
    canPlayTrack: (Track) -> Boolean,
    trackMenu: @Composable (Track) -> Unit,
    onPlayTrack: (List<Track>, String) -> Unit,
    loadArtwork: suspend (String, String) -> ByteArray?,
) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf(LibraryView.Tracks) }
    var groupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadedOnly by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val request = remember(library.tracks, query, view, groupKey, downloadedOnly) {
        LibrarySearch(library.tracks, query, view, groupKey, downloadedOnly)
    }
    val showAllTracks = view == LibraryView.Tracks && query.isBlank() && !downloadedOnly
    var completed by remember { mutableStateOf<Pair<LibrarySearch, LibraryResults>?>(null) }
    LaunchedEffect(request) {
        if (showAllTracks) return@LaunchedEffect
        if (request.query.isNotBlank()) delay(150)
        val results = withContext(Dispatchers.Default) {
            val sourceTracks = if (request.downloadedOnly) request.tracks.filter(Track::downloaded) else request.tracks
            val selected = request.groupKey?.takeIf { request.view != LibraryView.Tracks }?.let { group ->
                sourceTracks.grouped(request.view).find { it.key == group }
            }
            val tracks = (selected?.tracks ?: sourceTracks).matching(request.query)
            val groups = if (request.view != LibraryView.Tracks && selected == null) tracks.grouped(request.view) else emptyList()
            LibraryResults(tracks, groups, selected)
        }
        completed = request to results
    }
    val results = if (showAllTracks) LibraryResults(library.tracks, emptyList(), null)
    else completed?.takeIf { (previous, _) ->
        previous.query == query && previous.view == view && previous.groupKey == groupKey &&
            previous.downloadedOnly == downloadedOnly
    }?.second
    val selected = results?.selected
    val visibleTracks = results?.tracks.orEmpty()
    val scanning = library.indexLoading || library.deviceScanning || library.nasScanning
    BackHandler(enabled = groupKey != null) { groupKey = null }

    Scaffold { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = CircleShape, color = colors.primaryContainer) {
                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                Glyph(Symbol.Wave, colors.onPrimaryContainer, Modifier.size(19.dp))
                            }
                        }
                        Text("Reson", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onOpenNas) { Text("NAS") }
                        GlyphButton("Refresh library", Symbol.Refresh, onRefresh, enabled = !scanning)
                        GlyphButton("Settings", Symbol.Settings, onSettings)
                    }
                }

                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Library", style = MaterialTheme.typography.displaySmall)
                    }
                    val count = library.tracks.size
                    Text(
                        if (count == 1) "1 track" else "$count tracks",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (library.indexError || library.deviceError || library.nasError != null) {
                    Text(
                        library.nasError ?: "Couldn’t refresh the library.",
                        Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (playback.connectionFailed) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Couldn’t connect to playback.",
                            modifier = Modifier.weight(1f),
                            color = colors.error,
                        )
                        TextButton(onClick = onReconnect) { Text("Retry") }
                    }
                }

                if (nasStatus != null && library.tracks.isNotEmpty()) {
                    Text(nasStatus, Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (library.tracks.isNotEmpty() || library.hasPermission) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search library") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        trailingIcon = if (query.isNotEmpty()) {
                            { TextButton(onClick = { query = "" }) { Text("Clear") } }
                        } else null,
                    )
                    FlowRow(
                        Modifier.padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (option in LibraryView.entries) {
                            FilterChip(
                                selected = view == option,
                                onClick = {
                                    view = option
                                    groupKey = null
                                },
                                label = { Text(option.label) },
                            )
                        }
                        FilterChip(
                            selected = downloadedOnly,
                            onClick = {
                                downloadedOnly = !downloadedOnly
                                groupKey = null
                            },
                            label = { Text("Downloaded") },
                        )
                    }
                }

                if (!library.hasPermission && library.tracks.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Add music from this device", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onGrantAccess) { Text("Allow access") }
                    }
                }

                selected?.let { group ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphButton("Back to ${view.label.lowercase()}", Symbol.Back, { groupKey = null })
                        Text(group.title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = colors.outlineVariant)

                when {
                    scanning && library.tracks.isEmpty() ->
                        Text("Loading library…", Modifier.padding(24.dp), color = colors.onSurfaceVariant)
                    !library.hasPermission && library.tracks.isEmpty() -> LibraryNotice(
                        "Add music",
                        "Allow device music access or choose a library folder in NAS.",
                        "Scan this device",
                        onGrantAccess,
                        onOpenNas,
                    )
                    library.tracks.isEmpty() -> LibraryNotice(
                        "No tracks found",
                        "Add music to your device or choose a library folder in NAS.",
                        "Scan this device",
                        onRefresh,
                        onOpenNas,
                    )
                    results == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    visibleTracks.isEmpty() -> Text(
                        "No matching tracks",
                        Modifier.padding(24.dp),
                        color = colors.onSurfaceVariant,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 12.dp),
                    ) {
                        if (view == LibraryView.Tracks || selected != null) {
                            items(visibleTracks, key = Track::uri) { track ->
                                val subtitle = listOfNotNull(
                                    track.artist ?: "Unknown artist",
                                    track.album,
                                    when {
                                        track.downloaded -> "Downloaded"
                                        track.isNas -> "NAS"
                                        else -> "Device"
                                    },
                                ).joinToString(" · ")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LibraryRow(
                                        title = track.title,
                                        subtitle = subtitle,
                                        cover = track,
                                        current = track.uri == playback.mediaId,
                                        enabled = playback.connected && canPlayTrack(track),
                                        durationMs = track.durationMs,
                                        loadArtwork = loadArtwork,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            onPlayTrack(visibleTracks.filter(canPlayTrack), track.uri)
                                        },
                                    )
                                    trackMenu(track)
                                }
                            }
                        } else {
                            items(results.groups, key = { it.key }) { group ->
                                val count = group.tracks.size
                                LibraryRow(
                                    title = group.title,
                                    subtitle = listOfNotNull(
                                        group.artist,
                                        if (count == 1) "1 track" else "$count tracks",
                                    ).joinToString(" · "),
                                    cover = group.cover,
                                    loadArtwork = loadArtwork,
                                    onClick = { groupKey = group.key },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private class LibrarySearch(
    val tracks: List<Track>,
    val query: String,
    val view: LibraryView,
    val groupKey: String?,
    val downloadedOnly: Boolean,
)

private data class LibraryResults(
    val tracks: List<Track>,
    val groups: List<LibraryGroup>,
    val selected: LibraryGroup?,
)

@Composable
internal fun LibraryRow(
    title: String,
    subtitle: String,
    cover: Track?,
    loadArtwork: suspend (String, String) -> ByteArray?,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    enabled: Boolean = true,
    durationMs: Long = 0,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { selected = current },
        color = if (current) colors.primaryContainer else colors.surface,
        contentColor = if (current) colors.onPrimaryContainer else colors.onSurface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            val artwork = indexedArtwork(cover, loadArtwork)
            if (artwork != null) {
                Artwork(artwork, Modifier.size(46.dp), cornerSize = 12.dp)
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (current) colors.surface else colors.surfaceContainerHigh,
                ) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        Glyph(if (current) Symbol.Wave else Symbol.Note, colors.primary, Modifier.size(19.dp))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    color = if (current) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (durationMs > 0) {
                Text(
                    formatTime(durationMs),
                    modifier = Modifier.padding(end = 3.dp),
                    color = if (current) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
internal fun indexedArtwork(
    track: Track?,
    loadArtwork: suspend (String, String) -> ByteArray?,
): ByteArray? {
    val revision = track?.artworkRevision ?: return null
    return key(track.uri, revision) {
        val artwork by produceState<ByteArray?>(null) {
            value = loadArtwork(track.uri, revision)
        }
        artwork
    }
}

@Composable
private fun LibraryNotice(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit,
    onOpenNas: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        Glyph(Symbol.Note, MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(17.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    message,
                    Modifier.padding(top = 7.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(
                    Modifier.padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Button(onClick = onAction) { Text(action) }
                    OutlinedButton(onClick = onOpenNas) { Text("Connect to NAS") }
                }
            }
        }
    }
}

@Composable
internal fun MiniPlayer(playback: PlaybackState, onOpen: () -> Unit, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(21.dp),
    ) {
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onOpen,
                modifier = Modifier.weight(1f),
                color = colors.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.padding(start = 10.dp, top = 9.dp, bottom = 9.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Artwork(playback.artwork, Modifier.size(44.dp), cornerSize = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            playback.title.ifBlank { "Untitled track" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (playback.failed) "Playback failed" else playback.artist
                                ?: playback.sourceLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            PlayButton(playback.playing, onToggle, enabled = playback.connected, size = 46.dp)
        }
    }
}
