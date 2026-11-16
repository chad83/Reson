package com.reson.player.domain

internal class PlaybackHistoryGate {
    private var recorded = false

    fun started(hasItem: Boolean, playing: Boolean, transitioned: Boolean, ended: Boolean): Boolean {
        if (transitioned || ended || !hasItem) recorded = false
        if (!hasItem || !playing || recorded) return false
        recorded = true
        return true
    }
}
