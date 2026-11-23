package com.reson.player.playback.car

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.reson.player.ResonApplication
import com.reson.player.data.hasAudioReadPermission
import com.reson.player.playback.NasTrackUri
import com.reson.player.playback.OfflineTrackUri
import com.reson.player.playback.PLAY_NEXT
import com.reson.player.playback.NEXT_ITEM
import com.reson.player.playback.toMediaItem
import com.reson.player.playback.nasSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
internal class CarLibraryCallback(
    private val app: ResonApplication,
    private val scope: CoroutineScope,
    private val onNotificationDismissed: () -> Unit,
    private val onPlayNext: (MediaItem) -> Boolean,
) : MediaLibrarySession.Callback {
    private val catalog = CarCatalogSource(app)
    private val subscribed = mutableSetOf<String>()
    private var deviceObserver: Job? = null

    fun watchChanges(session: MediaLibrarySession) {
        scope.launch {
            combine(app.library.changes, app.collections.changes) { library, lists -> library to lists }
                .drop(1).collect {
                    subscribed.removeAll { session.getSubscribedControllers(it).isEmpty() }
                    for (id in subscribed) session.notifyChildrenChanged(id, Int.MAX_VALUE, null)
                }
        }
    }

    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> {
        val browserAllowed = canBrowse(controller)
        val isLegacyController =
            controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
        val isBluetoothService =
            !controller.isTrusted && controller.packageName == "com.android.bluetooth" && browserAllowed
        // A watch companion can already hold the platform session token without having library access.
        if ((!browserAllowed && isLegacyController) || isBluetoothService) {
            val commands = Player.Commands.Builder()
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_PREPARE)
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
            return Futures.immediateFuture(
                MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(SessionCommands.EMPTY)
                    .setAvailablePlayerCommands(commands)
                    .build(),
            )
        }
        if (!browserAllowed) return Futures.immediateFuture(MediaSession.ConnectionResult.reject())
        val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        if (!isLocal(controller)) playerCommands.remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
        else sessionCommands.add(PLAY_NEXT)
        return Futures.immediateFuture(
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands.build())
                .setAvailablePlayerCommands(playerCommands.build())
                .build(),
        )
    }

    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
        if (!isLocal(controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
        if (customCommand != PLAY_NEXT) return super.onCustomCommand(session, controller, customCommand, args)
        val item = args.getBundle(NEXT_ITEM)?.let { resolveLocalItem(MediaItem.fromBundle(it, MediaLibraryInfo.INTERFACE_VERSION)) }
        val result = if (item != null && onPlayNext(item)) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE
        return Futures.immediateFuture(SessionResult(result))
    }

    override fun onPlayerInteractionFinished(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        playerCommands: Player.Commands,
    ) {
        if (!playerCommands.contains(Player.COMMAND_STOP)) return
        val systemUi = controllerInfo.isTrusted && controllerInfo.isPackageNameVerified &&
            controllerInfo.packageName == "com.android.systemui"
        if (session.isMediaNotificationController(controllerInfo) || systemUi) {
            onNotificationDismissed()
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (!canBrowse(browser)) return denied()
        observeDeviceLibrary()
        val rootParams = LibraryParams.Builder()
            .setExtras(Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) })
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(CarCatalog.ROOT.toBrowseItem(), rootParams))
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (!canBrowse(browser)) return denied()
        return libraryResult {
            val item = if (mediaId == CarCatalog.ROOT.id) CarCatalog.ROOT else catalog.current().item(mediaId)
            if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItem(item.toBrowseItem(), null)
        }
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!canBrowse(browser)) return denied()
        return libraryResult {
            val children = if (parentId == CarCatalog.ROOT.id) {
                CarCatalog.ROOT_CHILDREN
            } else {
                val library = catalog.current()
                if (parentId == CarCatalog.LIBRARY.id && !library.hasLibraryTracks && !app.hasAudioReadPermission()) {
                    return@libraryResult LibraryResult.ofError(
                        SessionError(SessionError.ERROR_PERMISSION_DENIED,
                            "When parked, open Reson on your phone and allow music access."),
                    )
                }
                (library.item(parentId) as? CarItem.Folder)?.children
                    ?: return@libraryResult LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            }
            LibraryResult.ofItemList(children.page(page, pageSize).map { it.toBrowseItem() }, params)
        }
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        if (!canBrowse(browser)) return denied()
        val result = super.onSubscribe(session, browser, parentId, params)
        result.addListener({
            if (runCatching { result.get().resultCode }.getOrNull() == LibraryResult.RESULT_SUCCESS) {
                subscribed.add(parentId)
            }
        }, app.mainExecutor)
        return result
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        if (!canBrowse(browser)) return denied()
        return libraryResult {
            val count = catalog.current().search(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid(params)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!canBrowse(browser)) return denied()
        return libraryResult {
            LibraryResult.ofItemList(
                catalog.current().search(query).page(page, pageSize).map { it.toBrowseItem() }, params,
            )
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        if (!isLocal(controller)) return Futures.immediateFailedFuture(SecurityException("Only Reson can edit the queue"))
        val resolved = mediaItems.map { item ->
            resolveLocalItem(item) ?: return Futures.immediateFailedFuture(IllegalArgumentException("Invalid track"))
        }
        return Futures.immediateFuture(resolved)
    }

    private fun resolveLocalItem(item: MediaItem): MediaItem? {
        val uri = item.mediaId.toUri()
        val path = uri.pathSegments
        val localTrack = uri.scheme == "content" && uri.authority == "media" && path.size == 4 &&
            path[1] == "audio" && path[2] == "media" && path[3].toLongOrNull() != null
        val nasTrack = NasTrackUri.accepts(uri)
        if (!localTrack && !nasTrack && OfflineTrackUri.id(uri) == null) return null
        val source = if (nasTrack) item.nasSource ?: return null else null
        return item.buildUpon().setUri(uri).setCustomCacheKey(source).build()
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (isLocal(controller) && mediaItems.none {
                it.mediaId.startsWith("car:") || it.requestMetadata.searchQuery != null
            }
        ) return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        if (!canBrowse(controller)) return Futures.immediateFailedFuture(SecurityException("Browser access denied"))
        if (mediaItems.isEmpty() || mediaItems.size > CarCatalog.MAX_CHILDREN) {
            return Futures.immediateFailedFuture(IllegalArgumentException("Invalid car playback request"))
        }

        return future {
            val library = catalog.current()
            if (mediaItems.size == 1) {
                val request = mediaItems.single()
                val query = request.requestMetadata.searchQuery
                val extras = request.requestMetadata.extras
                @Suppress("DEPRECATION")
                val playlist = extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST)
                val album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)
                val artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                val title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)
                val selection = when {
                    query == null -> library.select(request.mediaId)
                    listOf(query, playlist, album, artist, title).all { it.isNullOrBlank() } ->
                        library.defaultSelection(app.collections.recentTracks(), allowFirstTrack = true)
                    else -> library.voiceSelection(query, playlist, album, artist, title)
                }
                if (selection == null) {
                    val message = "No matching track is available in Reson."
                    mediaSession.sendError(controller, SessionError(SessionError.ERROR_BAD_VALUE, message))
                    throw IllegalArgumentException(message)
                }
                selection.toPlayback(startPositionMs)
            } else {
                val songs = mediaItems.map { request ->
                    library.item(request.mediaId) as? CarItem.Song
                        ?: throw IllegalArgumentException("Invalid car track ID")
                }
                require(startIndex == C.INDEX_UNSET || startIndex in songs.indices)
                CarSelection(songs, startIndex).toPlayback(startPositionMs)
            }
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (!canBrowse(controller)) return Futures.immediateFailedFuture(SecurityException("Browser access denied"))
        return future {
            app.playbackQueue.load()?.let { saved ->
                return@future MediaSession.MediaItemsWithStartPosition(
                    saved.tracks.map { it.toMediaItem() }, saved.index, saved.position,
                )
            }
            catalog.current().defaultSelection(app.collections.recentTracks(), allowFirstTrack = isForPlayback)
                ?.toPlayback(C.TIME_UNSET) ?: throw IllegalStateException("No available track to play")
        }
    }

    private fun observeDeviceLibrary() {
        if (!app.hasAudioReadPermission() || deviceObserver?.isActive == true) return
        deviceObserver = scope.launch {
            app.library.deviceScan.collect {}
        }
    }

    private fun isLocal(controller: MediaSession.ControllerInfo): Boolean = controller.uid == Process.myUid()

    private fun canBrowse(controller: MediaSession.ControllerInfo): Boolean {
        if (isLocal(controller) || controller.isTrusted) return true
        // Some car hosts are installed as system apps without the platform media-control grant.
        return isSystemApp(controller)
    }

    private fun isSystemApp(controller: MediaSession.ControllerInfo): Boolean =
        try {
            val info = app.packageManager.getApplicationInfo(controller.packageName, 0)
            info.uid == controller.uid &&
                info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun <T : Any> denied(): ListenableFuture<LibraryResult<T>> =
        Futures.immediateFuture(LibraryResult.ofError<T>(SessionError.ERROR_PERMISSION_DENIED))

    private fun <T : Any> libraryResult(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> = future {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LibraryResult.ofError<T>(SessionError.ERROR_IO)
        }
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try {
                result.set(block())
            } catch (cancelled: CancellationException) {
                result.cancel(false)
                throw cancelled
            } catch (error: Exception) {
                result.setException(error)
            }
        }
        job.invokeOnCompletion { if (it is CancellationException) result.cancel(false) }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return result
    }
}

private fun <T> List<T>.page(page: Int, size: Int): List<T> {
    if (page < 0 || size <= 0) return emptyList()
    val start = page.toLong() * size
    if (start >= this.size) return emptyList()
    return subList(start.toInt(), minOf(this.size.toLong(), start + size).toInt())
}

@OptIn(UnstableApi::class)
private fun CarSelection.toPlayback(positionMs: Long): MediaSession.MediaItemsWithStartPosition =
    MediaSession.MediaItemsWithStartPosition(songs.map { it.track.toMediaItem(it.id) }, index, positionMs)

private fun CarItem.toBrowseItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
    when (this) {
        is CarItem.Folder -> metadata.setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        is CarItem.Song -> metadata.setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
    }
    return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata.build()).build()
}
