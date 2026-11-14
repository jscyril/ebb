package com.jscyril.ebb.ui.overlay

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jscyril.ebb.R
import com.jscyril.ebb.engine.SessionEngine
import com.jscyril.ebb.ui.theme.BigButton
import com.jscyril.ebb.ui.theme.EbbMotion
import com.jscyril.ebb.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay

private const val WAIT_SEC = 60

/** Deliberate friction: a 60 s wait that cannot be skipped, then a typed phrase. Cancel any time. */
@Composable
fun EmergencyScreen(appName: String, passesLeft: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    // No compact variant: the keyboard shrinks the window, and a size switch would drop the wait/phrase state.
    val reduced = rememberReducedMotion()
    var left by remember { mutableIntStateOf(WAIT_SEC) }
    LaunchedEffect(Unit) {
        val start = SystemClock.elapsedRealtime() // elapsed realtime: not shortened by changing the clock
        while (left > 0) {
            val e = SystemClock.elapsedRealtime() - start
            left = (WAIT_SEC - (e / 1000).toInt()).coerceAtLeast(0)
            delay(1_000 - e % 1_000)
        }
    }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.emergency_title), style = MaterialTheme.typography.headlineMedium)
            AnimatedContent(
                left == 0,
                transitionSpec = { fadeIn(EbbMotion.Standard()) togetherWith fadeOut(EbbMotion.Fast()) },
                label = "emergencyStep",
            ) { typing ->
                if (!typing) Wait(appName, passesLeft, left, reduced) else Phrase(appName, onConfirm)
            }
            TextButton(onCancel, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.emergency_cancel)) }
        }
    }
}

@Composable
private fun Wait(appName: String, passesLeft: Int, left: Int, reduced: Boolean) {
    // Each second glides linearly into the next, so the ring moves continuously.
    val p by animateFloatAsState(
        (WAIT_SEC - left + 1).coerceAtMost(WAIT_SEC) / WAIT_SEC.toFloat(),
        if (reduced) snap() else tween(1_000, easing = LinearEasing), label = "wait",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val desc = pluralStringResource(R.plurals.emergency_wait_desc, left, left)
        Ring({ p }, Modifier.size(200.dp).clearAndSetSemantics { contentDescription = desc }, stroke = 8.dp) {
            Text(formatRemaining(left * 1_000L), style = MaterialTheme.typography.displayMedium)
        }
        Text(
            pluralStringResource(R.plurals.emergency_cost, passesLeft, passesLeft),
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.emergency_wait, appName),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Phrase(appName: String, onConfirm: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val ok = phraseMatches(input)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            pluralStringResource(R.plurals.emergency_type, SessionEngine.EMERGENCY_MIN, appName, SessionEngine.EMERGENCY_MIN),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Text(stringResource(R.string.emergency_phrase, EMERGENCY_PHRASE), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        OutlinedTextField(
            input, { input = it },
            Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text(stringResource(R.string.emergency_field)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm() }),
        )
        BigButton(pluralStringResource(R.plurals.emergency_confirm, SessionEngine.EMERGENCY_MIN, SessionEngine.EMERGENCY_MIN), onConfirm, Modifier.fillMaxWidth(), enabled = ok)
    }
}
