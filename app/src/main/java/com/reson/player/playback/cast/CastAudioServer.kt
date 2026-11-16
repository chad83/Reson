package com.reson.player.playback.cast

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.google.android.gms.cast.MediaQueueItem
import com.reson.player.data.LocalNasNetwork
import com.reson.player.playback.toTrack
import com.reson.player.playback.visualizer.BoundedConnections
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
internal class CastAudioServer(
    private val lan: LocalNasNetwork,
    private val endpoint: LocalNasNetwork.LocalIpv4,
    private val receiver: InetAddress,
    private val resolver: ContentResolver,
    private val sources: DataSource.Factory,
) : NanoHTTPD(endpoint.address.hostAddress, 0) {
    private val root = "/${UUID.randomUUID()}/"
    private val tracks = ConcurrentHashMap<String, MediaItem>()
    private val paths = mutableMapOf<MediaItem, String>()

    init {
        setAsyncRunner(BoundedConnections())
    }

    fun toCastItem(item: MediaItem, converter: MediaItemConverter): MediaQueueItem {
        val path = paths.getOrPut(item) {
            (root + paths.size).also { tracks[it] = item }
        }
        val mime = mimeFromName(item.toTrack().originalUri.toUri().lastPathSegment)
        return converter.toMediaQueueItem(
            item.buildUpon()
                .setMediaId(path)
                .setUri("http://${endpoint.address.hostAddress}:$listeningPort$path")
                .setCustomCacheKey(null)
                .setMimeType(mime)
                .setMediaMetadata(
                    item.mediaMetadata.buildUpon().setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setExtras(null).setArtworkUri(null).setArtworkData(null, null).build(),
                )
                .build(),
        )
    }

    fun originalItem(uri: Uri): MediaItem? = tracks[uri.path]

    override fun useGzipWhenAccepted(response: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        val peer = runCatching { InetAddress.getByName(session.remoteIpAddress) }.getOrNull()
        val item = tracks[session.uri]
        if (item == null || peer == null || peer != receiver || !lan.accepts(endpoint, peer) ||
            session.headers["host"] != "${endpoint.address.hostAddress}:$listeningPort"
        ) return reply(Response.Status.NOT_FOUND)
        if (session.method == Method.OPTIONS) return reply(Response.Status.NO_CONTENT)
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return reply(Response.Status.METHOD_NOT_ALLOWED)
        }

        val configuration = requireNotNull(item.localConfiguration)
        val uri = configuration.uri
        val source = sources.createDataSource()
        var keepOpen = false
        try {
            val spec = DataSpec.Builder().setUri(uri).setKey(configuration.customCacheKey).build()
            val size = source.open(spec)
            val mime = contentType(uri, item)
            val header = session.headers["range"].takeUnless { session.method == Method.HEAD }
            if (header == null) {
                val response = when {
                    session.method == Method.HEAD -> newFixedLengthResponse(
                        Response.Status.OK, mime, ByteArrayInputStream(ByteArray(0)), size.coerceAtLeast(0),
                    )
                    size == C.LENGTH_UNSET.toLong() ->
                        newChunkedResponse(Response.Status.OK, mime, CastAudioInput(source))
                    else -> newFixedLengthResponse(Response.Status.OK, mime, CastAudioInput(source), size)
                }
                keepOpen = session.method == Method.GET
                return response.withHeaders()
            }
            val range = byteRange(header, size) ?: return reply(Response.Status.RANGE_NOT_SATISFIABLE)
                .apply { if (size >= 0) addHeader("Content-Range", "bytes */$size") }
            if (range.first > 0) {
                source.close()
                source.open(spec.subrange(range.first, range.last - range.first + 1))
            }
            val length = range.last - range.first + 1
            return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, CastAudioInput(source), length)
                .withHeaders().apply {
                    addHeader("Content-Range", "bytes ${range.first}-${range.last}/$size")
                    keepOpen = true
                }
        } catch (_: Exception) {
            return reply(Response.Status.SERVICE_UNAVAILABLE)
        } finally {
            if (!keepOpen) runCatching { source.close() }
        }
    }

    private fun contentType(uri: Uri, item: MediaItem): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            resolver.getType(uri)?.takeIf { it.startsWith("audio/") }?.let { return it }
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            mimeFromName(name)?.let { return it }
        }
        return mimeFromName(item.toTrack().originalUri.toUri().lastPathSegment) ?: "application/octet-stream"
    }

    private fun reply(status: Response.Status): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, "").withHeaders()

    private fun Response.withHeaders(): Response = apply {
        closeConnection(true)
        addHeader("Accept-Ranges", "bytes")
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Range")
        addHeader("Access-Control-Expose-Headers", "Accept-Ranges, Content-Range")
        addHeader("Cache-Control", "no-store")
    }

    private companion object {
        fun mimeFromName(name: String?): String? = when (name?.substringAfterLast('.')?.lowercase()) {
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "m4a", "mp4" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            else -> null
        }

        fun byteRange(header: String, size: Long): LongRange? {
            if (size <= 0 || !header.startsWith("bytes=")) return null
            val parts = header.removePrefix("bytes=").split('-', limit = 2)
            if (parts.size != 2 || parts.any { part -> part.any { it !in '0'..'9' } }) return null
            if (parts[0].isEmpty()) {
                val suffix = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
                return (size - suffix.coerceAtMost(size)) until size
            }
            val start = parts[0].toLongOrNull()?.takeIf { it < size } ?: return null
            val end = if (parts[1].isEmpty()) size - 1 else parts[1].toLongOrNull() ?: return null
            return (start..end.coerceAtMost(size - 1)).takeUnless { it.isEmpty() }
        }
    }
}

@OptIn(UnstableApi::class)
private class CastAudioInput(private val source: DataSource) : InputStream() {
    private var closed = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 255
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        if (length == 0) 0 else source.read(buffer, offset, length)

    override fun close() {
        if (closed) return
        closed = true
        runCatching { source.close() }
    }
}
