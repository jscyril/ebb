package com.jscyril.ebb.ui.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import com.jscyril.ebb.R
import com.jscyril.ebb.engine.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minutes as "45 min", "2 h" or "2 h 30 min". */
@Composable
fun minutesText(min: Int): String = when {
    min < 60 -> stringResource(R.string.dur_min, min)
    min % 60 == 0 -> stringResource(R.string.dur_hours, min / 60)
    else -> stringResource(R.string.dur_hours_min, min / 60, min % 60)
}

/** Edits one rule's session tiers and daily cap; the same editor in onboarding and on the Rules tab. */
@Composable
fun RuleEditor(tiers: List<Tier>, capMin: Int, onChange: (List<Tier>, Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiers.forEachIndexed { i, t ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.tier_n, i + 1), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton({ onChange(tiers.filterIndexed { j, _ -> j != i }, capMin) }, Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.cd_remove_tier, i + 1))
                        }
                    }
                    Stepper(
                        stringResource(R.string.tier_session), t.sessionMin,
                        { v -> onChange(tiers.mapIndexed { j, x -> if (j == i) x.copy(sessionMin = v) else x }, capMin) },
                        1, SESSION_MAX, 5, { minutesText(it) },
                    )
                    Stepper(
                        stringResource(R.string.tier_lock), t.lockMin,
                        { v -> onChange(tiers.mapIndexed { j, x -> if (j == i) x.copy(lockMin = v) else x }, capMin) },
                        0, LOCK_MAX, 15, { minutesText(it) },
                    )
                }
            }
        }
        OutlinedButton(
            {
                val last = tiers.maxByOrNull { it.sessionMin }
                val next = Tier(minOf(SESSION_MAX, (last?.sessionMin ?: 0) + 15), minOf(LOCK_MAX, last?.lockMin?.times(2) ?: 60))
                onChange(tiers + next, capMin)
            },
            Modifier.heightIn(min = 48.dp), enabled = tiers.size < 8,
        ) {
            Icon(painterResource(R.drawable.ic_add), null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.tier_add))
        }
        validateTiers(tiers).forEach { key ->
            Text(
                stringResource(
                    when (key) {
                        "duplicate_session" -> R.string.err_duplicate_session
                        "session_range" -> R.string.err_session_range
                        "lock_range" -> R.string.err_lock_range
                        else -> R.string.err_empty
                    },
                ),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Stepper(stringResource(R.string.rule_cap), capMin, { onChange(tiers, it) }, 0, LOCK_MAX, 5, { minutesText(it) })
    }
}

/**
 * Searchable list of launchable apps with checkboxes. Icons load lazily per row, off the main thread.
 * [selected] is the current choice; [onToggle] flips one package.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppPicker(selected: Set<String>, onToggle: (String) -> Unit, modifier: Modifier = Modifier, exclude: Set<String> = emptySet()) {
    val ctx = LocalContext.current
    val apps by produceState<List<LaunchableApp>?>(null) { value = withContext(Dispatchers.IO) { Apps.launchable(ctx) } }
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.picker_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        val list = apps
        if (list == null) {
            LoadingIndicator(Modifier.padding(32.dp).align(androidx.compose.ui.Alignment.CenterHorizontally))
            return@Column
        }
        val shown = remember(list, query, exclude) {
            list.filter { it.pkg !in exclude && (query.isBlank() || it.label.contains(query.trim(), ignoreCase = true)) }
        }
        Text(
            pluralStringResource(R.plurals.picker_selected, selected.size, selected.size),
            Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
        )
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.pkg }) { app ->
                val on = app.pkg in selected
                ListItem(
                    headlineContent = { Text(app.label) },
                    leadingContent = { AppIconFor(app.pkg, app.label) },
                    trailingContent = { Checkbox(on, null) },
                    modifier = Modifier.animateItem().toggleable(on, role = Role.Checkbox) { onToggle(app.pkg) },
                )
            }
        }
    }
}
