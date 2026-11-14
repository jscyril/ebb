package com.jscyril.ebb.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jscyril.ebb.R
import com.jscyril.ebb.engine.Tier
import com.jscyril.ebb.ui.theme.BigButton
import com.jscyril.ebb.ui.theme.EbbMotion
import com.jscyril.ebb.ui.theme.TierChip
import com.jscyril.ebb.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay

/** Pre-open sheet: pick a tier, then confirm. Two steps so a stray tap never starts a session. No skip. */
@Composable
fun PromptScreen(
    appName: String, appIcon: ImageBitmap?, options: List<Tier>, usedTodayMin: Int, capMin: Int,
    onPick: (Tier) -> Unit, onGoHome: () -> Unit,
) = Overlay(
    compact = { CompactOverlay(appName, appIcon, stringResource(R.string.prompt_compact, appName), onGoHome, locked = false) },
) {
    val reduced = rememberReducedMotion()
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    val scrim by animateFloatAsState(if (shown.targetState) 0.6f else 0f, tween(if (reduced) 120 else 320), label = "scrim")
    var selected by remember(options) { mutableStateOf<Tier?>(null) }
    val cols = if (maxWidth >= 440.dp) 2 else 1

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(shown, enter = EbbMotion.enter(reduced), exit = EbbMotion.exit(reduced)) {
            Surface(
                Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 12.dp,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AppIcon(appName, appIcon, 64.dp)
                    Text(
                        stringResource(R.string.prompt_title, appName),
                        style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    )
                    Usage(usedTodayMin, capMin, reduced)
                    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        options.chunked(cols).forEachIndexed { row, tiers ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                tiers.forEachIndexed { col, t ->
                                    Staggered(row * cols + col, reduced, Modifier.weight(1f)) {
                                        TierChip(t.sessionMin, t.lockMin, selected == t, { selected = t }, Modifier.fillMaxWidth())
                                    }
                                }
                                repeat(cols - tiers.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    val pick = selected
                    BigButton(
                        if (pick != null) stringResource(R.string.prompt_start, pick.sessionMin) else stringResource(R.string.prompt_pick),
                        { pick?.let(onPick) },
                        Modifier.fillMaxWidth(),
                        enabled = pick != null,
                    )
                    TextButton(onGoHome, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.prompt_not_now)) }
                }
            }
        }
    }
}

@Composable
private fun Usage(usedMin: Int, capMin: Int, reduced: Boolean) {
    val target = if (capMin > 0) (usedMin.toFloat() / capMin).coerceIn(0f, 1f) else 1f
    val p = remember { Animatable(if (reduced) target else 0f) }
    LaunchedEffect(target) { if (reduced) p.snapTo(target) else { delay(200); p.animateTo(target, EbbMotion.Slow()) } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.prompt_used, usedMin, capMin),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Decorative: the line above already says it.
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(p.value.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** Fades and rises [content] in, [index] x 40 ms after the sheet starts. */
@Composable
private fun Staggered(index: Int, reduced: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val a = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) { delay(120L + index * 40L); a.animateTo(1f, EbbMotion.Standard()) } }
    Box(modifier.graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 20.dp.toPx() }) { content() }
}
