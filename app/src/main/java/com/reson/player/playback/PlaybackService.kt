package com.reson.player.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.cast.Cast
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.common.collect.ImmutableList
import com.reson.player.MainActivity
import com.reson.player.ResonApplication
import com.reson.player.domain.NasException
import com.reson.player.domain.NasProblem
import com.reson.player.domain.PlaybackHistoryGate
import com.reson.player.playback.car.CarLibraryCallback
import com.reson.player.playback.cast.CastAudioServer
import com.reson.player.playback.cast.CastShufflePlayer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaLibrarySession? = null
    private val app get() = application as ResonApplication
    private var localPlayer: ExoPlayer? = null
    private lateinit var queueRecorder: PlaybackQueueRecorder
    private var castAudio: CastAudioServer? = null
    private var castClient: RemoteMediaClient? = null
    private val castErrors = object : RemoteMediaClient.Callback() {
        override fun onMediaError(mediaError: MediaError) {
            Toast.makeText(
                this@PlaybackService, "The Cast device couldn’t play this track.", Toast.LENGTH_SHORT,
            ).show()
        }
    }
    private var dismissalIntent: PendingIntent? = null
    private val nasTransfers = AtomicInteger()
    private val nasTransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, spec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, spec: DataSpec, isNetwork: Boolean) {
            if (nasTransfers.incrementAndGet() == 1) refreshWakeMode()
        }

        override fun onBytesTransferred(source: DataSource, spec: DataSpec, isNetwork: Boolean, bytes: Int) = Unit

        override fun onTransferEnd(source: DataSource, spec: DataSpec, isNetwork: Boolean) {
            if (nasTransfers.decrementAndGet() == 0) refreshWakeMode()
        }
    }
    private val notificationDismissal = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DISMISS_PLAYBACK) stopPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val playbackData = DataSource.Factory {
            ResonDataSource(
                ContentDataSource(this),
                NasDataSource(app.nasProfiles, app.nasClient).apply { addTransferListener(nasTransferListener) },
                app.offline::fileForPlayback,
            )
        }
        val sources = DefaultMediaSourceFactory(playbackData).setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                if (info.loadEventInfo.uri.scheme == NasTrackUri.SCHEME) {
                    val problem = generateSequence<Throwable>(info.exception) { it.cause }.take(16)
                        .filterIsInstance<NasException>().firstOrNull()?.problem
                    if (info.errorCount > 1 || (problem != null && problem != NasProblem.Unreachable)) {
                        return C.TIME_UNSET
                    }
                }
                return super.getRetryDelayMsFor(info)
            }
        })
        val replayGain = ReplayGainProcessor()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessors(arrayOf(replayGain))
                .build().let(app.visualizerAudio::observe)
        }
        val local = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(attributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        localPlayer = local
        val modes = getSharedPreferences("player_modes", MODE_PRIVATE)
        local.shuffleModeEnabled = modes.getBoolean("shuffle", false)
        local.repeatMode = when (val mode = modes.getInt("repeat", Player.REPEAT_MODE_OFF)) {
            Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE -> mode
            else -> Player.REPEAT_MODE_OFF
        }
        local.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                app.visualizerAudio.playing = isPlaying
            }

            override fun onTracksChanged(tracks: Tracks) {
                replayGain.update(tracks, app.playbackSettings.replayGain.value)
            }
        })
        serviceScope.launch {
            app.playbackSettings.replayGain.collect { replayGain.update(local.currentTracks, it) }
        }
        val player = castPlayer(local, playbackData)
        queueRecorder = PlaybackQueueRecorder(app.playbackQueue)
        val history = PlaybackHistoryGate()
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                modes.edit { putBoolean("shuffle", shuffleModeEnabled) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                modes.edit { putInt("repeat", repeatMode) }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                val queueChanged = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
                if (app.playbackSettings.resumeQueue.value && (queueChanged || events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    ))) {
                    queueRecorder.capture(player, queueChanged)
                }
                val item = player.currentMediaItem
                if (history.started(
                        hasItem = item != null,
                        playing = player.isPlaying,
                        transitioned = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION),
                        ended = player.playbackState == Player.STATE_ENDED,
                    ) && item != null
                ) {
                    app.collections.recordPlayback(item.toTrack(player.mediaMetadata, player.duration), System.currentTimeMillis())
                }
            }
        })
        serviceScope.launch {
            val saved = app.playbackQueue.load()
            if (saved != null && app.playbackSettings.resumeQueue.value && !queueRecorder.stopping &&
                player.mediaItemCount == 0 && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL
            ) {
                local.setMediaItems(saved.tracks.map { it.toMediaItem() }, saved.index, saved.position)
                saved.shuffleOrder?.let { local.setShuffleOrder(DefaultShuffleOrder(it.toIntArray(), System.nanoTime())) }
                local.prepare()
            }
        }
        serviceScope.launch {
            app.playbackSettings.resumeQueue.collect { enabled ->
                if (enabled) queueRecorder.capture(player, queueChanged = true) else queueRecorder.reset()
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(15_000)
                if (app.playbackSettings.resumeQueue.value && player.isPlaying) queueRecorder.capture(player)
            }
        }
        serviceScope.launch {
            var previousVolume: Float? = null
            app.television.status.collect { state ->
                if (state.connected && previousVolume == null) {
                    previousVolume = local.volume
                    local.volume = 0f
                } else if (!state.connected) {
                    previousVolume?.let { local.volume = it }
                    previousVolume = null
                }
                updateWakeMode(local)
            }
        }
        val launchApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            this, 0, Intent(DISMISS_PLAYBACK).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        dismissalIntent = dismiss
        ContextCompat.registerReceiver(
            this, notificationDismissal, IntentFilter(DISMISS_PLAYBACK), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setMediaNotificationProvider(DismissibleNotification(DefaultMediaNotificationProvider(this), dismiss))
        val callback = CarLibraryCallback(app, serviceScope, ::stopPlayback) { item ->
            if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) false
            else {
                if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) player.playNext(item)
                else local.playNext(item)
                true
            }
        }
        mediaSession = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(launchApp)
            .build()
            .also(callback::watchChanges)
    }

    private fun ExoPlayer.playNext(item: MediaItem) {
        if (mediaItemCount == 0) {
            addMediaItem(item)
            return
        }
        val current = currentMediaItemIndex
        val insertion = current + 1
        val order = if (shuffleModeEnabled) {
            val shuffle = shuffleOrder
            buildList<Int> {
                var index = shuffle.firstIndex
                while (index != C.INDEX_UNSET) {
                    add(if (index >= insertion) index + 1 else index)
                    if (index == current) add(insertion)
                    index = shuffle.getNextIndex(index)
                }
            }
        } else null
        addMediaItem(insertion, item)
        order?.let { setShuffleOrder(DefaultShuffleOrder(it.toIntArray(), System.nanoTime())) }
    }

    private fun castPlayer(local: ExoPlayer, sources: DataSource.Factory): CastShufflePlayer {
        val converter = DefaultMediaItemConverter()
        val remote = RemoteCastPlayer.Builder(this)
            .setMediaItemConverter(object : MediaItemConverter {
                override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem =
                    checkNotNull(castAudio).toCastItem(mediaItem, converter)

                override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
                    val item = converter.toMediaItem(mediaQueueItem)
                    return item.localConfiguration?.uri?.let { castAudio?.originalItem(it) } ?: item
                }
            })
            .build()
        lateinit var shufflePlayer: CastShufflePlayer
        val player = CastPlayer.Builder(this).setLocalPlayer(local).setRemotePlayer(remote)
            .setTransferCallback { source, target ->
                if (target === remote) {
                    val cast = Cast.getSingletonInstance(this)
                    val client = openCastAudio(cast, sources)
                    if (client != null) {
                        shufflePlayer.attach(client, source.shuffleModeEnabled)
                        shufflePlayer.transferToCast(source, target)
                    } else {
                        leaveUnavailableCast(cast, local, source.playbackState != Player.STATE_IDLE)
                    }
                } else {
                    if (castAudio != null && source.mediaItemCount > 0) {
                        shufflePlayer.transferToLocal(source, target)
                    }
                    shufflePlayer.detach()
                    closeCastAudio()
                }
            }.build()
        shufflePlayer = CastShufflePlayer(player) {
            Toast.makeText(this, "Couldn’t change shuffle on the Cast device.", Toast.LENGTH_SHORT).show()
        }
        // Media3 can start on the remote player without calling the transfer callback.
        if (remote.isCastSessionAvailable) {
            val cast = Cast.getSingletonInstance(this)
            val client = openCastAudio(cast, sources)
            if (client != null) shufflePlayer.attach(client, local.shuffleModeEnabled)
            else leaveUnavailableCast(cast, local, wasPrepared = false)
        }
        return shufflePlayer
    }

    private fun openCastAudio(cast: Cast, sources: DataSource.Factory): RemoteMediaClient? {
        return runCatching {
            val session = checkNotNull(cast.currentCastSession)
            val client = checkNotNull(session.remoteMediaClient)
            val receiver = checkNotNull(session.castDevice?.inetAddress)
            val endpoint = checkNotNull(app.localNasNetwork.localIpv4(receiver))
            val server = CastAudioServer(app.localNasNetwork, endpoint, receiver, contentResolver, sources)
            try {
                server.start()
                client.registerCallback(castErrors)
            } catch (error: Exception) {
                server.stop()
                throw error
            }
            castAudio = server
            castClient = client
            app.television.endSession()
            client
        }.onFailure { Log.w("ResonCast", "Could not start local audio server", it) }.getOrNull()
    }

    private fun leaveUnavailableCast(cast: Cast, local: ExoPlayer, wasPrepared: Boolean) {
        // Media3 completes the handoff after this callback returns.
        serviceScope.launch(Dispatchers.Main) {
            cast.endCurrentSession(true)
            if (wasPrepared) local.prepare()
            Toast.makeText(
                this@PlaybackService, "Couldn’t cast. Check that both devices are on the same network.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun closeCastAudio() {
        castClient?.unregisterCallback(castErrors)
        castClient = null
        val server = castAudio ?: return
        castAudio = null
        server.stop()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) = stopPlayback()

    private fun refreshWakeMode() {
        serviceScope.launch { localPlayer?.let(::updateWakeMode) }
    }

    private fun updateWakeMode(player: ExoPlayer) {
        val usesNetwork = nasTransfers.get() > 0 || app.television.status.value.connected
        player.setWakeMode(if (usesNetwork) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
    }

    private fun stopPlayback() {
        preserveQueueBeforeStopping()
        app.television.endSession()
        if (castAudio != null) Cast.getSingletonInstance(this).endCurrentSession(true)
        mediaSession?.player?.apply {
            pause()
            stop()
            clearMediaItems()
        }
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        preserveQueueBeforeStopping()
        unregisterReceiver(notificationDismissal)
        dismissalIntent?.cancel()
        serviceScope.cancel()
        app.visualizerAudio.playing = false
        app.television.endSession()
        if (castAudio != null) Cast.getSingletonInstance(this).endCurrentSession(true)
        mediaSession?.let { session ->
            session.release()
            session.player.release()
        }
        closeCastAudio()
        localPlayer = null
        mediaSession = null
        super.onDestroy()
    }

    private fun preserveQueueBeforeStopping() {
        if (app.playbackSettings.resumeQueue.value) mediaSession?.player?.let { queueRecorder.capture(it) }
        queueRecorder.stopping = true
    }

    private companion object {
        const val DISMISS_PLAYBACK = "com.reson.player.DISMISS_PLAYBACK"
    }
}

@OptIn(UnstableApi::class)
private class DismissibleNotification(
    private val delegate: MediaNotification.Provider,
    private val dismiss: PendingIntent,
) : MediaNotification.Provider by delegate {
    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChanged: MediaNotification.Provider.Callback,
    ): MediaNotification = delegate.createNotification(
        mediaSession,
        mediaButtonPreferences,
        object : MediaNotification.ActionFactory by actionFactory {
            override fun createNotificationDismissalIntent(mediaSession: MediaSession): PendingIntent = dismiss
        },
        onNotificationChanged,
    )
}
