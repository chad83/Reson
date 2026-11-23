package com.reson.player.ui

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.reson.player.ResonApplication
import com.reson.player.data.library.DeviceScan
import com.reson.player.data.library.MusicLibrary
import com.reson.player.domain.NasProblem
import com.reson.player.domain.PlaybackOrder
import com.reson.player.domain.Track
import com.reson.player.playback.PlaybackService
import com.reson.player.playback.NasTrackUri
import com.reson.player.playback.OfflineTrackUri
import com.reson.player.playback.PLAY_NEXT
import com.reson.player.playback.NEXT_ITEM
import com.reson.player.playback.queueId
import com.reson.player.playback.toMediaItem
import com.reson.player.playback.toTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryState(
    val hasPermission: Boolean = false,
    val deviceScanning: Boolean = false,
    val indexLoading: Boolean = true,
    val nasScanning: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val deviceError: Boolean = false,
    val indexError: Boolean = false,
    val nasError: String? = null,
)

data class PlaybackState(
    val connected: Boolean = false,
    val connectionFailed: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val artwork: ByteArray? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val casting: Boolean = false,
    val hasDownload: Boolean = false,
    val currentTrack: Track? = null,
    val currentQueueId: String? = null,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val durationMs: Long = 0,
    val canSeek: Boolean = false,
    val canGoNext: Boolean = false,
    val canGoPrevious: Boolean = false,
    val error: String? = null,
    val nasProblem: NasProblem? = null,
) {
    val repeatLabel: String get() = when (repeatMode) {
        Player.REPEAT_MODE_ALL -> "Repeat all"
        Player.REPEAT_MODE_ONE -> "Repeat one"
        else -> "Repeat off"
    }
    val failed: Boolean get() = error != null
    val isNas: Boolean get() = mediaId?.startsWith("${NasTrackUri.SCHEME}://") == true
    val isOffline: Boolean get() = mediaId?.startsWith("${OfflineTrackUri.SCHEME}://") == true
    val sourceLabel: String get() = when {
        isOffline || hasDownload -> "Downloaded"
        isNas -> "NAS stream"
        else -> "On this device"
    }
}

internal data class QueueItem(val id: String, val track: Track)

