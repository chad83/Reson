package com.reson.player.data.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.reson.player.data.hasAudioReadPermission
import com.reson.player.domain.LocalTrackMetadata
import com.reson.player.domain.NasClient
import com.reson.player.domain.NasException
import com.reson.player.domain.NasPaths
import com.reson.player.domain.NasProblem
import com.reson.player.domain.NasProfile
import com.reson.player.domain.NasProfiles
import com.reson.player.domain.Track
import com.reson.player.domain.TrackIdentity
import com.reson.player.domain.isPlaylistFile
import com.reson.player.domain.sourceId
import com.reson.player.playback.NasTrackUri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class PlaylistFiles(
    private val context: Context,
    private val library: MusicLibrary,
    private val collections: PlayerCollections,
    private val profiles: NasProfiles,
    private val nas: NasClient,
) {
    private val resolver = context.contentResolver

    suspend fun importDocument(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Imported playlist"
        val coroutine = currentCoroutineContext()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            readPlaylist { _, buffer, length ->
                coroutine.ensureActive()
                input.read(buffer, 0, length)
            }
        } ?: throw IOException("Couldn’t open playlist")
        import(name, bytes)
    }

    suspend fun importNas(profile: NasProfile, folder: List<String>, name: String): String = withContext(Dispatchers.IO) {
        require(isPlaylistFile(name) && NasPaths.validComponent(name))
        val login = profiles.login(profile.id)
        if (login.profile.sourceId() != profile.sourceId()) throw NasException(NasProblem.SourceChanged)
        val coroutine = currentCoroutineContext()
        val bytes = nas.open(login, folder + name).use { input ->
            if (input.size > MAX_PLAYLIST_BYTES) throw IOException("Playlist is too large")
            readPlaylist { offset, buffer, length ->
                coroutine.ensureActive()
                if (offset >= input.size) -1 else {
                    input.read(offset, buffer, 0, minOf(length.toLong(), input.size - offset).toInt())
                }
            }
        }
        import(name, bytes, profile, folder)
    }

    suspend fun exportDocument(uri: Uri, id: String): String = withContext(Dispatchers.IO) {
        val playlist = collections.snapshot().playlists.firstOrNull { it.id == id }
            ?: throw IOException("Playlist is unavailable")
        val nasProfiles = profiles.list().associateBy { it.id }
        val device = devicePaths()
        val coroutine = currentCoroutineContext()
        val entries = playlist.tracks.mapNotNull { track ->
            coroutine.ensureActive()
            val original = track.originalUri.toUri()
            val path = if (NasTrackUri.accepts(original)) {
                nasProfiles[original.authority]?.takeIf { it.sourceId() == track.source }?.let { profile ->
                    val host = if (':' in profile.host) "[${profile.host}]" else profile.host
                    val authority = if (profile.port == 445) host else "$host:${profile.port}"
                    Uri.Builder().scheme("smb").encodedAuthority(authority).apply {
                        original.pathSegments.forEach(::appendPath)
                    }.build().toString()
                }
            } else device[track.originalUri]?.let { it.absolute ?: it.relative }
            path?.takeUnless { '\r' in it || '\n' in it || it.startsWith('#') }?.let { it to track }
        }
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { output ->
            output.appendLine("#EXTM3U")
            for ((path, track) in entries) {
                coroutine.ensureActive()
                val title = track.title.replace('\r', ' ').replace('\n', ' ')
                output.appendLine("#EXTINF:${track.durationMs / 1000},$title")
                output.appendLine(path)
            }
        } ?: throw IOException("Couldn’t open export destination")
        result("Exported", entries.size, playlist.tracks.size - entries.size)
    }

    private suspend fun import(name: String, bytes: ByteArray, profile: NasProfile? = null, folder: List<String> = emptyList()): String {
        val tracks = library.tracks(context.hasAudioReadPermission()).associateBy(Track::identity)
        val savedProfiles = profiles.list()
        val device = devicePaths().entries.groupBy { it.value.relative.substringAfterLast('/').lowercase(Locale.ROOT) }
        val entries = playlistEntries(bytes)
        val coroutine = currentCoroutineContext()
        val imported = entries.mapNotNull { path ->
            coroutine.ensureActive()
            val uri = path.toUri()
            when {
                path.startsWith("\\\\") || uri.scheme.equals("smb", ignoreCase = true) -> {
                    val smb = if (path.startsWith("\\\\")) "smb:${path.replace('\\', '/')}".toUri() else uri
                    val share = smb.pathSegments.firstOrNull()
                    val candidates = savedProfiles.filter { saved ->
                        saved.host.equals(smb.host?.removeSurrounding("[", "]"), ignoreCase = true) &&
                            saved.port == smb.port.takeUnless { it == -1 }.let { it ?: 445 } &&
                            saved.share.equals(share, ignoreCase = true)
                    }
                    if (smb.encodedUserInfo != null || smb.query != null || smb.fragment != null) null
                    else candidates.singleOrNull()?.let { nasTrack(it, smb.pathSegments.drop(1), tracks) }
                }
                NasTrackUri.accepts(uri) -> savedProfiles.firstOrNull { it.id == uri.authority && it.share == uri.pathSegments.first() }
                    ?.let { nasTrack(it, uri.pathSegments.drop(1), tracks) }
                uri.scheme == "content" -> tracks[TrackIdentity(path, "")]
                profile != null && (uri.scheme == null || uri.scheme?.length == 1) ->
                    relativePlaylistPath(path, folder)?.let { nasTrack(profile, it, tracks) }
                uri.scheme == null || uri.scheme == "file" || uri.scheme?.length == 1 -> {
                    val filePath = (if (uri.scheme == "file") uri.path else path)?.replace('\\', '/')
                        ?.removePrefix("./") ?: return@mapNotNull null
                    val matches = device[filePath.substringAfterLast('/').lowercase(Locale.ROOT)].orEmpty()
                    val match = matches.firstOrNull { it.value.absolute == filePath } ?: matches.filter { (_, candidate) ->
                        filePath.equals(candidate.relative, ignoreCase = true) ||
                            filePath.endsWith("/${candidate.relative}", ignoreCase = true) ||
                            candidate.relative.endsWith("/$filePath", ignoreCase = true)
                    }.singleOrNull()
                    match?.let { tracks[TrackIdentity(it.key, "")] }
                }
                else -> null
            }
        }.distinctBy(Track::identity)
        if (imported.isEmpty()) return "No playlist tracks matched your device or saved NAS connections."
        val title = name.substringBeforeLast('.', name).trim().take(100).ifBlank { "Imported playlist" }
        collections.importPlaylist(title, imported)
        return result("Imported", imported.size, entries.size - imported.size)
    }

    private fun nasTrack(profile: NasProfile, path: List<String>, tracks: Map<TrackIdentity, Track>): Track? {
        if (path.isEmpty() || path.any { !NasPaths.validComponent(it) } ||
            !LocalTrackMetadata.supports(path.last(), false)
        ) return null
        val uri = NasTrackUri.create(profile.id, profile.share, path).toString()
        val source = profile.sourceId()
        return tracks[TrackIdentity(uri, source)]
            ?: Track(uri, LocalTrackMetadata.title(null, path.last()), null, null, 0, source = source)
    }

    @Suppress("DEPRECATION")
    private suspend fun devicePaths(): Map<String, DevicePath> {
        if (!context.hasAudioReadPermission()) return emptyMap()
        val audio = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
        val coroutine = currentCoroutineContext()
        return resolver.query(audio, columns, null, null, null)?.use { rows ->
            buildMap {
                while (rows.moveToNext()) {
                    coroutine.ensureActive()
                    val volume = rows.getString(1) ?: continue
                    val name = rows.getString(3) ?: continue
                    val id = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), rows.getLong(0))
                    val relative = listOfNotNull(rows.getString(2)?.trimEnd('/')?.takeIf(String::isNotEmpty), name).joinToString("/")
                    put(id.toString(), DevicePath(relative, rows.getString(4)))
                }
            }
        }.orEmpty()
    }

    private fun result(verb: String, count: Int, skipped: Int): String = buildString {
        append("$verb $count ${if (count == 1) "track" else "tracks"}.")
        if (skipped > 0) append(" Skipped $skipped.")
    }

    private data class DevicePath(val relative: String, val absolute: String?)
}

