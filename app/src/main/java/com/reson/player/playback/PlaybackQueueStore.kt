package com.reson.player.playback

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.reson.player.data.PlaybackSettings
import com.reson.player.domain.Track
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class PlaybackQueue(
    val tracks: List<Track>,
    val index: Int,
    val position: Long,
    val shuffleOrder: List<Int>?,
)

internal class PlaybackQueueStore(context: Context, private val settings: PlaybackSettings) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "playback-queue.json"))
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val updates = Channel<PlaybackQueue?>(Channel.CONFLATED)
    @Volatile private var latest: QueueUpdate? = null

    init {
        CoroutineScope(SupervisorJob() + io).launch {
            for (queue in updates) {
                try {
                    if (queue == null || !settings.resumeQueue.value) file.delete() else write(queue)
                } catch (error: IOException) {
                    Log.w("Reson", "Could not save the playback queue", error)
                }
            }
        }
    }

    fun save(queue: PlaybackQueue?) {
        latest = QueueUpdate(queue)
        updates.trySend(queue)
    }

    suspend fun load(): PlaybackQueue? = withContext(io) {
        if (!settings.resumeQueue.value) return@withContext null
        latest?.let { return@withContext it.queue }
        try {
            val data = JSONObject(file.readFully().decodeToString())
            val items = data.getJSONArray("tracks")
            val tracks = List(items.length()) { index -> items.getJSONObject(index).toTrack() }
            val index = data.getInt("index")
            if (index !in tracks.indices) return@withContext null
            val order = data.optJSONArray("shuffleOrder")?.let { order -> List(order.length(), order::getInt) }
                ?.takeIf { it.size == tracks.size && it.all(tracks.indices::contains) && it.distinct().size == it.size }
            PlaybackQueue(tracks, index, data.getLong("position").coerceAtLeast(0), order)
        } catch (_: FileNotFoundException) {
            null
        } catch (error: Exception) {
            if (error !is IOException && error !is JSONException) throw error
            Log.w("Reson", "Could not read the playback queue", error)
            null
        }
    }

    private fun write(queue: PlaybackQueue) {
        val tracks = JSONArray().apply { queue.tracks.forEach { put(it.toJson()) } }
        val bytes = JSONObject().put("tracks", tracks).put("index", queue.index).put("position", queue.position)
            .put("shuffleOrder", queue.shuffleOrder?.let { JSONArray(it) })
            .toString().encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: IOException) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun Track.toJson() = JSONObject().put("uri", uri).put("original", originalUri)
        .put("title", title).put("artist", artist).put("album", album).put("albumArtist", albumArtist)
        .put("duration", durationMs).put("track", trackNumber).put("disc", discNumber)
        .put("artwork", artworkRevision).put("downloaded", downloaded).put("source", source)

    private fun JSONObject.toTrack() = Track(
        uri = getString("uri"), originalUri = getString("original"), title = getString("title"),
        artist = optString("artist").takeIf(String::isNotEmpty), album = optString("album").takeIf(String::isNotEmpty),
        albumArtist = optString("albumArtist").takeIf(String::isNotEmpty), durationMs = getLong("duration"),
        trackNumber = optInt("track").takeIf { it > 0 }, discNumber = optInt("disc").takeIf { it > 0 },
        artworkRevision = optString("artwork").takeIf(String::isNotEmpty), downloaded = optBoolean("downloaded"),
        source = getString("source"),
    )

    private class QueueUpdate(val queue: PlaybackQueue?)
}

@OptIn(UnstableApi::class)
internal class PlaybackQueueRecorder(private val store: PlaybackQueueStore) {
    private var tracks = emptyList<Track>()
    private var shuffleOrder: List<Int>? = null
    private var previous: PlaybackQueue? = null
    private var recordedQueue = false
    var stopping = false

    fun capture(player: Player, queueChanged: Boolean = false) {
        if (stopping) return
        if (queueChanged || tracks.isEmpty()) {
            tracks = List(player.mediaItemCount) { player.getMediaItemAt(it).toTrack() }
            shuffleOrder = if (player.shuffleModeEnabled) {
                val timeline = player.currentTimeline
                buildList {
                    var index = timeline.getFirstWindowIndex(true)
                    while (index != C.INDEX_UNSET) {
                        add(index)
                        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                    }
                }.takeIf { it.size == tracks.size }
            } else null
        }
        val index = player.currentMediaItemIndex
        if (index !in tracks.indices) {
            if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return
            if (recordedQueue) store.save(null)
            recordedQueue = false
            previous = null
            return
        }
        if (tracks.any { !it.uri.startsWith("content://media/") && !it.uri.startsWith("reson-") }) return
        recordedQueue = true
        val snapshot = PlaybackQueue(tracks, index, player.currentPosition.coerceAtLeast(0), shuffleOrder)
        if (snapshot != previous) store.save(snapshot)
        previous = snapshot
    }

    fun reset() {
        tracks = emptyList()
        shuffleOrder = null
        previous = null
        recordedQueue = false
        store.save(null)
    }
}
