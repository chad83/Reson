package com.reson.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reson.player.domain.LocalTrackMetadata
import com.reson.player.domain.NasEntry
import com.reson.player.domain.sourceId
import com.reson.player.playback.NasTrackUri

@Composable
internal fun NasScreen(
    model: NasViewModel,
    playback: PlaybackState,
    offline: OfflineUiState,
    offlineModel: OfflineViewModel,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    onGrantNetwork: () -> Unit,
    onPlay: (NasPage.Folder, NasEntry) -> Unit,
    onImportPlaylist: (NasPage.Folder, NasEntry) -> Unit,
    trackMenu: @Composable (NasPage.Folder, NasEntry) -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
    onReconnectPlayback: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val page = state.page
    val back = { if (!model.back()) onBack() }
    BackHandler(onBack = back)

    Scaffold(bottomBar = {
        if (playback.mediaId != null) {
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
                    GlyphButton("Back", Symbol.Back, back)
                    Text(
                        when (page) {
                            NasPage.Connections -> "NAS connections"
                            is NasPage.Editor -> if (page.draft.original == null) "Add NAS" else "Edit NAS"
                            is NasPage.Folder -> page.profile.name
                        },
                        Modifier.weight(1f).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (page is NasPage.Folder) {
                        GlyphButton("Refresh folder", Symbol.Refresh, { model.browse(page.profile, page.path) },
                            enabled = !state.busy && state.hasPermission)
                    }
                    TextButton(onClick = onLibrary) { Text("Library") }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!state.hasPermission) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Local network access is required to connect.", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onGrantNetwork) { Text("Allow access") }
                    }
                }
                state.message?.let { message ->
                    Text(
                        message,
                        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                when (page) {
                    NasPage.Connections -> NasConnections(model, state)
                    is NasPage.Editor -> NasEditor(model, state, page)
                    is NasPage.Folder -> key(page.profile.id, page.path) {
                        NasFolder(model, state, page, playback, offline, offlineModel, onPlay, onImportPlaylist,
                            trackMenu, onReconnectPlayback)
                    }
                }
            }
        }
    }
}

@Composable
private fun NasConnections(model: NasViewModel, state: NasUiState) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { model.add() }, enabled = !state.busy) { Text("Add NAS") }
                OutlinedButton(onClick = model::discover, enabled = !state.busy && state.hasPermission) {
                    Text("Find on network")
                }
            }
        }
        if (state.profiles.isEmpty() && !state.busy) {
            item {
                Text("Add a connection to browse music on a Synology or other SMB NAS.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.profiles, key = { it.id }) { profile ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                NasRow(
                    title = profile.name,
                    subtitle = "${profile.host} · ${profile.share}",
                    symbol = Symbol.Nas,
                    enabled = !state.busy && state.hasPermission,
                    onClick = { model.browse(profile) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { model.edit(profile) }, enabled = !state.busy) { Text("Edit") }
            }
        }
        if (state.devices.isNotEmpty()) {
            item { Text("Discovered devices", style = MaterialTheme.typography.titleMedium) }
            items(state.devices, key = { "${it.address}:${it.port}" }) { device ->
                NasRow(device.name, device.address, Symbol.Nas, !state.busy, { model.add(device) })
            }
        }
    }
}

