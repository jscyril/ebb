package com.jscyril.ebb.ui.theme

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The app's only motion specs. Springs, so interrupted animations stay smooth. */
@Suppress("FunctionName")
object EbbMotion {
    /** Presses, toggles, small state flips. */
    fun <T> Fast(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    /** Most layout/colour changes. */
    fun <T> Standard(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    /** Large, calm moves: overlays, progress rings draining. */
    fun <T> Slow(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)

    /** Overlay entrance: fade + gentle scale from 0.92 + a slight rise. A quick fade when [reduced]. */
    fun enter(reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(tween(120))
        else fadeIn(Standard()) + scaleIn(Standard(), initialScale = 0.92f) +
            slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)) { it / 24 }

    fun exit(reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(tween(120))
        else fadeOut(Fast()) + scaleOut(Fast(), targetScale = 0.96f) +
            slideOutVertically(spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = IntOffset.VisibilityThreshold)) { it / 32 }
}

/** True when the user turned animations off (developer options or accessibility "Remove animations"). */
@Composable
fun rememberReducedMotion(): Boolean {
    val cr = LocalContext.current.contentResolver
    return remember(cr) { Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
