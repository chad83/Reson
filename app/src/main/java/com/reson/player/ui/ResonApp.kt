package com.reson.player.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.reson.player.ResonApplication
import com.reson.player.data.Skin
import com.reson.player.data.library.SavedTrackResolver
import com.reson.player.domain.LocalTrackMetadata
import com.reson.player.domain.NasEntry
import com.reson.player.domain.Track
import com.reson.player.domain.sourceId
import com.reson.player.domain.isConnectionFailure
import com.reson.player.playback.NasTrackUri
import kotlinx.coroutines.launch

@Composable
internal fun ResonApp(
    model: PlayerViewModel,
    nasModel: NasViewModel,
    offlineModel: OfflineViewModel,
    collectionsModel: CollectionsViewModel,
    app: ResonApplication,
    skin: Skin,
    onGrantAccess: () -> Unit,
    onGrantNetwork: () -> Unit,
) {
    val library by model.library.collectAsStateWithLifecycle()
    val player by model.playback.collectAsStateWithLifecycle()
    val visualizerEnabled by app.visualizerSettings.enabled.collectAsStateWithLifecycle()
    val resumeQueue by app.playbackSettings.resumeQueue.collectAsStateWithLifecycle()
    val replayGain by app.playbackSettings.replayGain.collectAsStateWithLifecycle()
    val television by app.television.status.collectAsStateWithLifecycle()
    val queue by model.queue.collectAsStateWithLifecycle()
    val restoringQueue by app.playbackQueue.restoring.collectAsStateWithLifecycle()
    val nas by nasModel.state.collectAsStateWithLifecycle()
    val hasLan by app.localNasNetwork.availability.collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(hasLan) {
        if (!hasLan) nasModel.clearUnavailableProfiles()
    }
    val canStreamNas = hasLan && nas.hasPermission
    fun canPlayTrack(track: Track): Boolean =
        !track.isNas || track.downloaded || canStreamNas
    val nasStatus = when {
        nas.profiles.isEmpty() -> null
        !nas.hasPermission -> "NAS · Local network access required"
        !hasLan -> "NAS · No Wi-Fi or Ethernet connection"
        nas.unavailableProfiles.isNotEmpty() -> nas.profiles.filter { it.id in nas.unavailableProfiles }
            .takeIf { it.isNotEmpty() }?.joinToString(" · ") { "${it.name} unavailable" }
        else -> null
    }
    val offline by offlineModel.state.collectAsStateWithLifecycle()
    val collections by collectionsModel.state.collectAsStateWithLifecycle()
    val offlinePlaylists by collectionsModel.offlinePlaylists.collectAsStateWithLifecycle()
    val playlistFailures by collectionsModel.offlineFailures.collectAsStateWithLifecycle()
    val sourceByProfile = remember(nas.profiles) { nas.profiles.associate { it.id to it.sourceId() } }
    val indexedByUri = remember(library.tracks) { library.tracks.associateBy(Track::uri) }
    val resolver = remember(library.tracks, sourceByProfile) { SavedTrackResolver(library.tracks, sourceByProfile) }
    val mediaId = player.mediaId
    val activeTrack = player.currentTrack
    val activeProfile = mediaId?.takeIf { player.isNas }?.toUri()?.authority
    val source = sourceByProfile[activeProfile]
    val queuedSource = activeTrack?.source?.takeIf(String::isNotEmpty)
    val copy = if (player.isNas && mediaId != null && queuedSource != null) {
        offline.copyFor(mediaId, queuedSource)
    } else null
    LaunchedEffect(activeProfile, player.nasProblem, player.playing, player.buffering, copy) {
        if (activeProfile != null) {
            when {
                player.nasProblem?.isConnectionFailure == true -> nasModel.setUnavailable(activeProfile, true)
                player.playing && !player.buffering && !player.failed && copy == null -> {
                    nasModel.setUnavailable(activeProfile, false)
                }
            }
        }
    }
    val matchesSource = activeTrack?.source.isNullOrEmpty() || activeTrack.source == source
    val track = indexedByUri[mediaId]?.takeIf { activeTrack == null || it.identity == activeTrack.identity }
    val cover = track?.takeIf { it.artworkRevision != null } ?: copy?.track ?: activeTrack
    val indexed = if (player.artwork == null) indexedArtwork(cover, model::loadArtwork) else null
    val playback = player.copy(artwork = player.artwork ?: indexed, hasDownload = track?.downloaded == true || copy != null)
    val downloadQueued = mediaId != null && source != null && offline.taskFor(mediaId, source, folder = false) != null
    val navigation = rememberNavController()
    val route by navigation.currentBackStackEntryAsState()
    val openPlayer = { navigation.navigate("player") { launchSingleTop = true } }
    fun selectSection(section: String) {
        while (navigation.currentDestination?.route in listOf("player", "queue", "settings", "visualizer")) {
            if (!navigation.popBackStack()) break
        }
        navigation.navigate(section) {
            popUpTo("library") { saveState = true }
            launchSingleTop = true
            restoreState = section != "library"
        }
    }
    val openLibrary = { selectSection("library") }
    val openNas = {
        if (navigation.currentDestination?.route == "offline") selectSection("library")
        navigation.navigate("nas") { launchSingleTop = true }
    }
    val openDownloads = { selectSection("offline") }
    val playAndOpen = { tracks: List<Track>, uri: String ->
        model.playTracks(tracks, uri)
        openPlayer()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun offerUndo(message: String, undo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, actionLabel = "Undo", withDismissAction = true) ==
                SnackbarResult.ActionPerformed) undo()
        }
    }
    var playlistTarget by remember { mutableStateOf<Track?>(null) }
    val actions = TrackActions(
        favourites = collections.data.favouriteIds,
        playbackConnected = playback.connected,
        onQueue = {
            model.addToQueue(it)
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("Added to queue")
            }
        },
        onFavourite = collectionsModel::setFavourite,
        onPlayNext = model::playNext,
        onPlaylist = { playlistTarget = it },
    )
    val trackMenu: @Composable (Track) -> Unit = { item ->
        ActionMenu("Track actions for ${item.title}", actions.items(item))
    }

    offline.notice?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            offlineModel.clearNotice(message)
        }
    }
    collections.notice?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            collectionsModel.clearNotice(message)
        }
    }

    Box(Modifier.fillMaxSize()) {
      val showTransfers = route?.destination?.route != "offline" &&
          (offline.progress != null || offline.queued > 0 || offline.transferring)
      Column(if (showTransfers) Modifier.fillMaxSize().statusBarsPadding() else Modifier.fillMaxSize()) {
        if (showTransfers) {
            Surface(onClick = openDownloads, modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val progress = offline.progress
                    val title = when {
                        progress != null -> "Downloading ${progress.title}"
                        offline.transferring -> "Syncing downloads"
                        else -> "Downloads queued (${offline.queued})"
                    }
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (progress != null) DownloadProgressBar(progress)
                    else if (offline.transferring) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
        val currentRoute = route?.destination?.route
        val section = if (currentRoute == "nas") "library" else currentRoute
        val showSections = section == "library" || section == "collections" || section == "offline"
        val paneInsets = if (showSections) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier
        Box(Modifier.weight(1f).fillMaxWidth()) {
            NavHost(navigation, startDestination = "library", modifier = paneInsets.fillMaxSize()) {
                composable("library") {
                    LibraryScreen(
                        library = library,
                        playback = playback,
                        onGrantAccess = onGrantAccess,
                        onRefresh = {
                            nasModel.clearUnavailableProfiles()
                            model.refreshLibrary()
                        },
                        onReconnect = model::connect,
                        onOpenNas = openNas,
                        nasStatus = nasStatus,
                        canPlayTrack = ::canPlayTrack,
                        onSettings = { navigation.navigate("settings") { launchSingleTop = true } },
                        trackMenu = trackMenu,
                        onPlayTrack = model::playTracks,
                        loadArtwork = model::loadArtwork,
                    )
                }
                composable("nas") {
                    NasScreen(
                        nasModel,
                        playback,
                        offline,
                        offlineModel,
                        hasLan = hasLan,
                        onBack = { navigation.popBackStack() },
                        onGrantNetwork = onGrantNetwork,
                        onPlay = { folder, selected ->
                            val profileSource = folder.profile.sourceId()
                            val tracks = folder.entries.orEmpty().filterNot { it.directory || it.playlist }.map { entry ->
                                nasTrack(folder, entry, profileSource, indexedByUri, offline)
                            }
                            val uri = NasTrackUri.create(folder.profile.id, folder.profile.share, folder.path + selected.name)
                            playAndOpen(tracks, uri.toString())
                        },
                        onImportPlaylist = { folder, item ->
                            collectionsModel.importNasPlaylist(folder.profile, folder.path, item.name)
                        },
                        trackMenu = { folder, entry ->
                            val profileSource = remember(folder.profile) { folder.profile.sourceId() }
                            trackMenu(nasTrack(folder, entry, profileSource, indexedByUri, offline))
                        },
                        onReconnectPlayback = model::connect,
                    )
                }
                composable("offline") {
                    OfflineScreen(
                        offline = offline,
                        playback = playback,
                        onBrowseNas = openNas,
                        onPlay = playAndOpen,
                        trackMenu = trackMenu,
                        loadArtwork = model::loadArtwork,
                        onSync = offlineModel::syncNow,
                        onStop = offlineModel::stop,
                        onRemove = offlineModel::remove,
                        onPreviewCleanup = offlineModel::previewCleanup,
                        onDismissCleanup = offlineModel::dismissCleanup,
                        onCleanup = offlineModel::cleanup,
                        onReconnectPlayback = model::connect,
                    )
                }
                composable("collections") {
                    CollectionsScreen(
                        state = collections,
                        model = collectionsModel,
                        onRemoved = { offerUndo("Removed from playlist", it) },
                        onReconnect = model::connect,
                        offline = offline,
                        offlinePlaylists = offlinePlaylists,
                        playlistFailures = playlistFailures,
                        sourceByProfile = sourceByProfile,
                        canPlayTrack = ::canPlayTrack,
                        nasStatus = nasStatus,
                        resolver = resolver,
                        actions = actions,
                        playback = playback,
                        loadArtwork = model::loadArtwork,
                        onPlay = playAndOpen,
                        onShufflePlay = { tracks ->
                            model.shufflePlay(tracks)
                            openPlayer()
                        },
                    )
                }
                composable("player") {
                    NowPlayingScreen(
                        playback = playback,
                        readPosition = model::positionMs,
                        televisionConnected = television.connected,
                        onSeek = model::seek,
                        onBack = { navigation.popBackStack() },
                        onLibrary = openLibrary,
                        onTogglePlayback = model::togglePlayback,
                        onReconnect = model::connect,
                        onPrevious = model::previous,
                        onNext = model::next,
                        hasLanPermission = nas.hasPermission,
                        onGrantNetwork = onGrantNetwork,
                        onVisualizer = if (visualizerEnabled && !playback.casting) {
                            { navigation.navigate("visualizer") { launchSingleTop = true } }
                        } else null,
                        onQueue = { navigation.navigate("queue") { launchSingleTop = true } },
                        onShuffle = model::toggleShuffle,
                        onRepeat = model::cycleRepeat,
                        trackActions = (track ?: activeTrack)?.let { actions.items(it, matchesSource || !playback.isNas) }.orEmpty(),
                        downloadQueued = downloadQueued,
                        onDownload = if (mediaId != null && playback.isNas && !playback.hasDownload && matchesSource) {
                            { offlineModel.download(mediaId, playback.title.ifBlank { "NAS track" }, source) }
                        } else null,
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        skin = skin,
                        onSkinChanged = app.appearance::setSkin,
                        visualizerEnabled = visualizerEnabled,
                        onVisualizerChanged = { enabled ->
                            app.visualizerSettings.setEnabled(enabled)
                            if (!enabled) app.television.endSession()
                        },
                        resumeQueue = resumeQueue,
                        onResumeQueueChanged = { enabled ->
                            app.playbackSettings.setResumeQueue(enabled)
                            if (!enabled) app.playbackQueue.save(null)
                        },
                        replayGain = replayGain,
                        onReplayGainChanged = app.playbackSettings::setReplayGain,
                        onBack = { navigation.popBackStack() },
                        onLibrary = openLibrary,
                    )
                }
                composable("visualizer") {
                    if (visualizerEnabled) {
                        VisualizerScreen(
                            audio = app.visualizerAudio,
                            television = television,
                            hasLanPermission = nas.hasPermission,
                            onBack = { navigation.popBackStack() },
                            onLibrary = openLibrary,
                            onGrantNetwork = onGrantNetwork,
                            onStartTv = { scope.launch { app.television.start() } },
                            onStopTv = app.television::endSession,
                        )
                    } else {
                        LaunchedEffect(Unit) { navigation.popBackStack() }
                    }
                }
                composable("queue") {
                    QueueScreen(
                        items = queue,
                        restoring = restoringQueue,
                        onReconnect = model::connect,
                        playback = playback,
                        onBack = { navigation.popBackStack() },
                        onLibrary = openLibrary,
                        onPlay = model::playQueued,
                        onMove = model::moveQueued,
                        onRemove = { id ->
                            model.removeQueued(id)?.let { offerUndo("Removed from queue", it) }
                        },
                        onShuffle = model::toggleShuffle,
                        onRepeat = model::cycleRepeat,
                        loadArtwork = model::loadArtwork,
                    )
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).then(
                if (showSections) Modifier else Modifier.navigationBarsPadding(),
            ))
        }
        if (showSections) {
            if (playback.mediaId != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MiniPlayer(playback, openPlayer, model::togglePlayback)
                }
            }
            NavigationBar {
                NavigationBarItem(section == "library", { selectSection("library") },
                    icon = { Glyph(Symbol.Note, MaterialTheme.colorScheme.onSurface) }, label = { Text("Library") })
                NavigationBarItem(section == "collections", { selectSection("collections") },
                    icon = { Glyph(Symbol.Folder, MaterialTheme.colorScheme.onSurface) }, label = { Text("Playlists") })
                NavigationBarItem(section == "offline", openDownloads,
                    icon = { Glyph(Symbol.Down, MaterialTheme.colorScheme.onSurface) }, label = { Text("Downloads") })
            }
        }
      }
        playlistTarget?.let { selected ->
            AddToPlaylistDialog(
                track = selected,
                playlists = collections.data.playlists.filter { it.rule == null },
                onDismiss = { playlistTarget = null },
                onAdd = collectionsModel::add,
                onCreate = collectionsModel::create,
            )
        }
    }
}

private fun nasTrack(
    folder: NasPage.Folder,
    entry: NasEntry,
    source: String,
    indexed: Map<String, Track>,
    offline: OfflineUiState,
): Track {
    val uri = NasTrackUri.create(folder.profile.id, folder.profile.share, folder.path + entry.name).toString()
    return indexed[uri]?.takeIf { it.source == source }
        ?: offline.copyFor(uri, source)?.track?.copy(uri = uri, artworkRevision = null)
        ?: Track(uri, LocalTrackMetadata.title(null, entry.name), null, null, 0, source = source)
}
