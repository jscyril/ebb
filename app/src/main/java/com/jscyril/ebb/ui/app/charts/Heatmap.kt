package com.jscyril.ebb.ui.app.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

private const val COLS = 12
private val CELL_H = 28.dp
private val LABEL_H = 20.dp
private val GAP = 3.dp

/**
 * 24 hours as 2 rows of 12 cells, single-hue scale from surfaceContainerHighest (0) to primary (max).
 * [hourLabels] are drawn under columns 0 and 6 of each row (12a 6a / 12p 6p). Cells fade in left to right.
 */
@Composable
fun HourHeatmap(
    hourly: IntArray, hourLabels: List<String>, selected: Int?, tooltip: String?, description: String,
    onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val entrance = rememberEntrance()
    val cs = MaterialTheme.colorScheme
    val colors = remember(hourly, cs.primary, cs.surfaceContainerHighest) {
        val max = (hourly.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
        Array(24) { lerp(cs.surfaceContainerHighest, cs.primary, hourly[it] / max) }
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val labels = remember(hourLabels, labelStyle) { hourLabels.map { measurer.measure(it, labelStyle) } }
    val labelColor = cs.onSurfaceVariant
    val ring = cs.onSurface
    val density = LocalDensity.current
    val ringStroke = remember(density) { Stroke(with(density) { 2.dp.toPx() }) }
    val select by rememberUpdatedState(onSelect)

    Column(modifier.clearAndSetSemantics { contentDescription = description; if (tooltip != null) stateDescription = tooltip }) {
        if (selected != null && tooltip != null) TooltipChip(tooltip, (selected % COLS + 0.5f) / COLS, Modifier.padding(bottom = 8.dp))
        Canvas(
            Modifier.fillMaxWidth().height(CELL_H * 2 + LABEL_H * 2 + GAP).pointerInput(Unit) {
                detectTapGestures { o ->
                    val col = (o.x / size.width * COLS).toInt().coerceIn(0, COLS - 1)
                    val row = if (o.y < size.height / 2) 0 else 1
                    select(row * COLS + col)
                }
            },
        ) {
            val gap = GAP.toPx()
            val cw = (size.width - gap * (COLS - 1)) / COLS
            val ch = CELL_H.toPx()
            val block = ch + LABEL_H.toPx() + gap
            val r = CornerRadius(6.dp.toPx())
            val p = entrance.value
            for (h in 0 until 24) {
                val row = h / COLS
                val col = h % COLS
                val tl = Offset(col * (cw + gap), row * block)
                // Wave: each cell starts its fade a little after the one before.
                val a = (p * 1.6f - h / 23f * 0.6f).coerceIn(0f, 1f)
                drawRoundRect(colors[h], tl, Size(cw, ch), r, alpha = a)
                if (h == selected) drawRoundRect(ring, tl, Size(cw, ch), r, style = ringStroke)
            }
            labels.forEachIndexed { i, l ->
                val row = i / 2
                val col = (i % 2) * 6
                drawText(l, labelColor, Offset(col * (cw + gap), row * block + ch + 2.dp.toPx()))
            }
        }
    }
}
