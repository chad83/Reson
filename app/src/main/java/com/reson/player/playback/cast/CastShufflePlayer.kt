package com.reson.player.playback.cast

import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.media.MediaQueue
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.reson.player.playback.queueId

@OptIn(UnstableApi::class)
internal class CastShufflePlayer(
    player: Player,
    private val onShuffleFailed: () -> Unit,
) : ForwardingSimpleBasePlayer(player) {
    private var client: RemoteMediaClient? = null
    private var enabled = false
    private var originalOrder = emptyList<Int>()
    private var pendingHandoff: PendingHandoff? = null
    private var reordering = false
    private val playNextItems = mutableSetOf<String>()
    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = updateQueue()
    }
    private val queueCallback = object : MediaQueue.Callback() {
        override fun mediaQueueChanged() = updateQueue()
    }

    fun attach(client: RemoteMediaClient, shuffle: Boolean) {
        detach()
        enabled = shuffle
        this.client = client
        client.registerCallback(callback)
        client.mediaQueue.registerCallback(queueCallback)
    }

    fun detach() {
        client?.let {
            it.unregisterCallback(callback)
            it.mediaQueue.unregisterCallback(queueCallback)
        }
        client = null
        enabled = false
        originalOrder = emptyList()
        pendingHandoff = null
        reordering = false
        playNextItems.clear()
    }

    fun playNext(item: MediaItem) {
        if (mediaItemCount == 0) {
            setMediaItem(item)
            return
        }
        if (enabled) playNextItems.add(item.queueId(-1))
        addMediaItem(currentMediaItemIndex + 1, item)
    }

    fun transferToCast(source: Player, target: Player) {
        val timeline = source.currentTimeline
        if (!enabled || timeline.isEmpty) {
            CastPlayer.TransferCallback.DEFAULT.transferState(source, target)
            return
        }

        val state = PlayerTransferState.fromPlayer(source)
        val order = buildList {
            var index = timeline.getFirstWindowIndex(true)
            while (index != C.INDEX_UNSET) {
                add(index)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
            }
        }
        val currentIndex = state.currentMediaItemIndex
        val castItems = order.map(state.mediaItems::get)
        pendingHandoff = PendingHandoff(target, castItems, order.indices.sortedBy { order[it] })
        state.buildUpon()
            .setMediaItems(castItems)
            .setCurrentMediaItemIndex(order.indexOf(currentIndex))
            .build()
            .setToPlayer(target)
    }

    fun transferToLocal(source: Player, target: Player) {
        val state = PlayerTransferState.fromPlayer(source)
        val items = state.mediaItems
        val timeline = source.currentTimeline
        val window = Timeline.Window()
        val indices = items.indices.associateBy { timeline.getWindow(it, window).uid }
        val originalIndices = originalOrder.mapNotNull { indices[it] }
        val known = originalIndices.toSet()
        val order = originalIndices + items.indices.filterNot { it in known }
        state.buildUpon()
            .setShuffleModeEnabled(enabled)
            .setMediaItems(order.map(items::get))
            .setCurrentMediaItemIndex(order.indexOf(state.currentMediaItemIndex).coerceAtLeast(0))
            .build()
            .setToPlayer(target)
    }

    override fun getState(): State {
        val state = super.getState()
        if (client == null) {
            if (state.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return state
            // A Cast session without an audio server is being disconnected.
            val commands = state.availableCommands.buildUpon()
                .remove(Player.COMMAND_SET_MEDIA_ITEM)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
            return state.buildUpon().setAvailableCommands(commands).build()
        }
        return state.buildUpon()
            .setAvailableCommands(state.availableCommands.buildUpon().add(Player.COMMAND_SET_SHUFFLE_MODE).build())
            .setShuffleModeEnabled(enabled)
            .build()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        pendingHandoff = null
        playNextItems.clear()
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (client == null) return super.handleSetShuffleModeEnabled(shuffleModeEnabled)
        enabled = shuffleModeEnabled
        updateQueue()
        return Futures.immediateVoidFuture()
    }

    private fun updateQueue(forceRestore: Boolean = false) {
        val remote = client ?: return
        if (reordering) return
        val ids = remote.mediaQueue.itemIds.toList()
        if (ids.isEmpty()) {
            originalOrder = emptyList()
            return
        }
        val current = remote.mediaStatus?.currentItemId ?: return
        val currentIndex = ids.indexOf(current)
        if (currentIndex < 0) return
        pendingHandoff?.let { handoff ->
            // Wait for the receiver's newly loaded queue before assigning its IDs to the original order.
            if (ids.size != handoff.items.size || handoff.target.currentMediaItem !== handoff.items[currentIndex]) return
            originalOrder = handoff.originalIndices.map(ids::get)
            pendingHandoff = null
        }
        val previous = originalOrder
        val present = ids.toSet()
        val known = previous.toSet()
        val original = previous.filter { it in present } + ids.filterNot { it in known }
        val order = if (enabled) {
            if (present == known) return
            originalOrder = original
            if (playNextItems.isNotEmpty() && previous.isNotEmpty() && present.containsAll(known)) {
                val target = getPlayer()
                val added = ids.withIndex().filter { it.value !in known }
                val window = Timeline.Window()
                val timeline = target.currentTimeline
                // Match the receiver's assigned window IDs before treating an insertion as Play next.
                if (timeline.windowCount == ids.size && added.all { (index, id) ->
                        val item = timeline.getWindow(index, window)
                        item.uid == id && item.mediaItem.queueId(index) in playNextItems
                    }) {
                    val insert = previous.indexOf(current) + 1
                    originalOrder = previous.take(insert) + added.map { it.value } + previous.drop(insert)
                    added.forEach { (index, _) -> playNextItems.remove(timeline.getWindow(index, window).mediaItem.queueId(index)) }
                    return
                }
                if (timeline.windowCount != ids.size) {
                    originalOrder = previous
                    return
                }
            }
            // Reorder only the upcoming tracks so the receiver can keep playing the current item.
            val upcoming = ids.drop(currentIndex + 1)
            val shuffled = upcoming.shuffled()
            if (shuffled == upcoming && upcoming.size > 1) upcoming.drop(1) + upcoming.first() else shuffled
        } else {
            if (previous.isEmpty()) return
            original
        }
        if (order.isEmpty() || (!forceRestore && order == ids.takeLast(order.size))) {
            if (!enabled) originalOrder = emptyList()
            return
        }
        reordering = true
        val requested = enabled
        remote.queueReorderItems(order.toIntArray(), MediaQueueItem.INVALID_ITEM_ID, null).setResultCallback { result ->
            if (client !== remote) return@setResultCallback
            reordering = false
            if (result.status.isSuccess) {
                if (!requested) originalOrder = emptyList()
                updateQueue(forceRestore = requested && !enabled)
            } else if (enabled == requested) {
                enabled = !requested
                originalOrder = previous
                invalidateState()
                onShuffleFailed()
            } else {
                originalOrder = previous
                updateQueue()
            }
        }
    }

    private data class PendingHandoff(val target: Player, val items: List<MediaItem>, val originalIndices: List<Int>)
}
