package com.reson.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.reson.player.data.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayGainTest {
    @OptIn(UnstableApi::class)
    @Test
    fun processorAcceptsMedia3EmptyInputBeforeReceivingAudio() {
        val processor = ReplayGainProcessor()
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)

        assertEquals(0, processor.output.remaining())
    }

    @Test
    fun albumAndTrackApplyTheirOwnTaggedGain() {
        val tags = mapOf("REPLAYGAIN_TRACK_GAIN" to "-6.02 dB", "REPLAYGAIN_ALBUM_GAIN" to "+6.02 dB",
            "REPLAYGAIN_ALBUM_PEAK" to "0.4")

        assertEquals(0.5f, replayGainFactor(tags, ReplayGainMode.Track), 0.001f)
        assertEquals(2f, replayGainFactor(tags, ReplayGainMode.Album), 0.001f)
        assertEquals(1f, replayGainFactor(tags, ReplayGainMode.Off), 0f)
    }

    @Test
    fun missingAlbumTagsFallBackAndPeakLimitsBoost() {
        val tags = mapOf("REPLAYGAIN_TRACK_GAIN" to "+12 dB", "REPLAYGAIN_TRACK_PEAK" to "0.8")

        assertEquals(1.25f, replayGainFactor(tags, ReplayGainMode.Album), 0.001f)
        assertEquals(1f, replayGainFactor(mapOf("REPLAYGAIN_TRACK_GAIN" to "NaN"), ReplayGainMode.Track), 0f)
    }
}
