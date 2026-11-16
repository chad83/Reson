package com.reson.player.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryTest {
    @Test
    fun recordsWhenAudioStartsAndIgnoresPauseAndResumeWithinThatVisit() {
        val gate = PlaybackHistoryGate()

        assertFalse(gate.started(true, false, true, false))
        assertTrue(gate.started(true, true, false, false))
        assertFalse(gate.started(true, false, false, false))
        assertFalse(gate.started(true, true, false, false))
    }

    @Test
    fun recordsTransitionsAndReplaysAsSeparateVisits() {
        val gate = PlaybackHistoryGate()

        assertTrue(gate.started(true, true, true, false))
        assertTrue(gate.started(true, true, true, false))
        assertFalse(gate.started(true, false, false, true))
        assertTrue(gate.started(true, true, false, false))
        assertFalse(gate.started(false, false, false, false))
    }

    @Test
    fun downloadedAndStreamedCopiesShareIdentityWithinOneNasSource() {
        val remote = Track("reson-nas://profile/share/file.mp3", "Song", null, null, 0, source = "first")
        val offline = remote.copy(uri = "reson-offline://copy", downloaded = true)
        val other = remote.copy(source = "second")

        assertEquals(remote.identity, offline.identity)
        assertFalse(remote.identity == other.identity)
    }
}
