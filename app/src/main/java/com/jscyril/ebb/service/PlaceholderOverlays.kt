package com.jscyril.ebb.service

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jscyril.ebb.R
import com.jscyril.ebb.engine.Tier
import kotlinx.coroutines.delay

// Placeholder overlay screens; Task 8 replaces these with designed ones.

@Composable
private fun Frame(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }
}

@Composable
fun PromptOverlay(appLabel: String, options: List<Tier>, onPick: (Tier) -> Unit) = Frame {
    Text(stringResource(R.string.overlay_prompt_title, appLabel), style = MaterialTheme.typography.headlineSmall)
    options.forEach { t ->
        Button(onClick = { onPick(t) }, Modifier.fillMaxWidth()) {
            Text(
                if (t.lockMin % 60 == 0) stringResource(R.string.overlay_tier_hours, t.sessionMin, t.lockMin / 60)
                else stringResource(R.string.overlay_tier_minutes, t.sessionMin, t.lockMin)
            )
        }
    }
}

/** [lockedUntil] is a preformatted time, or null for the daily cap. */
@Composable
fun BlockOverlay(appLabel: String, lockedUntil: String?, onHome: () -> Unit) = Frame {
    Text(appLabel, style = MaterialTheme.typography.titleMedium)
    Text(
        if (lockedUntil != null) stringResource(R.string.overlay_locked_until, lockedUntil)
        else stringResource(R.string.overlay_daily_cap),
        style = MaterialTheme.typography.headlineSmall,
    )
    Button(onClick = onHome) { Text(stringResource(R.string.overlay_go_home)) }
}

@Composable
fun TimesUpOverlay(onDone: () -> Unit) = Frame {
    Text(stringResource(R.string.overlay_times_up), style = MaterialTheme.typography.headlineSmall)
    LaunchedEffect(Unit) { delay(1_500); onDone() }
}
