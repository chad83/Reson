package com.reson.player.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class Skin(val id: String, val label: String) {
    Green("green", "Green"),
    Purple("purple", "Purple"),
    Orange("orange", "Orange"),
}

internal class AppearanceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(
        Skin.entries.firstOrNull { it.id == preferences.getString("skin", null) } ?: Skin.Green,
    )
    val skin = selected.asStateFlow()

    fun setSkin(skin: Skin) {
        preferences.edit { putString("skin", skin.id) }
        selected.value = skin
    }
}
