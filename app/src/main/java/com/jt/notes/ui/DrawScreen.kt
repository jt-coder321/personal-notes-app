package com.jt.notes.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One point of a pen stroke, with the width it was drawn at. */
private data class StrokePoint(val offset: Offset, val widthPx: Float)

/** One drag of the pen or stylus. */
private data class Stroke(val color: Color, val points: List<StrokePoint>)

private val InkColors = listOf(
    Color.Black,
    Color(0xFF1D4ED8),
    Color(0xFFDC2626),
    Color(0xFF15803D),
    Color(0xFF7C3AED),
)

private val StrokeWidthsDp = listOf(3f, 8f, 16f)

/** A full-screen drawing pad. Saving returns the sketch as a bitmap on a white background. */
@Composable
fun DrawDialog(onSave: (Bitmap) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DrawScreen(onSave = onSave, onCancel = onCancel)
    }
}

@Composable
private fun DrawScreen(onSave: (Bitmap) -> Unit, onCancel: () -> Unit) {
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    var current by remember { mutableStateOf<Stroke?>(null) }
    var colorIndex by remember { mutableIntStateOf(0) }
    var widthIndex by remember { mutableIntStateOf(1) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val widthsPx = remember(density) { StrokeWidthsDp.map { with(density) { it.dp.toPx() } } }
    val baseWidth = widthsPx[widthIndex]
    val ink = InkColors[colorIndex]

    // Stylus pressure widens the line; finger input reports no usable pressure
    // and keeps the chosen width.
    fun widthFor(pressure: Float): Float =
        if (pressure > 0f) {
            (baseWidth * (0.6f + pressure * 0.8f)).coerceIn(baseWidth * 0.5f, baseWidth * 1.6f)
        } else {
            baseWidth
        }

    fun render(all: List<Stroke>): Bitmap {
        val bitmap = Bitmap.createBitmap(
            canvasSize.width.coerceAtLeast(1),
            canvasSize.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        for (stroke in all) {
            paint.color = stroke.color.toArgb()
            val pts = stroke.points
            for (i in 0 until pts.size - 1) {
                paint.strokeWidth = pts[i].widthPx
                canvas.drawLine(pts[i].offset.x, pts[i].offset.y, pts[i + 1].offset.x, pts[i + 1].offset.y, paint)
            }
            if (pts.size == 1) {
                paint.strokeWidth = pts[0].widthPx
                canvas.drawPoint(pts[0].offset.x, pts[0].offset.y, paint)
            }
        }
        return bitmap
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Text(
                    "Sketch",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                TextButton(
                    enabled = strokes.isNotEmpty() || current != null,
                    onClick = { onSave(render(strokes + listOfNotNull(current))) },
                ) { Text("Save") }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(Color.White)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(colorIndex, widthIndex) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                current = Stroke(ink, listOf(StrokePoint(offset, baseWidth)))
                            },
                            onDragEnd = {
                                current?.let { strokes = strokes + it }
                                current = null
                            },
                            onDragCancel = { current = null },
                        ) { change, _ ->
                            change.consume()
                            val prev = current
                            if (prev != null) {
                                current = prev.copy(points = prev.points + StrokePoint(change.position, widthFor(change.pressure)))
                            }
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    for (stroke in strokes + listOfNotNull(current)) {
                        val pts = stroke.points
                        for (i in 0 until pts.size - 1) {
                            drawLine(stroke.color, pts[i].offset, pts[i + 1].offset, strokeWidth = pts[i].widthPx, cap = StrokeCap.Round)
                        }
                        if (pts.size == 1) {
                            drawCircle(stroke.color, radius = pts[0].widthPx / 2f, center = pts[0].offset)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InkColors.forEachIndexed { index, color ->
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (index == colorIndex) {
                                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { colorIndex = index },
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = strokes.isNotEmpty(),
                    onClick = { strokes = strokes.dropLast(1) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("Undo")
                }
                TextButton(
                    enabled = strokes.isNotEmpty() || current != null,
                    onClick = { strokes = emptyList(); current = null },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("Clear") }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Thin", style = MaterialTheme.typography.labelLarge)
                StrokeWidthsDp.forEachIndexed { index, width ->
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { widthIndex = index },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width.dp.coerceAtLeast(4.dp))
                                .clip(CircleShape)
                                .background(
                                    if (index == widthIndex) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                ),
                        )
                    }
                }
                Text("Thick", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                "Saved sketches show with the note's pictures",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            )
        }
    }
}

