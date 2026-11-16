package com.reson.player.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ReplayGainMode(val label: String) { Off("Off"), Track("Track"), Album("Album") }

internal class PlaybackSettings(context: Context) {
    private val preferences = context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE)
    private val resume = MutableStateFlow(preferences.getBoolean("resume_queue", true))
    private val gain = MutableStateFlow(
        ReplayGainMode.entries.firstOrNull { it.name == preferences.getString("replay_gain", null) }
            ?: ReplayGainMode.Off,
    )
    val resumeQueue = resume.asStateFlow()
    val replayGain = gain.asStateFlow()

    fun setResumeQueue(enabled: Boolean) {
        preferences.edit { putBoolean("resume_queue", enabled) }
        resume.value = enabled
    }

    fun setReplayGain(mode: ReplayGainMode) {
        preferences.edit { putString("replay_gain", mode.name) }
        gain.value = mode
    }
}
