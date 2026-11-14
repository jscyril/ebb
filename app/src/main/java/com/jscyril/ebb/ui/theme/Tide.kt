package com.jscyril.ebb.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.sin

/** Three slow layered sine waves along the bottom; the top crest sits at [level] (fraction of height). One infinite transition; static when [animate] is false. */
@Composable
fun Tide(modifier: Modifier, animate: Boolean, level: Float = 0.70f) {
    val phase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "tide")
            .animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "phase")
    } else remember { mutableFloatStateOf(0.6f) }
    val color = MaterialTheme.colorScheme.primary
    val path = remember { Path() }
    // phase is read only here, in the draw phase, so the wave redraws without recomposing.
    Canvas(modifier) {
        val ph = phase.value
        for (i in 0..2) {
            path.reset()
            val amp = size.height * (0.028f - i * 0.006f)
            val base = size.height * (level + i * 0.08f)
            val k = (2 * PI / size.width).toFloat() * (1f + i * 0.5f)
            val speed = intArrayOf(1, -1, 2)[i] // whole multiples, so the loop at 2π is seamless
            path.moveTo(0f, size.height)
            var x = 0f
            while (x <= size.width + 12f) {
                path.lineTo(x, base + amp * sin(k * x + ph * speed + i * 1.7f))
                x += 12f
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, color.copy(alpha = 0.06f + i * 0.04f))
        }
    }
}
