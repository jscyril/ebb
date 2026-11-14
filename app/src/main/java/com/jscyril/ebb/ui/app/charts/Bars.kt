package com.jscyril.ebb.ui.app.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jscyril.ebb.ui.theme.EbbMotion
import com.jscyril.ebb.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Small inverse chip placed over [centerFraction] of the width (clamped to the edges); slides when it moves. */
@Composable
internal fun TooltipChip(text: String, centerFraction: Float, modifier: Modifier = Modifier) {
    val c by animateFloatAsState(centerFraction, EbbMotion.Standard(), label = "tip")
    Surface(
        modifier.layout { m, cons ->
            val p = m.measure(cons.copy(minWidth = 0))
            layout(cons.maxWidth, p.height) {
                p.place((c * cons.maxWidth - p.width / 2f).roundToInt().coerceIn(0, (cons.maxWidth - p.width).coerceAtLeast(0)), 0)
            }
        },
        color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
    }
}

/**
 * Seven-day bars: rounded data-ends on a shared baseline, one hue (selected = primary, others lighter),
 * optional dashed [guide] line. Tap a bar (or a TalkBack custom action) to [onSelect] it.
 */
@Composable
fun WeekBars(
    values: List<Long>, labels: List<String>, selected: Int, today: Int, guide: Long?, tooltip: String,
    description: String, actionLabels: List<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val n = values.size
    val reduced = rememberReducedMotion()
    val grow = remember(n) { List(n) { Animatable(if (reduced) 1f else 0f) } }
    LaunchedEffect(grow) { grow.forEachIndexed { i, a -> launch { delay(30L * i); a.animateTo(1f, EbbMotion.Slow()) } } }
    val cs = MaterialTheme.colorScheme
    val on = cs.primary
    // Opaque over the card, so the squared baseline overdraw doesn't double the alpha.
    val off = remember(on, cs.surfaceContainerLow) { on.copy(alpha = 0.45f).compositeOver(cs.surfaceContainerLow) }
    val stub = cs.outlineVariant
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) }
    val max = remember(values, guide) { maxOf(values.maxOrNull() ?: 0L, guide ?: 0L, 1L).toFloat() }
    val select by rememberUpdatedState(onSelect)
    val actions = remember(actionLabels) { actionLabels.mapIndexed { i, l -> CustomAccessibilityAction(l) { select(i); true } } }

    Column(modifier.clearAndSetSemantics { contentDescription = description; stateDescription = tooltip; customActions = actions }) {
        if (n > 0) TooltipChip(tooltip, (selected + 0.5f) / n, Modifier.padding(bottom = 8.dp))
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).pointerInput(n) {
                detectTapGestures { o -> if (n > 0) select((o.x / size.width * n).toInt().coerceIn(0, n - 1)) }
            },
        ) {
            if (n == 0) return@Canvas
            val slot = size.width / n
            val gap = 2.dp.toPx()
            val w = minOf(slot - gap, 32.dp.toPx())
            val r = CornerRadius(4.dp.toPx())
            for (i in 0 until n) {
                val x = i * slot + (slot - w) / 2
                val h = values[i] / max * size.height * grow[i].value
                if (values[i] == 0L) { drawRect(stub, Offset(x, size.height - 2.dp.toPx()), Size(w, 2.dp.toPx())); continue }
                val c = if (i == selected) on else off
                drawRoundRect(c, Offset(x, size.height - h), Size(w, h), r)
                // Square the baseline end: only the data end is rounded.
                val sq = minOf(h, r.y)
                drawRect(c, Offset(x, size.height - sq), Size(w, sq))
            }
            if (guide != null && guide > 0) {
                val y = size.height - guide / max * size.height
                drawLine(cs.outlineVariant, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            labels.forEachIndexed { i, l ->
                Text(
                    l, Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (i == today) FontWeight.Bold else null,
                    color = if (i == today || i == selected) cs.onSurface else cs.onSurfaceVariant,
                )
            }
        }
    }
}