internal class PlayerViewModel(
    private val appContext: Context,
    private val musicLibrary: MusicLibrary,
) : ViewModel() {
    private val permission = MutableStateFlow(false)
    private val refresh = MutableStateFlow(0)
    private var nasRefresh: Job? = null
    private var queuePreparation: Job? = null
    private val _library = MutableStateFlow(LibraryState())
    val library = _library.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackState())
    val playback = _playback.asStateFlow()
    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue = _queue.asStateFlow()

    private var controller: MediaController? = null
    private var pendingSearch: MediaItem? = null
    private var connection: ListenableFuture<MediaController>? = null
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishPlayback()
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) publishQueue(player)
        }
    }

    init {
        viewModelScope.launch {
            combine(permission, musicLibrary.changes, refresh) { allowed, _, _ -> allowed }
                .collectLatest { allowed ->
                    try {
                        val tracks = musicLibrary.tracks(allowed)
                        _library.update { it.copy(tracks = tracks, indexLoading = false, indexError = false) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _library.update { it.copy(indexLoading = false, indexError = true) }
                    }
                }
        }
        viewModelScope.launch {
            permission.collectLatest { allowed ->
                if (!allowed) return@collectLatest
                musicLibrary.deviceScan.collect { status ->
                    _library.update {
                        it.copy(deviceScanning = status == DeviceScan.Scanning, deviceError = status == DeviceScan.Failed)
                    }
                }
            }
        }
        connect()
    }

    fun setPermission(granted: Boolean) {
        if (permission.value == granted) return
        permission.value = granted
        _library.update { state ->
            state.copy(
                hasPermission = granted,
                deviceScanning = granted,
                deviceError = false,
                tracks = if (granted) state.tracks else state.tracks.filter { it.isNas || it.downloaded },
            )
        }
    }

    fun reloadLibraryIndex() {
        refresh.update { it + 1 }
    }

    fun refreshLibrary() {
        if (nasRefresh?.isActive == true) return
        reloadLibraryIndex()
        if (permission.value) musicLibrary.refreshDevice()
        nasRefresh = viewModelScope.launch {
            _library.update { it.copy(nasScanning = true, nasError = null) }
            try {
                val error = musicLibrary.refreshNas()
                _library.update { it.copy(nasError = error) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _library.update { it.copy(nasError = "Couldn’t refresh the NAS library.") }
            } finally {
                _library.update { it.copy(nasScanning = false) }
            }
        }
    }

    suspend fun loadArtwork(uri: String, revision: String): ByteArray? = try {
        musicLibrary.artwork(uri, revision)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        null
    }

    fun connect() {
        if (controller != null || connection != null) return
        _playback.update { it.copy(connectionFailed = false) }
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(disconnected: MediaController) {
                    if (controller != disconnected) return
                    disconnected.removeListener(playerListener)
                    controller = null
                    connection = null
                    _playback.update {
                        it.copy(connected = false, connectionFailed = true, playing = false, buffering = false)
                    }
                }
            })
            .buildAsync()
        connection = future
        future.addListener({
            if (connection === future) {
                val result = runCatching { future.get() }.getOrNull()
                if (result == null) {
                    connection = null
                    _playback.update { it.copy(connectionFailed = true) }
                } else {
                    controller = result
                    result.addListener(playerListener)
                    publishPlayback()
                    publishQueue(result)
                    pendingSearch?.let { request ->
                        pendingSearch = null
                        startSearch(result, request)
                    }
                }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun playFromSearch(query: String, extras: Bundle) {
        queuePreparation?.cancel()
        val request = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build())
            .build()
        val player = controller
        if (player == null) {
            pendingSearch = request
            connect()
        } else {
            startSearch(player, request)
        }
    }

    private fun startSearch(player: MediaController, request: MediaItem) {
        player.setMediaItem(request)
        player.prepare()
        player.play()
    }

    fun playTracks(tracks: List<Track>, uri: String) = prepareQueue(tracks, uri)

    fun shufflePlay(tracks: List<Track>) = prepareQueue(tracks, selectedUri = null)

    private fun prepareQueue(tracks: List<Track>, selectedUri: String?) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        queuePreparation?.cancel()
        queuePreparation = viewModelScope.launch {
            val (startIndex, items) = withContext(Dispatchers.Default) {
                val start = if (selectedUri == null) 0 else {
                    PlaybackOrder.fromLibrary(tracks, selectedUri)?.startIndex ?: return@withContext null
                }
                start to tracks.map {
                    ensureActive()
                    it.toMediaItem()
                }
            } ?: return@launch
            if (controller !== player) return@launch
            if (selectedUri == null) {
                player.shuffleModeEnabled = true
                player.setMediaItems(items, true)
            } else {
                player.setMediaItems(items, startIndex, 0L)
            }
            player.prepare()
            player.play()
        }
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun addToQueue(track: Track) {
        controller?.addMediaItem(track.toMediaItem())
    }

    @OptIn(UnstableApi::class)
    fun playNext(track: Track) {
        val item = track.toMediaItem().toBundle(MediaLibraryInfo.INTERFACE_VERSION)
        controller?.sendCustomCommand(PLAY_NEXT, Bundle().apply { putBundle(NEXT_ITEM, item) })
    }

    fun playQueued(id: String) = withQueueItem(id) { player, index ->
        player.seekToDefaultPosition(index)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun removeQueued(id: String): (() -> Unit)? {
        var undo: (() -> Unit)? = null
        withQueueItem(id) { player, index ->
            if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return@withQueueItem
            val track = player.getMediaItemAt(index).toTrack()
            val nextId = (index + 1).takeIf { it < player.mediaItemCount }
                ?.let { player.getMediaItemAt(it).queueId(it) }
            player.removeMediaItem(index)
            undo = {
                if (controller === player && player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                    val destination = (0 until player.mediaItemCount).firstOrNull {
                        player.getMediaItemAt(it).queueId(it) == nextId
                    } ?: index.coerceAtMost(player.mediaItemCount)
                    player.addMediaItem(destination, track.toMediaItem())
                }
            }
        }
        return undo
    }

    fun moveQueued(id: String, offset: Int) = withQueueItem(id) { player, index ->
        val destination = index + offset
        if (!player.shuffleModeEnabled && destination in 0 until player.mediaItemCount) {
            player.moveMediaItem(index, destination)
        }
    }

    private inline fun withQueueItem(id: String, action: (MediaController, Int) -> Unit) {
        val player = controller ?: return
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).queueId(it) == id }
            ?: return
        action(player, index)
    }

    @OptIn(UnstableApi::class)
    private fun publishQueue(player: Player) {
        val timeline = player.currentTimeline
        val shuffled = player.shuffleModeEnabled
        _queue.value = buildList {
            var index = timeline.getFirstWindowIndex(shuffled)
            while (index != C.INDEX_UNSET && index < player.mediaItemCount && size < player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                add(QueueItem(item.queueId(index), item.toTrack()))
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffled)
            }
        }
    }

    fun togglePlayback() {
        val player = controller ?: return
        when {
            player.currentMediaItem == null -> return
            player.playerError != null || player.playbackState == Player.STATE_IDLE -> {
                player.prepare()
                player.play()
            }
            player.playbackState == Player.STATE_ENDED -> {
                player.seekToDefaultPosition()
                player.play()
            }
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    fun seek(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun positionMs(): Long = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L

    private fun publishPlayback() {
        val player = controller ?: return
        val metadata = player.mediaMetadata
        val item = player.currentMediaItem
        val track = item?.toTrack(metadata, player.duration)
        val mediaId = track?.uri
        val nasProblem = generateSequence<Throwable>(player.playerError) { cause -> cause.cause }.take(16)
            .firstNotNullOfOrNull { cause ->
                NasProblem.entries.firstOrNull { problem -> problem.description == cause.message }
            }
        val error = player.playerError?.let {
            nasProblem?.description ?: when {
                mediaId?.startsWith("${NasTrackUri.SCHEME}://") == true ->
                    "Couldn’t play this NAS track. Check the connection and tap Play to retry."
                mediaId?.startsWith("${OfflineTrackUri.SCHEME}://") == true ->
                    "Couldn’t play the downloaded copy. Try downloading it again."
                else -> "Couldn’t play this file. Try again or choose another track."
            }
        }
        _playback.value = PlaybackState(
            connected = true,
            mediaId = mediaId,
            currentTrack = track,
            currentQueueId = item?.queueId(player.currentMediaItemIndex),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            artwork = metadata.artworkData,
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED &&
                player.playerError == null,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            casting = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
            durationMs = player.duration.coerceAtLeast(0),
            canSeek = player.isCurrentMediaItemSeekable,
            canGoNext = player.hasNextMediaItem(),
            canGoPrevious = player.isCurrentMediaItemSeekable || player.hasPreviousMediaItem(),
            error = error,
            nasProblem = nasProblem,
        )
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        val pending = connection
        connection = null
        controller = null
        pending?.let(MediaController::releaseFuture)
    }

    companion object {
        fun factory(application: ResonApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    modelClass.cast(PlayerViewModel(application, application.library))!!
            }
    }
}

internal val Track.isNas: Boolean get() = uri.startsWith("${NasTrackUri.SCHEME}://")
