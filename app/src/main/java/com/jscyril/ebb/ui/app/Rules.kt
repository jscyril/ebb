package com.jscyril.ebb.ui.app

import com.jscyril.ebb.service.SelfToggle
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.mutableIntStateOf
import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.engine.AppRule
import com.jscyril.ebb.guard.Rules
import com.jscyril.ebb.ui.theme.BigButton
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.jscyril.ebb.engine.Sites
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.InputChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi

class RulesVm(private val app: Application) : AndroidViewModel(app) {
    private val repo = Repo.get(app)
    private val sub = SharingStarted.WhileSubscribed(5_000)
    val rules = repo.rulesFlow.stateIn(viewModelScope, sub, null)
    val pending = repo.pendingFlow.stateIn(viewModelScope, sub, null)
    val now = trustedTicker(app).stateIn(viewModelScope, sub, null)
    val pledge = repo.pledgeFlow.stateIn(viewModelScope, sub, null)

    /** Sends [proposed] through the cooldown and reports the outcome as a snackbar message. */
    fun submit(proposed: Rules, onMessage: (String) -> Unit) = viewModelScope.launch {
        onMessage(submitMessage(app, submitRules(app, proposed)))
    }

    /** Keeping the current rules is never looser, so cancelling applies at once. */
    fun cancelPending() = viewModelScope.launch { repo.savePending(null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesTab(contentPadding: PaddingValues, onMessage: (String) -> Unit, vm: RulesVm = viewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val pledge by vm.pledge.collectAsStateWithLifecycle()
    val pledgeUntil = pledge?.endsAt?.takeIf { end -> now?.let { it < end } ?: true } // active pledge: only tightening
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val current = rules ?: return
    val sorted = remember(current.apps) { current.apps.values.sortedBy { Apps.label(ctx, it.pkg).lowercase() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(stringResource(R.string.tab_rules), Modifier.padding(vertical = 8.dp).semantics { heading() }, style = MaterialTheme.typography.displaySmall)
        }
        item(key = "intro") {
            Body(if (pledgeUntil != null) stringResource(R.string.rules_pledge_intro, dateText(pledgeUntil)) else stringResource(R.string.rules_intro))
        }
        val p = pending
        if (p != null) item(key = "pending") {
            Surface(Modifier.animateItem().fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_schedule), null)
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.pending_title), style = MaterialTheme.typography.titleMedium)
                    }
                    val left = now?.let { durationText(p.effectiveAt - it) }
                    Text(
                        if (left != null) stringResource(R.string.pending_countdown, dayAndTime(p.effectiveAt), left)
                        else stringResource(R.string.pending_body, dayAndTime(p.effectiveAt)),
                        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                    Body(pendingSummary(current, p.newRules))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(vm::cancelPending, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pending_cancel)) }
                    }
                }
            }
        }
        if (current.apps.size > 1) item(key = "shared") {
            SharedLimitCard(current, pending != null, pledgeUntil != null, { vm.submit(it, onMessage) }, Modifier.animateItem())
        }
        items(sorted, key = { it.pkg }) { rule ->
            AppRuleCard(
                rule, expanded == rule.pkg, { expanded = if (expanded == rule.pkg) null else rule.pkg },
                current, pending != null, pledgeUntil, onSave = { vm.submit(it, onMessage); expanded = null }, Modifier.animateItem(),
            )
        }
        item(key = "add") {
            FilledTonalButton({ adding = true }, Modifier.animateItem().fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(painterResource(R.drawable.ic_add), null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.rules_add_apps))
            }
        }
    }

    if (adding) {
        var picked by remember { mutableStateOf(emptySet<String>()) }
        ModalBottomSheet({ adding = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
                AppPicker(picked, { picked = if (it in picked) picked - it else picked + it }, Modifier.weight(1f), exclude = current.apps.keys)
                BigButton(
                    pluralStringResource(R.plurals.rules_add_n, picked.size, picked.size),
                    {
                        // A new app's rule is a template copy; adding a watched app is tightening, so it saves at once.
                        val template = current.apps.values.firstOrNull() ?: AppRule()
                        vm.submit(current.copy(apps = current.apps + picked.associateWith { template.copy(pkg = it, name = Apps.label(ctx, it), domains = null) }), onMessage)
                        adding = false
                    },
                    Modifier.fillMaxWidth().padding(vertical = 12.dp), enabled = picked.isNotEmpty(),
                )
            }
        }
    }
}

/** Per-app grey-out for a session's last 30 s; it needs the one-time adb grant, so it says so when that is missing. */
@Composable
private fun GrayscaleRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val canWrite = remember { SelfToggle.canWrite(ctx) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, enabled = canWrite || on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.grey_title), style = MaterialTheme.typography.bodyLarge)
            Body(stringResource(if (canWrite) R.string.grey_body else R.string.grey_needs_setup))
        }
        Switch(on, null, enabled = canWrite || on)
    }
}

