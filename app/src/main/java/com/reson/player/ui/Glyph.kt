package com.reson.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class Symbol { Wave, Note, Play, Pause, Previous, Next, Refresh, Down, Phone, Back, Folder, Nas, More, Settings, Drag }

@Composable
internal fun Glyph(symbol: Symbol, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        val origin = Offset((size.width - 24 * unit) / 2, (size.height - 24 * unit) / 2)
        fun point(x: Float, y: Float) = origin + Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 1.8f) =
            drawLine(color, point(x1, y1), point(x2, y2), width * unit, StrokeCap.Round)
        fun triangle(x1: Float, x2: Float) {
            val a = point(x1, 5.4f)
            val b = point(x1, 18.6f)
            val tip = point(x2, 12f)
            drawPath(Path().apply {
                moveTo(a.x, a.y)
                lineTo(tip.x, tip.y)
                lineTo(b.x, b.y)
                close()
            }, color)
        }

        when (symbol) {
            Symbol.Wave -> {
                line(3f, 10f, 3f, 14f)
                line(7.5f, 7f, 7.5f, 17f)
                line(12f, 4f, 12f, 20f)
                line(16.5f, 8f, 16.5f, 16f)
                line(21f, 10f, 21f, 14f)
            }
            Symbol.Note -> {
                drawOval(color, point(5.5f, 15f), Size(7f * unit, 5f * unit))
                line(12f, 17f, 12f, 5f)
                line(12f, 5f, 18f, 7f)
            }
            Symbol.Play -> triangle(8f, 19f)
            Symbol.Pause -> {
                line(9f, 6f, 9f, 18f, 3f)
                line(15f, 6f, 15f, 18f, 3f)
            }
            Symbol.Previous -> {
                triangle(18f, 7f)
                line(5f, 6f, 5f, 18f)
            }
            Symbol.Next -> {
                triangle(6f, 17f)
                line(19f, 6f, 19f, 18f)
            }
            Symbol.Refresh -> {
                drawArc(color, -27f, -292f, false, point(4.5f, 4.5f),
                    Size(15 * unit, 15 * unit), style = Stroke(1.8f * unit, cap = StrokeCap.Round))
                line(19.5f, 4.5f, 19.5f, 10f)
                line(19.5f, 10f, 14.5f, 10f)
            }
            Symbol.Down -> {
                line(5f, 9f, 12f, 16f)
                line(12f, 16f, 19f, 9f)
            }
            Symbol.Phone -> {
                drawRoundRect(color, point(7f, 3f), Size(10 * unit, 18 * unit),
                    androidx.compose.ui.geometry.CornerRadius(2 * unit), style = Stroke(1.7f * unit))
                line(11f, 17.5f, 13f, 17.5f, 1.5f)
            }
            Symbol.Back -> {
                line(13f, 5f, 6f, 12f)
                line(6f, 12f, 13f, 19f)
                line(6f, 12f, 20f, 12f)
            }
            Symbol.Folder -> {
                val top = point(3f, 7f)
                val tab = point(9f, 7f)
                val edge = point(11f, 10f)
                val right = point(21f, 10f)
                val lowerRight = point(21f, 19f)
                val lowerLeft = point(3f, 19f)
                drawPath(Path().apply {
                    moveTo(top.x, top.y)
                    lineTo(tab.x, tab.y)
                    lineTo(edge.x, edge.y)
                    lineTo(right.x, right.y)
                    lineTo(lowerRight.x, lowerRight.y)
                    lineTo(lowerLeft.x, lowerLeft.y)
                    close()
                }, color, style = Stroke(1.7f * unit))
            }
            Symbol.Settings -> {
                line(4f, 6f, 20f, 6f)
                line(4f, 12f, 20f, 12f)
                line(4f, 18f, 20f, 18f)
                line(9f, 4f, 9f, 8f, 3f)
                line(15f, 10f, 15f, 14f, 3f)
                line(8f, 16f, 8f, 20f, 3f)
            }
            Symbol.Drag -> {
                line(4f, 9f, 20f, 9f)
                line(4f, 15f, 20f, 15f)
            }
            Symbol.More -> {
                for (y in listOf(6f, 12f, 18f)) drawCircle(color, 1.35f * unit, point(12f, y))
            }
            Symbol.Nas -> {
                drawRoundRect(color, point(4f, 5f), Size(16 * unit, 14 * unit),
                    androidx.compose.ui.geometry.CornerRadius(2 * unit), style = Stroke(1.7f * unit))
                line(4f, 12f, 20f, 12f, 1.7f)
                drawCircle(color, 0.9f * unit, point(7.5f, 8.5f))
                drawCircle(color, 0.9f * unit, point(7.5f, 15.5f))
            }
        }
    }
}
