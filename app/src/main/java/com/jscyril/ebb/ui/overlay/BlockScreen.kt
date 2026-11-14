package com.jscyril.ebb.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.jscyril.ebb.R
import com.jscyril.ebb.engine.Reason
import com.jscyril.ebb.ui.theme.BigButton
import com.jscyril.ebb.ui.theme.EbbMotion
import com.jscyril.ebb.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.random.Random
import kotlin.math.sin

/**
 * The app is locked until [until]. [now] is trusted time at show; [lockedFrom] is when the lock began
 * (drives the ring). Calls [onUnlocked] once the lock runs out, so the service re-gates.
 * Only the countdown text and the ring follow the clock; the rest of the screen does not recompose per second.
 */
@Composable
fun BlockScreen(
    appName: String, appIcon: ImageBitmap?, until: Long, reason: Reason, passesLeft: Int,
    onEmergency: () -> Unit, onGoHome: () -> Unit, onUnlocked: () -> Unit, now: Long, lockedFrom: Long,
) {
    val clock = tickingNow(now)
    val unlockedCb by rememberUpdatedState(onUnlocked)
    LaunchedEffect(until) { snapshotFlow { clock.value >= until }.first { it }; unlockedCb() }
    val unlockAt = timeOfDay(until)
    Overlay(compact = { CompactOverlay(appName, appIcon, stringResource(R.string.block_compact, unlockAt), onGoHome) }) {
        val reduced = rememberReducedMotion()
        val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
        val messages = stringArrayResource(R.array.block_messages)
        val messageAt = remember { Random.nextInt(messages.size) } // once per show
        val ringSize = min(260.dp, min(maxWidth * 0.68f, maxHeight * 0.34f))

        FullBleed {
            Tide(Modifier.fillMaxSize(), animate = !reduced)
            AnimatedVisibility(shown, enter = EbbMotion.enter(reduced)) {
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    AppIcon(appName, appIcon, 64.dp, locked = true)
                    Text(appName, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(0.4f))
                    val desc = stringResource(R.string.block_ring_desc, unlockAt)
                    LockRing(clock, lockedFrom, until, reduced, Modifier.size(ringSize).clearAndSetSemantics { contentDescription = desc }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Countdown(clock, until)
                            Text(
                                stringResource(R.string.block_until, unlockAt),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.weight(0.4f))
                    Text(
                        stringResource(if (reason == Reason.COOLDOWN) R.string.block_reason_cooldown else R.string.block_reason_cap),
                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    )
                    Text(
                        messages[messageAt], Modifier.padding(top = 8.dp).widthIn(max = 360.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.weight(1f))
                    BigButton(stringResource(R.string.overlay_go_home), onGoHome, Modifier.widthIn(max = 420.dp).fillMaxWidth())
                    TextButton(onEmergency, Modifier.padding(top = 4.dp).heightIn(min = 48.dp), enabled = passesLeft > 0) {
                        Text(
                            if (passesLeft > 0) pluralStringResource(R.plurals.block_emergency, passesLeft, passesLeft)
                            else stringResource(R.string.block_no_passes),
                        )
                    }
                }
            }
        }
    }
}

/** The only text that recomposes each second. */
@Composable
private fun Countdown(clock: State<Long>, until: Long) =
    Text(formatRemaining(until - clock.value), style = MaterialTheme.typography.displayMedium)

/** Ring that fills in from empty on show, then follows the clock with a slow spring; it only redraws. */
@Composable
private fun LockRing(clock: State<Long>, from: Long, until: Long, reduced: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val p = remember { Animatable(if (reduced) ringProgress(clock.value, from, until) else 0f) }
    LaunchedEffect(from, until) {
        snapshotFlow { ringProgress(clock.value, from, until) }
            .collectLatest { if (reduced) p.snapTo(it) else p.animateTo(it, EbbMotion.Slow()) }
    }
    Ring({ p.value }, modifier, stroke = 8.dp) { content() }
}

/** Three slow layered sine waves along the bottom. One infinite transition; static when [animate] is false. */
@Composable
private fun Tide(modifier: Modifier, animate: Boolean) {
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
            val base = size.height * (0.70f + i * 0.08f)
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
