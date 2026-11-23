package com.reson.player

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reson.player.data.audioReadPermission
import com.reson.player.data.hasAudioReadPermission
import com.reson.player.ui.CollectionsViewModel
import com.reson.player.ui.PlayerViewModel
import com.reson.player.ui.NasViewModel
import com.reson.player.ui.OfflineViewModel
import com.reson.player.ui.ResonApp
import com.reson.player.ui.ResonTheme

class MainActivity : ComponentActivity() {
    private data class PlaybackRequest(val query: String, val hints: Bundle)

    private var playbackRequest by mutableStateOf<PlaybackRequest?>(null)
    private val model: PlayerViewModel by viewModels {
        PlayerViewModel.factory(application as ResonApplication)
    }
    private val nasModel: NasViewModel by viewModels {
        NasViewModel.factory(application as ResonApplication)
    }
    private val offlineModel: OfflineViewModel by viewModels {
        OfflineViewModel.factory(application as ResonApplication)
    }
    private val collectionsModel: CollectionsViewModel by viewModels {
        CollectionsViewModel.factory(application as ResonApplication)
    }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updatePermission()
    }
    private val networkPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updatePermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updatePermission()
        if (savedInstanceState == null) handleMediaSearch(intent)
        val app = application as ResonApplication
        setContent {
            val skin by app.appearance.skin.collectAsStateWithLifecycle()
            ResonTheme(skin) {
                ResonApp(
                    model, nasModel, offlineModel, collectionsModel, app, skin,
                    onGrantAccess = ::requestAudioAccess, onGrantNetwork = ::requestNetworkAccess,
                )
                playbackRequest?.let { request ->
                    AlertDialog(
                        onDismissRequest = { playbackRequest = null },
                        title = { Text("Play music in Reson?") },
                        text = { Text("Another app requested playback. This will replace your current queue.") },
                        confirmButton = {
                            TextButton(onClick = {
                                playbackRequest = null
                                model.playFromSearch(request.query, request.hints)
                            }) { Text("Play") }
                        },
                        dismissButton = {
                            TextButton(onClick = { playbackRequest = null }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMediaSearch(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleMediaSearch(intent: Intent) {
        if (intent.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        fun searchText(key: String): String? =
            runCatching { intent.getStringExtra(key)?.take(200) }.getOrNull()

        val hints = Bundle().apply {
            for (key in listOf(
                MediaStore.EXTRA_MEDIA_PLAYLIST, MediaStore.EXTRA_MEDIA_ALBUM,
                MediaStore.EXTRA_MEDIA_ARTIST, MediaStore.EXTRA_MEDIA_TITLE,
            )) {
                searchText(key)?.let { putString(key, it) }
            }
        }
        playbackRequest = PlaybackRequest(searchText(SearchManager.QUERY).orEmpty(), hints)
    }

    override fun onResume() {
        super.onResume()
        updatePermission()
        model.connect()
        model.reloadLibraryIndex()
        collectionsModel.refresh()
    }

    private fun updatePermission() {
        model.setPermission(hasAudioReadPermission())
        nasModel.setPermission((application as ResonApplication).localNasNetwork.hasPermission())
    }

    private fun requestAudioAccess() {
        requestAccess(audioReadPermission(), "requested_audio", permissionRequest::launch)
    }

    private fun requestNetworkAccess() {
        if (Build.VERSION.SDK_INT >= 37) {
            requestAccess(Manifest.permission.ACCESS_LOCAL_NETWORK, "requested_lan", networkPermissionRequest::launch)
        }
    }

    private fun requestAccess(permission: String, preference: String, launch: (String) -> Unit) {
        val preferences = getPreferences(MODE_PRIVATE)
        if (preferences.getBoolean(preference, false) &&
            !shouldShowRequestPermissionRationale(permission)
        ) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        } else {
            preferences.edit { putBoolean(preference, true) }
            launch(permission)
        }
    }
}