private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024

private fun readPlaylist(read: (Long, ByteArray, Int) -> Int): ByteArray {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (bytes.size() <= MAX_PLAYLIST_BYTES) {
        val count = read(bytes.size().toLong(), buffer, minOf(buffer.size, MAX_PLAYLIST_BYTES + 1 - bytes.size()))
        if (count < 0) return bytes.toByteArray()
        if (count == 0 || count > buffer.size) throw IOException("Couldn’t read playlist")
        bytes.write(buffer, 0, count)
    }
    throw IOException("Playlist is too large")
}

internal fun playlistEntries(bytes: ByteArray): List<String> {
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        bytes.toString(charset("windows-1252"))
    }
    return text.removePrefix("\uFEFF").lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
}

internal fun relativePlaylistPath(path: String, folder: List<String>): List<String>? {
    val normalized = path.replace('\\', '/')
    if (normalized.startsWith('/') || ':' in normalized) return null
    val segments = folder.toMutableList()
    for (part in normalized.split('/')) {
        when {
            part.isEmpty() || part == "." -> Unit
            part == ".." -> if (segments.removeLastOrNull() == null) return null
            !NasPaths.validComponent(part) -> return null
            else -> segments.add(part)
        }
    }
    return segments.takeIf { it.isNotEmpty() }
}