@Composable
private fun NasEditor(model: NasViewModel, state: NasUiState, editor: NasPage.Editor) {
    val draft = editor.draft
    val enabled = !state.busy
    val canConnect = enabled && state.hasPermission
    var confirmRemove by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            NasField("Connection name", draft.name, { model.updateDraft(draft.copy(name = it)) }, enabled)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NasField("Host or IP address", draft.host, { model.updateDraft(draft.copy(host = it)) },
                    enabled, modifier = Modifier.weight(3f), keyboard = KeyboardType.Uri)
                NasField("Port", draft.port, { model.updateDraft(draft.copy(port = it)) },
                    enabled, modifier = Modifier.weight(1f), keyboard = KeyboardType.Number)
            }
        }
        item {
            NasField("Username", draft.username, { model.updateDraft(draft.copy(username = it)) }, enabled)
        }
        item {
            NasField("Password", draft.password, { model.updateDraft(draft.copy(password = it)) },
                enabled, keyboard = KeyboardType.Password)
        }
        item {
            NasField("Domain (optional)", draft.domain, { model.updateDraft(draft.copy(domain = it)) }, enabled)
        }
        item {
            NasField("Share name", draft.share, { model.updateDraft(draft.copy(share = it)) }, enabled)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { model.test(draft) }, enabled = canConnect) { Text("Test connection") }
                OutlinedButton(onClick = { model.listShares(draft) }, enabled = canConnect) { Text("Browse shares") }
            }
        }
        items(editor.shares, key = { it }) { share ->
            NasRow(share, null, Symbol.Folder, enabled, { model.updateDraft(draft.copy(share = share)) })
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { model.save(draft) }, enabled = enabled) { Text("Save connection") }
                if (draft.original != null) {
                    TextButton(onClick = { confirmRemove = true }, enabled = enabled) { Text("Remove connection") }
                }
            }
        }
    }
    if (confirmRemove && draft.original != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${draft.original.name}?") },
            text = { Text("Reson will forget the saved address and sign-in.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    model.remove(draft.original)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NasField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        visualTransformation = if (keyboard == KeyboardType.Password) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
    )
}

@Composable
private fun NasFolder(
    model: NasViewModel,
    state: NasUiState,
    page: NasPage.Folder,
    playback: PlaybackState,
    offline: OfflineUiState,
    offlineModel: OfflineViewModel,
    onPlay: (NasPage.Folder, NasEntry) -> Unit,
    onImportPlaylist: (NasPage.Folder, NasEntry) -> Unit,
    trackMenu: @Composable (NasPage.Folder, NasEntry) -> Unit,
    onReconnectPlayback: () -> Unit,
) {
    val entries = page.entries.orEmpty()
    val profile = page.profile
    val source = remember(profile) { profile.sourceId() }
    val folderUri = remember(profile.id, profile.share, page.path) {
        NasTrackUri.create(profile.id, profile.share, page.path).toString()
    }
    val syncEnabled = offline.taskFor(folderUri, source, folder = true) != null
    var confirmSync by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(page.path.lastOrNull() ?: page.profile.share, style = MaterialTheme.typography.headlineMedium)
                if (page.path.isNotEmpty()) {
                    Text((listOf(page.profile.share) + page.path).joinToString(" / "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (page.entries != null) {
                    FlowRow {
                        TextButton(onClick = { model.scanLibraryFolder(page) },
                            enabled = !state.busy && state.hasPermission && page.path !in page.libraryFolders) {
                            Text(if (page.path in page.libraryFolders) "In library" else "Add folder to library")
                        }
                        TextButton(
                            onClick = { confirmSync = true },
                            enabled = !syncEnabled && !state.busy && state.hasPermission,
                        ) { Text(if (syncEnabled) "Folder sync enabled" else "Keep folder offline") }
                        if (page.path != page.profile.folder) {
                            TextButton(onClick = { model.saveStartFolder(page) }, enabled = !state.busy) {
                                Text("Use as starting folder")
                            }
                        }
                    }
                }
                if (page.libraryFolders.isNotEmpty()) {
                    ActionMenu("Manage library folders", page.libraryFolders.map { path ->
                        MenuAction("Remove ${(listOf(profile.share) + path).joinToString(" / ")}", !state.busy) {
                            model.removeLibraryFolder(page, path)
                        }
                    })
                }
            }
        }
        item { HorizontalDivider() }
        if (state.isError && page.entries == null) {
            item {
                OutlinedButton(onClick = { model.browse(page.profile, page.path) },
                    enabled = !state.busy && state.hasPermission) { Text("Retry") }
            }
        }
        if (page.entries?.isEmpty() == true) {
            item { Text("No folders or playable tracks here.", style = MaterialTheme.typography.bodyMedium) }
        }
        if (playback.connectionFailed) {
            item { TextButton(onClick = onReconnectPlayback) { Text("Reconnect playback") } }
        }
        items(entries, key = NasEntry::name) { entry ->
            if (entry.playlist) {
                NasRow(entry.name, "Import playlist", Symbol.Note, !state.busy && state.hasPermission,
                    { onImportPlaylist(page, entry) })
                return@items
            }
            val uri = NasTrackUri.create(profile.id, profile.share, page.path + entry.name).toString()
            val saved = !entry.directory && offline.copyFor(uri, source) != null
            val queued = !entry.directory && offline.taskFor(uri, source, folder = false) != null
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NasRow(
                    entry.name,
                    if (saved) "Downloaded" else null,
                    if (entry.directory) Symbol.Folder else Symbol.Note,
                    !state.busy && ((entry.directory && state.hasPermission) ||
                        (!entry.directory && playback.connected && (saved || state.hasPermission))),
                    {
                        if (entry.directory) model.browse(profile, page.path + entry.name)
                        else onPlay(page, entry)
                    },
                    Modifier.weight(1f),
                )
                if (!entry.directory && !saved) {
                    TextButton(
                        onClick = { offlineModel.download(uri, LocalTrackMetadata.title(null, entry.name), source) },
                        enabled = !queued && state.hasPermission,
                    ) { Text(if (queued) "Queued" else "Download") }
                }
                if (!entry.directory) trackMenu(page, entry)
            }
        }
    }
    if (confirmSync) {
        AlertDialog(
            onDismissRequest = { confirmSync = false },
            title = { Text("Keep this folder offline?") },
            text = { Text("Reson will download tracks in this folder and its subfolders, then check for changes automatically.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSync = false
                    offlineModel.keepFolder(profile, page.path)
                }) { Text("Keep offline") }
            },
            dismissButton = { TextButton(onClick = { confirmSync = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NasRow(
    title: String,
    subtitle: String?,
    symbol: Symbol,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        color = colors.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = colors.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Glyph(symbol, colors.onPrimaryContainer, Modifier.size(19.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
