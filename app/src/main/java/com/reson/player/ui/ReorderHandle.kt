package com.reson.player.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.unit.dp

@Composable
internal fun ReorderHandle(list: LazyListState, itemKey: String, onMove: (Int) -> Unit) {
    val move by rememberUpdatedState(onMove)
    val container = LocalPinnableContainer.current
    var dragging by remember(itemKey) { mutableStateOf(false) }

    Box(
        Modifier.size(width = 36.dp, height = 48.dp).pointerInput(list, itemKey, container) {
            var startIndex = -1
            var position = 0f
            var destination = -1
            var pin: PinnableContainer.PinnedHandle? = null
            try {
                detectVerticalDragGestures(
                    onDragStart = {
                        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
                        startIndex = item?.index ?: -1
                        destination = startIndex
                        position = if (item == null) 0f else item.offset + item.size / 2f
                        dragging = item != null
                        if (dragging) pin = container?.pin()
                    },
                    onVerticalDrag = { change, distance ->
                        if (!dragging) return@detectVerticalDragGestures
                        change.consume()
                        val layout = list.layoutInfo
                        val start = layout.viewportStartOffset.toFloat()
                        val end = layout.viewportEndOffset.toFloat()
                        if (end > start) {
                            val next = position + distance
                            val edge = next.coerceIn(start, end)
                            if (next != edge) list.dispatchRawDelta(next - edge)
                            position = edge
                            destination = list.layoutInfo.visibleItemsInfo.minByOrNull { item ->
                                kotlin.math.abs(item.offset + item.size / 2f - position)
                            }?.index ?: destination
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        pin?.release()
                        pin = null
                    },
                    onDragEnd = {
                        dragging = false
                        pin?.release()
                        pin = null
                        if (startIndex >= 0 && destination != startIndex) move(destination - startIndex)
                    },
                )
            } finally {
                pin?.release()
                dragging = false
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        val colors = MaterialTheme.colorScheme
        Glyph(Symbol.Drag, if (dragging) colors.primary else colors.onSurfaceVariant,
            Modifier.size(18.dp))
    }
}
