package com.reson.player

import android.app.Application
import com.reson.player.data.AppearanceSettings
import com.reson.player.data.MediaStoreLibrary
import com.reson.player.data.KeystoreNasProfiles
import com.reson.player.data.LocalNasNetwork
import com.reson.player.data.PlaybackSettings
import com.reson.player.data.MdnsNasDiscovery
import com.reson.player.data.SmbNasClient
import com.reson.player.data.hasAudioReadPermission
import com.reson.player.data.library.MusicLibrary
import com.reson.player.data.library.OfflineDownloads
import com.reson.player.data.library.PlayerCollections
import com.reson.player.data.library.PlaylistDownloads
import com.reson.player.data.library.PlaylistFiles
import com.reson.player.playback.PlaybackQueueStore
import com.reson.player.playback.visualizer.TvVisualizer
import com.reson.player.playback.visualizer.VisualizerAudio
import com.reson.player.playback.visualizer.VisualizerSettings

class ResonApplication : Application() {
    internal val appearance by lazy { AppearanceSettings(this) }
    internal val playbackSettings by lazy { PlaybackSettings(this) }
    internal val playbackQueue by lazy { PlaybackQueueStore(this, playbackSettings) }
    internal val library by lazy {
        MusicLibrary(this, MediaStoreLibrary(this), nasProfiles, nasClient)
    }
    internal val offline by lazy { OfflineDownloads(this, library, nasProfiles, nasClient) }
    internal val collections by lazy { PlayerCollections(library, ::hasAudioReadPermission) }
    internal val playlistDownloads by lazy { PlaylistDownloads(this, collections, offline) }
    internal val playlistFiles by lazy { PlaylistFiles(this, library, collections, nasProfiles, nasClient) }
    val nasProfiles by lazy { KeystoreNasProfiles(this) }
    val localNasNetwork by lazy { LocalNasNetwork(this) }
    internal val visualizerSettings by lazy { VisualizerSettings(this) }
    internal val television by lazy { TvVisualizer(this, localNasNetwork, visualizerSettings) }
    internal val visualizerAudio by lazy { VisualizerAudio(visualizerSettings, television) }
    val nasClient by lazy { SmbNasClient(localNasNetwork) }
    val nasDiscovery by lazy { MdnsNasDiscovery(this, localNasNetwork) }
}