/** One daily budget for all watched apps. Turning it on or lowering it is tightening; off or higher waits 24 h. */
@Composable
private fun SharedLimitCard(current: Rules, hasPending: Boolean, pledge: Boolean, onSubmit: (Rules) -> Unit, modifier: Modifier = Modifier) {
    val on = current.sharedCapMin != null
    var draft by rememberSaveable(current.sharedCapMin) { mutableIntStateOf(current.sharedCapMin ?: minOf(current.totalCapMin, 60)) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(on, enabled = !hasPending && !(on && pledge), role = Role.Switch) { onSubmit(current.copy(sharedCapMin = if (it) draft else null)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.shared_title), style = MaterialTheme.typography.titleMedium)
                    Body(stringResource(R.string.shared_body))
                }
                Switch(on, null, enabled = !hasPending && !(on && pledge))
            }
            if (on) {
                Stepper(stringResource(R.string.shared_label), draft, { draft = it }, 1, 24 * 60, 1, { minutesText(it) }, steps = listOf(5, 15, 30, 60),
                    enabled = !hasPending, upEnabled = !pledge || draft < current.sharedCapMin!!)
                if (draft != current.sharedCapMin) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val looser = draft > current.sharedCapMin!!
                    TextButton({ onSubmit(current.copy(sharedCapMin = draft)) }, Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (looser) R.string.rules_save_wait else R.string.rules_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRuleCard(
    rule: AppRule, open: Boolean, onToggle: () -> Unit, current: Rules, hasPending: Boolean, pledgeUntil: Long?,
    onSave: (Rules) -> Unit, modifier: Modifier,
) {
    val name = rememberLabel(rule.pkg)
    var draft by remember(rule) { mutableStateOf(rule) }
    val stateText = stringResource(if (open) R.string.cd_expanded else R.string.cd_collapsed)
    ElevatedCard(onToggle, modifier.fillMaxWidth().semantics { stateDescription = stateText }, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconFor(rule.pkg, name, 44.dp)
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Body(
                        pluralStringResource(R.plurals.rule_summary, rule.tiers.size, rule.tiers.size, minutesText(rule.dailyCapMin)),
                    )
                }
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RuleEditor(draft.tiers, draft.dailyCapMin, { t, c -> draft = draft.copy(tiers = t, dailyCapMin = c) })
                    SitesEditor(Sites.of(draft), name) { draft = draft.copy(domains = it) }
                    GrayscaleRow(draft.grayscale) { draft = draft.copy(grayscale = it) }
                    val proposed = current.copy(apps = current.apps + (rule.pkg to draft.copy(tiers = draft.tiers.sortedBy { it.sessionMin })))
                    val changed = draft != rule
                    val looser = changed && isLooser(current, proposed)
                    val refused = looser && !allowSubmit(true, pledgeUntil != null)
                    AnimatedVisibility(looser) {
                        Text(
                            when {
                                refused -> stringResource(R.string.pledge_refuses_loosening, dateText(pledgeUntil!!))
                                hasPending -> stringResource(R.string.rules_looser_replaces)
                                else -> stringResource(R.string.rules_looser)
                            },
                            color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    AnimatedVisibility(changed && !looser && hasPending) {
                        Body(stringResource(R.string.rules_tighter_drops_pending))
                    }
                    BigButton(
                        stringResource(if (looser) R.string.rules_save_wait else R.string.rules_save),
                        { onSave(proposed) }, Modifier.fillMaxWidth(), enabled = changed && !refused && validateTiers(draft.tiers).isEmpty(),
                    )
                    // Removing an app only loosens, so it is hidden during a pledge (the intro says why).
                    if (pledgeUntil == null) {
                        OutlinedButton({ onSave(current.copy(apps = current.apps - rule.pkg)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(painterResource(R.drawable.ic_delete), null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.rules_remove_app, name))
                        }
                        Body(stringResource(R.string.rules_remove_note))
                    }
                }
            }
        }
    }
}

/** One line saying what the pending change does, e.g. "Removes Instagram" or "Changes YouTube". */
@Composable
private fun pendingSummary(cur: Rules, new: Rules): String {
    val ctx = LocalContext.current
    val (removed, changed) = remember(cur, new) {
        (cur.apps.keys - new.apps.keys).map { Apps.label(ctx, it) } to
            cur.apps.keys.intersect(new.apps.keys).filter { cur.apps[it] != new.apps[it] }.map { Apps.label(ctx, it) }
    }
    val parts = buildList {
        if (removed.isNotEmpty()) add(stringResource(R.string.pending_removes, removed.joinToString()))
        if (changed.isNotEmpty()) add(stringResource(R.string.pending_changes, changed.joinToString()))
        if (new.passesPerMonth != cur.passesPerMonth) add(stringResource(R.string.pending_passes, new.passesPerMonth))
        if (new.sharedCapMin != cur.sharedCapMin) add(new.sharedCapMin?.let { stringResource(R.string.pending_shared, minutesText(it)) } ?: stringResource(R.string.pending_shared_off))
    }
    return parts.joinToString(" · ")
}

/** The websites that share this app's limits, so its browser version is gated too. Removing one is a loosening. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SitesEditor(sites: List<String>, appName: String, onChange: (List<String>) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val parsed = Sites.normalize(input)
    val add = { if (parsed != null && parsed !in sites) onChange(sites + parsed); input = "" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sites_title), style = MaterialTheme.typography.titleSmall)
        Body(stringResource(R.string.sites_body, appName))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in sites) {
                InputChip(
                    selected = false, onClick = { onChange(sites - d) }, label = { Text(d) },
                    trailingIcon = { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cd_remove_site, d), Modifier.size(18.dp)) },
                )
            }
            if (sites.isEmpty()) Body(stringResource(R.string.sites_none))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(stringResource(R.string.sites_hint)) },
                isError = input.isNotBlank() && parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            FilledTonalIconButton({ add() }, Modifier.size(48.dp), enabled = parsed != null && parsed !in sites) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.sites_add))
            }
        }
    }
}

