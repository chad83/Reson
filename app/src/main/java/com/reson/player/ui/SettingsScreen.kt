package com.reson.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reson.player.data.ReplayGainMode
import com.reson.player.data.Skin

@Composable
internal fun SettingsScreen(
    skin: Skin,
    onSkinChanged: (Skin) -> Unit,
    visualizerEnabled: Boolean,
    onVisualizerChanged: (Boolean) -> Unit,
    resumeQueue: Boolean,
    onResumeQueueChanged: (Boolean) -> Unit,
    replayGain: ReplayGainMode,
    onReplayGainChanged: (ReplayGainMode) -> Unit,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    PlayerListScreen("Settings", onBack, onLibrary) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text("Skin color", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            FlowRow(
                Modifier.padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Skin.entries.forEach { option ->
                    FilterChip(
                        selected = skin == option,
                        onClick = { onSkinChanged(option) },
                        label = { Text(option.label) },
                        leadingIcon = {
                            Box(Modifier.size(16.dp).background(option.colors(dark).primary, CircleShape))
                        },
                    )
                }
            }
            HorizontalDivider()
            SettingSwitch("Resume last queue", "Restore the queue and position when you reopen Reson.",
                resumeQueue, onResumeQueueChanged)
            HorizontalDivider()
            Text("ReplayGain", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            Text("Use ReplayGain tags to balance volume on this device. Album mode preserves volume differences within albums.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in ReplayGainMode.entries) {
                    FilterChip(selected = replayGain == mode, onClick = { onReplayGainChanged(mode) },
                        label = { Text(mode.label) })
                }
            }
            HorizontalDivider()
            SettingSwitch("Visualizer", "Show music-reactive visuals on your phone or a compatible TV browser.",
                visualizerEnabled, onVisualizerChanged)
        }
    }
}

@Composable
private fun SettingSwitch(label: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
