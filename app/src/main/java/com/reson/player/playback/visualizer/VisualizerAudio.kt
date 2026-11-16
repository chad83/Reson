package com.reson.player.playback.visualizer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
internal class VisualizerAudio(
    private val settings: VisualizerSettings,
    private val television: TvVisualizer,
) {
    fun observe(sink: AudioSink): AudioSink = object : ForwardingAudioSink(sink) {
        private var sampleRate = 0
        private var channels = 0

        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
            super.configure(audioSinkConfig)
            val format = audioSinkConfig.format
            sampleRate = format.sampleRate
            channels = if (format.pcmEncoding == C.ENCODING_PCM_16BIT) format.channelCount else 0
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            val start = buffer.position()
            val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            if (channels > 0 && buffer.position() > start) {
                observe(buffer, start, buffer.position(), channels, sampleRate)
            }
            return handled
        }
    }

    private val samples = MutableStateFlow<FloatArray?>(null)
    val waveform = samples.asStateFlow()

    @Volatile var showWaveform = false
        set(value) {
            field = value
            if (!value) samples.value = null
        }
    @Volatile var playing = false
        set(value) {
            field = value
            if (!value) samples.value = null
        }

    private var lastWaveform = 0L

    private fun observe(buffer: ByteBuffer, start: Int, end: Int, channels: Int, sampleRate: Int) {
        if (!settings.enabled.value || !playing || sampleRate !in 8000..192000) return
        val stream = television.stream
        val now = if (showWaveform) System.nanoTime() else 0L
        val draw = showWaveform && now - lastWaveform >= 33_000_000L
        if (stream == null && !draw) return
        val frames = (end - start) / (2 * channels)
        if (frames == 0) return
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (draw) {
            lastWaveform = now
            val waveform = FloatArray(96) { i ->
                val frame = i * minOf(frames, 1024) / 96
                val offset = start + frame * channels * 2
                val left = input.getShort(offset).toFloat()
                val right = if (channels > 1) input.getShort(offset + 2).toFloat() else left
                (left + right) / 65_536f
            }
            if (showWaveform && playing && settings.enabled.value) samples.value = waveform
        }
        if (stream != null) {
            var index = 0
            while (index < frames) {
                val count = minOf(2048, frames - index)
                stream.offer(stereoPacket(input, start + index * channels * 2, count, channels, sampleRate))
                index += count
            }
        }
    }
}

internal fun stereoPacket(input: ByteBuffer, start: Int, frames: Int, channels: Int, sampleRate: Int): ByteArray {
    val packet = ByteBuffer.allocate(8 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
    packet.putInt(sampleRate).putInt(frames)
    for (frame in 0 until frames) {
        val index = start + frame * channels * 2
        val left = input.getShort(index)
        val right = if (channels > 1) input.getShort(index + 2) else left
        packet.putShort(left).putShort(right)
    }
    return packet.array()
}
