package com.reson.player.playback.visualizer

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class VisualizerSettings(context: Context) {
    private val preferences = context.getSharedPreferences("visualizer", Context.MODE_PRIVATE)
    private val value = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = value.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean("enabled", enabled) }
        value.value = enabled
    }
}
