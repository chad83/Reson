package com.reson.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.reson.player.data.ReplayGainMode
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
internal class ReplayGainProcessor : BaseAudioProcessor() {
    @Volatile private var gain = 1f

    fun update(tracks: Tracks, mode: ReplayGainMode) {
        val tags = mutableMapOf<String, String>()
        if (mode != ReplayGainMode.Off) {
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
                for (index in 0 until group.length) {
                    if (!group.isTrackSelected(index)) continue
                    val metadata = group.getTrackFormat(index).metadata ?: continue
                    for (entry in 0 until metadata.length()) {
                        when (val tag = metadata[entry]) {
                            is VorbisComment -> tags[tag.key.uppercase(Locale.ROOT)] = tag.value
                            is TextInformationFrame -> if (tag.id == "TXXX") {
                                val key = tag.description ?: continue
                                tag.values.firstOrNull()?.let { tags[key.uppercase(Locale.ROOT)] = it }
                            }
                        }
                    }
                }
            }
        }
        gain = replayGainFactor(tags, mode)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        inputAudioFormat.takeIf { it.encoding == C.ENCODING_PCM_16BIT } ?: AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val factor = gain
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (factor == 1f) {
            output.put(inputBuffer)
        } else {
            while (inputBuffer.hasRemaining()) {
                val sample = (inputBuffer.short * factor).roundToInt()
                output.putShort(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        output.flip()
    }
}

internal fun replayGainFactor(tags: Map<String, String>, mode: ReplayGainMode): Float {
    if (mode == ReplayGainMode.Off) return 1f
    fun gain(scope: String) = tags["REPLAYGAIN_${scope}_GAIN"]?.trim()?.lowercase(Locale.ROOT)
        ?.removeSuffix("db")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -60.0..60.0 }
    val scope = if (mode == ReplayGainMode.Album && gain("ALBUM") != null) "ALBUM" else "TRACK"
    val decibels = gain(scope) ?: return 1f
    val peak = tags["REPLAYGAIN_${scope}_PEAK"]?.trim()?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0 } ?: 1.0
    return minOf(10.0.pow(decibels / 20), 1.0 / peak).toFloat()
}
