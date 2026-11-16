package com.reson.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reson.player.playback.visualizer.TelevisionState
import com.reson.player.playback.visualizer.VisualizerAudio

@Composable
internal fun VisualizerScreen(
    audio: VisualizerAudio,
    television: TelevisionState,
    hasLanPermission: Boolean,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    onGrantNetwork: () -> Unit,
    onStartTv: () -> Unit,
    onStopTv: () -> Unit,
) {
    val waveform by audio.waveform.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val colors = MaterialTheme.colorScheme
    LifecycleResumeEffect(audio) {
        audio.showWaveform = true
        onPauseOrDispose { audio.showWaveform = false }
    }

    PlayerListScreen("Visualizer", onBack, onLibrary) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
            Canvas(Modifier.fillMaxWidth().heightIn(min = 235.dp)) {
                val points = waveform
                for (line in 1..5) {
                    val y = size.height * line / 6
                    drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y))
                }
                if (points != null) {
                    val path = Path()
                    for (index in points.indices) {
                        val x = size.width * index / (points.size - 1)
                        val y = size.height * (.5f - points[index] * .46f)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, colors.primary, style = Stroke(2.dp.toPx()))
                }
            }
            Text("TV mode", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleLarge)
            Text("Use a computer or TV browser on the same Wi-Fi. You can lock the phone after connecting.")
            FlowRow {
                if (!hasLanPermission) {
                    TextButton(onClick = onGrantNetwork) { Text("Allow local network access") }
                } else if (television.url == null) {
                    TextButton(onClick = onStartTv) { Text("Start TV mode") }
                } else {
                    TextButton(onClick = onStopTv) { Text("Stop TV mode") }
                }
            }
            television.url?.let { url ->
                if (television.connected) {
                    Text("TV connected. Audio is routed to the TV.")
                } else {
                    Text("Open this single-use link within five minutes. Use a trusted Wi-Fi network.")
                    SelectionContainer { Text(url, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge) }
                }
            }
            television.error?.let { Text(it, color = colors.error) }
        }
    }
}
