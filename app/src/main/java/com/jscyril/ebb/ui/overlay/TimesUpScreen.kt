package com.jscyril.ebb.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jscyril.ebb.R
import com.jscyril.ebb.ui.theme.EbbMotion
import com.jscyril.ebb.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay

/** Session over: the ring springs closed, then the message. Calls [onDone] after 2 s or on tap. */
@Composable
fun TimesUpScreen(appName: String, appIcon: ImageBitmap?, nextUnlock: Long, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    var finished by remember { mutableStateOf(false) }
    val finish = { if (!finished) { finished = true; done() } }
    LaunchedEffect(Unit) { delay(2_000); finish() }
    // Cosmetic only: the service passes now when nothing is locked.
    val locked = nextUnlock > System.currentTimeMillis()
    val line = if (locked) stringResource(R.string.times_up_locked, appName, timeOfDay(nextUnlock))
    else stringResource(R.string.times_up_ended, appName)
    Overlay(compact = { CompactOverlay(appName, appIcon, stringResource(R.string.times_up), finish, locked) }) {
        val reduced = rememberReducedMotion()
        val ring = remember { Animatable(if (reduced) 1f else 0.78f) }
        var text by remember { mutableStateOf(reduced) }
        LaunchedEffect(Unit) {
            if (reduced) return@LaunchedEffect
            ring.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
        }
        LaunchedEffect(Unit) { delay(250); text = true }
        FullBleed(Modifier.clickable(remember { MutableInteractionSource() }, null, onClickLabel = stringResource(R.string.times_up_dismiss), role = Role.Button) { finish() }) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                Ring(ring.value, Modifier.size(168.dp), stroke = 10.dp) { AppIcon(appName, appIcon, 64.dp, locked) }
                AnimatedVisibility(text, enter = if (reduced) EbbMotion.enter(true) else fadeIn(EbbMotion.Standard()) + slideInVertically(EbbMotion.Standard()) { it / 3 }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.times_up), style = MaterialTheme.typography.displaySmall)
                        Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
