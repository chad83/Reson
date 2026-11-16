package com.reson.player.domain

import java.text.Normalizer
import java.text.Normalizer.Form.NFC
import java.text.Normalizer.Form.NFD
import java.util.Locale

internal enum class LibraryView(val label: String) {
    Tracks("Tracks"),
    Albums("Albums"),
    Artists("Artists"),
}

internal data class LibraryGroup(
    val key: String,
    val title: String,
    val artist: String?,
    val tracks: List<Track>,
) {
    val cover: Track? get() = tracks.firstOrNull { it.artworkRevision != null }
}

internal fun List<Track>.matching(query: String): List<Track> {
    val words = query.trim().split(whitespace).filter(String::isNotEmpty).map(String::librarySearchKey)
    if (words.isEmpty()) return this
    return filter { track ->
        val tags = listOfNotNull(track.title, track.artist, track.album, track.albumArtist)
            .map(String::librarySearchKey)
        words.all { word -> tags.any { it.contains(word, ignoreCase = true) } }
    }
}

internal fun List<Track>.grouped(view: LibraryView): List<LibraryGroup> {
    require(view != LibraryView.Tracks)
    return groupBy { track ->
        val fields = when (view) {
            LibraryView.Albums -> listOf(track.album?.metadataKey(), track.albumCredit?.librarySearchKey())
            LibraryView.Artists -> listOf(track.artistCredit?.librarySearchKey())
            LibraryView.Tracks -> error("Track view has no groups")
        }
        fields.joinToString("") { tag -> if (tag == null) "-" else "${tag.length}:$tag" }
    }.map { (key, tracks) ->
        val first = tracks.first()
        when (view) {
            LibraryView.Albums -> LibraryGroup(
                key,
                first.album ?: "Unknown album",
                preferredCredit(tracks, Track::albumCredit),
                tracks.sortedWith(compareBy<Track> { it.discNumber ?: 1 }.thenBy { it.trackNumber ?: Int.MAX_VALUE }),
            )
            LibraryView.Artists -> LibraryGroup(
                key, preferredCredit(tracks, Track::artistCredit) ?: "Unknown artist", null, tracks,
            )
            LibraryView.Tracks -> error("Track view has no groups")
        }
    }.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, LibraryGroup::title)
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist.orEmpty() }
            .thenBy(LibraryGroup::key),
    )
}

private val Track.artistCredit: String? get() = artist ?: albumArtist
private val Track.albumCredit: String? get() = albumArtist ?: artist

private fun preferredCredit(tracks: List<Track>, credit: (Track) -> String?): String? {
    val first = credit(tracks.first()) ?: return null
    val key = first.librarySearchKey()
    return tracks.firstNotNullOfOrNull { track -> credit(track)?.takeIf { it.metadataKey() != key } } ?: first
}

private fun String.metadataKey(): String = Normalizer.normalize(lowercase(Locale.ROOT), NFC)

internal fun String.librarySearchKey(): String {
    val lower = lowercase(Locale.ROOT)
    if (lower.all { it <= '\u007f' }) return lower

    val decomposed = Normalizer.normalize(lower, NFD)
    // Discard combining marks on Latin letters only. Other scripts use marks to form distinct letters.
    val folded = buildString(decomposed.length) {
        var followsLatinLetter = false
        var offset = 0
        while (offset < decomposed.length) {
            val codePoint = decomposed.codePointAt(offset)
            when (Character.getType(codePoint)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt() -> if (!followsLatinLetter) appendCodePoint(codePoint)
                else -> {
                    appendCodePoint(codePoint)
                    followsLatinLetter = Character.isLetter(codePoint) &&
                        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
                }
            }
            offset += Character.charCount(codePoint)
        }
    }
    return Normalizer.normalize(folded, NFC)
}

private val whitespace = Regex("\\s+")
