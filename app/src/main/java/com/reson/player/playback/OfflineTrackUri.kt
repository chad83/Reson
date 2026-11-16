package com.reson.player.playback

import android.net.Uri

internal object OfflineTrackUri {
    const val SCHEME = "reson-offline"
    private val validId = Regex("[a-f0-9]{64}")

    fun create(id: String): Uri = Uri.Builder().scheme(SCHEME).authority(id).build()

    fun id(uri: Uri): String? =
        uri.encodedAuthority?.takeIf {
            uri.scheme == SCHEME && validId.matches(it) && uri.encodedPath.orEmpty().isEmpty() &&
                uri.encodedQuery == null && uri.encodedFragment == null
        }
}
