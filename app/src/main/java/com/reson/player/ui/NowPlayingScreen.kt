package com.reson.player.ui

import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
@Composable
internal fun NowPlayingScreen(
    playback: PlaybackState,
    readPosition: () -> Long,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    onVisualizer: (() -> Unit)?,
    hasLanPermission: Boolean,
    onGrantNetwork: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    trackActions: List<MenuAction>,
    downloadQueued: Boolean,
    onDownload: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphButton("Back", Symbol.Down, onBack)
                    Text(
                        "Now playing", Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (hasLanPermission) {
                        MediaRouteButton()
                    } else if (playback.mediaId != null) {
                        TextButton(onClick = onGrantNetwork) { Text("Cast") }
                    }
                    TextButton(onClick = onLibrary) { Text("Library") }
                }
                if (playback.mediaId == null) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Choose a track from the library.",
                            textAlign = TextAlign.Center,
                            color = colors.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Artwork(
                            artwork = playback.artwork,
                            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f),
                            cornerSize = 30.dp,
                        )
                        Spacer(Modifier.height(26.dp))
                        Text(
                            playback.title.ifBlank { "Untitled track" },
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(playback.artist, playback.album).joinToString(" · ")
                                .ifBlank { "Unknown artist" },
                            modifier = Modifier.padding(top = 5.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph(if (playback.isNas && !playback.hasDownload) Symbol.Nas else Symbol.Phone, colors.primary, Modifier.size(15.dp))
                            Text(playback.sourceLabel,
                                color = colors.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        if (onDownload != null) {
                            TextButton(onClick = onDownload, enabled = !downloadQueued) {
                                Text(if (downloadQueued) "Download queued" else "Download")
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        SeekBar(playback, readPosition, onSeek)
                        if (playback.buffering) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                        playback.error?.let { message ->
                            Text(
                                message,
                                Modifier.padding(top = 8.dp),
                                color = colors.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Row(
                            Modifier.padding(top = 22.dp),
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlyphButton("Previous track", Symbol.Previous, onPrevious, enabled = playback.canGoPrevious)
                            PlayButton(playback.playing, onTogglePlayback, size = 74.dp)
                            GlyphButton("Next track", Symbol.Next, onNext, enabled = playback.canGoNext)
                        }
                        FlowRow(
                            Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            PlaybackModes(playback, onShuffle, onRepeat)
                            TextButton(onClick = onQueue) { Text("Queue") }
                            if (onVisualizer != null) TextButton(onClick = onVisualizer) { Text("Visualizer") }
                            if (trackActions.isNotEmpty()) ActionMenu("Track actions", trackActions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(playback: PlaybackState, readPosition: () -> Long, onSeek: (Long) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var position by remember(playback.mediaId) { mutableLongStateOf(readPosition()) }
    var dragging by remember(playback.mediaId) { mutableStateOf(false) }
    var dragFraction by remember(playback.mediaId) { mutableFloatStateOf(0f) }
    LaunchedEffect(lifecycle, playback.mediaId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                position = readPosition()
                delay(300)
            }
        }
    }

    val fraction = if (dragging) dragFraction else if (playback.durationMs > 0) {
        (position.toDouble() / playback.durationMs).toFloat().coerceIn(0f, 1f)
    } else 0f
    val visiblePosition = if (dragging) (dragFraction * playback.durationMs).roundToLong() else position

    Slider(
        value = fraction,
        onValueChange = {
            dragFraction = it
            dragging = true
        },
        onValueChangeFinished = {
            if (dragging) {
                position = (dragFraction * playback.durationMs).roundToLong()
                onSeek(position)
                dragging = false
            }
        },
        enabled = playback.canSeek && playback.durationMs > 0,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(formatTime(visiblePosition), color = color, style = MaterialTheme.typography.labelMedium)
        Text(
            if (playback.durationMs > 0) formatTime(playback.durationMs) else "–:––",
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
internal fun GlyphButton(
    label: String,
    symbol: Symbol,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.semantics { contentDescription = label }) {
        Glyph(symbol, if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f))
    }
}

@Composable
internal fun PlayButton(playing: Boolean, onClick: () -> Unit, size: Dp) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size).semantics {
            contentDescription = if (playing) "Pause" else "Play"
            role = Role.Button
        },
        shape = CircleShape,
        color = colors.primary,
        contentColor = colors.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Glyph(if (playing) Symbol.Pause else Symbol.Play, colors.onPrimary, Modifier.size(size * 0.43f))
        }
    }
}

@Composable
internal fun Artwork(artwork: ByteArray?, modifier: Modifier, cornerSize: Dp) {
    val image by produceState<ImageBitmap?>(null, artwork) {
        value = artwork?.let { bytes ->
            withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options()
                while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 900) {
                    options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }
        }
    }
    val clipped = modifier.clip(RoundedCornerShape(cornerSize))
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = clipped, contentScale = ContentScale.Crop)
    } else {
        val palette = LocalArtworkColors.current
        Box(
            clipped.background(Brush.linearGradient(listOf(palette.background, Color(0xFF101A23)))),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension
                val tint = palette.accent
                for (ring in 1..5) {
                    drawCircle(tint.copy(alpha = 0.11f), radius * (0.15f + ring * 0.066f),
                        style = Stroke((radius * 0.003f).coerceAtLeast(0.5f)))
                }
                drawCircle(palette.center, radius * 0.20f)
                drawCircle(tint.copy(alpha = 0.40f), radius * 0.20f,
                    style = Stroke((radius * 0.004f).coerceAtLeast(0.5f)))
                drawCircle(tint.copy(alpha = 0.7f), radius * 0.012f,
                    center = center + Offset(radius * 0.28f, -radius * 0.21f))
            }
            Glyph(Symbol.Wave, palette.accent, Modifier.fillMaxSize(0.22f))
        }
    }
}

internal fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$remainder" else "$minutes:$remainder"
}
