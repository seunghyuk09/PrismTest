package com.prismtest.scope

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ValidationEditMode { ROI, POINTS }

@Composable
fun ValidationCanvas(bitmap: Bitmap, roi: ValidationRoi, truth: List<ValidationPoint>,
                     run: ValidationFrameRun?, comparison: ValidationComparison?,
                     mode: ValidationEditMode, enabled: Boolean, showAll: Boolean,
                     modifier: Modifier, onRoi: (ValidationRoi) -> Unit, onPoint: (ValidationPoint) -> Unit) {
    var measured by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember(bitmap) { mutableStateOf<ValidationPoint?>(null) }
    var dragEnd by remember(bitmap) { mutableStateOf<ValidationPoint?>(null) }
    val currentOnPoint by rememberUpdatedState(onPoint)
    val currentOnRoi by rememberUpdatedState(onRoi)
    val source = if (mode == ValidationEditMode.ROI) ValidationRoi(0, 0, bitmap.width, bitmap.height) else roi
    fun viewport(): FloatArray {
        val scale = min(measured.width.toFloat() / source.width, measured.height.toFloat() / source.height)
        return floatArrayOf((measured.width - source.width * scale) / 2,
            (measured.height - source.height * scale) / 2, scale)
    }
    fun point(offset: Offset): ValidationPoint? {
        val v = viewport()
        if (v[2] <= 0) return null
        val x = source.left + (offset.x - v[0]) / v[2]
        val y = source.top + (offset.y - v[1]) / v[2]
        if (x < source.left || x >= source.right || y < source.top || y >= source.bottom) return null
        return ValidationPoint(x.toDouble(), y.toDouble())
    }
    Canvas(modifier.onSizeChanged { measured = it }.pointerInput(bitmap, roi, mode, measured, enabled) {
        if (!enabled) return@pointerInput
        if (mode == ValidationEditMode.ROI) {
            detectDragGestures(onDragStart = { dragStart = point(it); dragEnd = dragStart },
                onDrag = { change, _ -> change.consume(); point(change.position)?.let { dragEnd = it } },
                onDragCancel = { dragStart = null; dragEnd = null },
                onDragEnd = {
                    val a = dragStart; val b = dragEnd
                    if (a != null && b != null) currentOnRoi(ValidationRoi(min(a.x, b.x).roundToInt(),
                        min(a.y, b.y).roundToInt(), max(a.x, b.x).roundToInt(), max(a.y, b.y).roundToInt()))
                    dragStart = null; dragEnd = null
                })
        } else detectTapGestures { position -> point(position)?.let(currentOnPoint) }
    }) {
        drawRect(Color(0xFF070D12))
        val v = viewport(); if (v[2] <= 0) return@Canvas
        drawContext.canvas.nativeCanvas.drawBitmap(bitmap,
            android.graphics.Rect(source.left, source.top, source.right, source.bottom),
            android.graphics.RectF(v[0], v[1], v[0] + source.width * v[2], v[1] + source.height * v[2]),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        fun offset(p: ValidationPoint) = Offset(v[0] + (p.x - source.left).toFloat() * v[2],
            v[1] + (p.y - source.top).toFloat() * v[2])
        if (mode == ValidationEditMode.ROI) {
            val a = dragStart; val b = dragEnd
            val selected = if (a != null && b != null) ValidationRoi(min(a.x, b.x).toInt(), min(a.y, b.y).toInt(),
                max(a.x, b.x).toInt(), max(a.y, b.y).toInt()) else roi
            drawRect(Color(0xFFFFC857), offset(ValidationPoint(selected.left.toDouble(), selected.top.toDouble())),
                Size(selected.width * v[2], selected.height * v[2]), style = Stroke(2.dp.toPx()))
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; textSize = 12.dp.toPx(); isFakeBoldText = true
        }
        run?.candidates?.let { candidates ->
            (if (showAll) candidates else candidates.take(8)).forEach { c ->
                val center = offset(ValidationPoint(c.x, c.y))
                drawCircle(Color(0xFFFFC857), max(6.dp.toPx(), (c.radius + 2).toFloat() * v[2]), center,
                    style = Stroke(1.5.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText("${c.rank}", center.x + 7.dp.toPx(), center.y, paint)
            }
        }
        truth.forEachIndexed { i, p ->
            val center = offset(p)
            val color = if (comparison?.missed?.contains(i) == true) Color(0xFFFF6573) else Color(0xFF28DBA1)
            drawCircle(color, 9.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            drawLine(color, center - Offset(4.dp.toPx(), 0f), center + Offset(4.dp.toPx(), 0f), 1.dp.toPx())
            drawLine(color, center - Offset(0f, 4.dp.toPx()), center + Offset(0f, 4.dp.toPx()), 1.dp.toPx())
        }
    }
}
