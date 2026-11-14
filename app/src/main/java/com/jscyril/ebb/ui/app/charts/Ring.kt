package com.jscyril.ebb.ui.app.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jscyril.ebb.ui.theme.EbbMotion
import com.jscyril.ebb.ui.theme.rememberReducedMotion

/** 0 -> 1 once on first composition (spring), or already 1 under reduced motion. Read `.value` in the draw phase. */
@Composable
internal fun rememberEntrance(delayMs: Long = 0): Animatable<Float, AnimationVector1D> {
    val reduced = rememberReducedMotion()
    val a = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        a.animateTo(1f, EbbMotion.Slow())
    }
    return a
}

/** Thin progress ring for [fraction] (clamped to 0..1) that sweeps in on first draw; [content] sits in the centre. */
@Composable
fun RingChart(fraction: Float, description: String, modifier: Modifier = Modifier, stroke: Dp = 14.dp, content: @Composable BoxScope.() -> Unit) {
    val entrance = rememberEntrance()
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), EbbMotion.Slow(), label = "ring")
    val cs = MaterialTheme.colorScheme
    val track = cs.surfaceContainerHighest
    val arc = cs.primary
    val density = LocalDensity.current
    val style = remember(density, stroke) { Stroke(with(density) { stroke.toPx() }, cap = StrokeCap.Round) }
    Box(
        modifier.clearAndSetSemantics { contentDescription = description }.drawBehind {
            val d = size.minDimension - style.width
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            val s = Size(d, d)
            drawArc(track, 0f, 360f, false, tl, s, style = style)
            val sweep = 360f * f * entrance.value.coerceIn(0f, 1f)
            if (sweep > 0.5f) drawArc(arc, -90f, sweep, false, tl, s, style = style)
        },
        contentAlignment = Alignment.Center,
        content = content,
    )
}
