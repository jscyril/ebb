package com.jscyril.ebb.ui.app

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.engine.AppState
import com.jscyril.ebb.guard.Admin
import com.jscyril.ebb.service.ServiceState
import com.jscyril.ebb.ui.theme.EbbMotion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import com.jscyril.ebb.data.DayTotal
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.luminance

class HomeVm(app: Application) : AndroidViewModel(app) {
    private val repo = Repo.get(app)
    private val sub = SharingStarted.WhileSubscribed(5_000)
    val rules = repo.rulesFlow.stateIn(viewModelScope, sub, null)
    val states = repo.statesFlow.stateIn(viewModelScope, sub, emptyMap())
    val pending = repo.pendingFlow.stateIn(viewModelScope, sub, null)
    val protectionOn = repo.protectionEnabledFlow.stateIn(viewModelScope, sub, true)
    val now = trustedTicker(app).stateIn(viewModelScope, sub, null)
    val zone = flow<ZoneId?> { emit(repo.zone()) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** Yesterday and today, oldest first. */
    val days = repo.dailyTotals(2).stateIn(viewModelScope, sub, emptyList())

}

@Composable
fun Home(onOpenRules: () -> Unit, onOpenSettings: () -> Unit, contentPadding: PaddingValues, vm: HomeVm = viewModel()) {
    val ctx = LocalContext.current
    val rules by vm.rules.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val protectionOn by vm.protectionOn.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val zone by vm.zone.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    var serviceOn by remember { mutableStateOf(true) }
    var adminOn by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        serviceOn = ServiceState.isEnabled(ctx); adminOn = Admin.isActive(ctx)
        onPauseOrDispose {}
    }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { adminOn = Admin.isActive(ctx) }

    val apps = remember(rules) { rules?.apps.orEmpty().values.sortedBy { Apps.label(ctx, it.pkg).lowercase() } }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(stringResource(R.string.app_name), Modifier.padding(vertical = 8.dp).semantics { heading() }, style = MaterialTheme.typography.displaySmall)
        }
        // Most urgent problem first; each has a direct fix.
        val problem = when {
            !protectionOn -> Triple(R.string.banner_off_user, R.string.banner_fix_settings) { onOpenSettings() }
            !serviceOn -> Triple(R.string.banner_off_service, R.string.banner_fix_service) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            !adminOn -> Triple(R.string.banner_off_admin, R.string.banner_fix_admin) { adminLauncher.launch(Admin.enableIntent(ctx)) }
            else -> null
        }
        if (problem != null) item(key = "problem") {
            Banner(stringResource(R.string.banner_off_title), stringResource(problem.first), stringResource(problem.second), problem.third, error = true, Modifier.animateItem())
        }
        val p = pending
        if (p != null) item(key = "pending") {
            Banner(
                stringResource(R.string.pending_title), stringResource(R.string.pending_body, dayAndTime(p.effectiveAt)),
                stringResource(R.string.pending_view), onOpenRules, error = false, Modifier.animateItem(),
            )
        }
        item(key = "hero") {
            val capSec = apps.sumOf { it.dailyCapMin.coerceAtLeast(0) * 60L }
            Hero(days.lastOrNull(), days.getOrNull(days.size - 2)?.takeIf { days.size == 2 }, capSec, Modifier.animateItem())
        }
        item(key = "appsTitle") { SectionTitle(stringResource(R.string.home_watched), Modifier.semantics { heading() }) }
        if (rules != null && apps.isEmpty()) item(key = "empty") {
            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Body(stringResource(R.string.home_empty))
                TextButton(onOpenRules, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.home_empty_action)) }
            }
        }
        val t = now; val z = zone
        items(apps, key = { it.pkg }) { rule ->
            val status = if (t != null && z != null) appStatus(rule, states[rule.pkg] ?: AppState(), t, z) else null
            AppRow(rule.pkg, status, Modifier.animateItem())
        }
    }
}

/**
 * Bento-style summary: one bold card with today's total, the limit and a progress bar, then two small
 * tiles (opens/blocked, time left). Flat cards with big numbers, after the finance-dashboard look.
 */
@Composable
private fun Hero(today: DayTotal?, yesterday: DayTotal?, capSec: Long, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val sec = today?.seconds ?: 0L
    val fraction = if (capSec > 0) (sec.toFloat() / capSec).coerceIn(0f, 1f) else 0f
    val bar by animateFloatAsState(fraction, EbbMotion.Slow(), label = "bar")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // A deep, saturated card in both modes: primary in light, the dark primary container in dark.
        val dark = cs.surface.luminance() < 0.5f
        val card = if (dark) cs.primaryContainer else cs.primary
        val ink = if (dark) cs.onPrimaryContainer else cs.onPrimary
        val accent = if (dark) cs.primary else cs.inversePrimary
        Surface(Modifier.fillMaxWidth(), color = card, contentColor = ink, shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_today), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (yesterday != null) TrendPill(sec - yesterday.seconds, ink)
                }
                AnimatedContent(usageText(sec), transitionSpec = { fadeIn(EbbMotion.Standard()) togetherWith fadeOut(EbbMotion.Fast()) }, label = "total") {
                    Text(it, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.displayMedium)
                }
                Text(
                    if (capSec > 0) stringResource(R.string.home_of_limit, usageText(capSec)) else stringResource(R.string.home_today_sub),
                    style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.75f),
                )
                if (capSec > 0) {
                    Text(
                        stringResource(R.string.home_used_pct, (fraction * 100).toInt()), Modifier.padding(top = 20.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(ink.copy(alpha = 0.18f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(bar).clip(CircleShape).background(accent))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniTile(
                stringResource(R.string.home_opens), (today?.opens ?: 0).toString(),
                pluralStringResource(R.plurals.insights_blocked_caption, today?.blocked ?: 0, today?.blocked ?: 0),
                cs.surfaceContainerHigh, Modifier.weight(1f),
            )
            MiniTile(
                stringResource(R.string.home_left), if (capSec > 0) usageText((capSec - sec).coerceAtLeast(0)) else "—",
                stringResource(R.string.home_left_caption), cs.tertiaryContainer, Modifier.weight(1f),
            )
        }
    }
}

/** "+12m" / "−8m" vs yesterday; less is better, so less gets the calm colour. */
@Composable
private fun TrendPill(deltaSec: Long, ink: Color) {
    val min = deltaSec / 60
    val text = when {
        min > 0 -> stringResource(R.string.home_trend_up, min)
        min < 0 -> stringResource(R.string.home_trend_down, -min)
        else -> stringResource(R.string.home_trend_same)
    }
    Surface(color = ink.copy(alpha = 0.14f), contentColor = ink, shape = CircleShape) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MiniTile(label: String, value: String, caption: String, color: Color, modifier: Modifier) {
    Surface(modifier.fillMaxHeight(), color = color, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp)) {
            val ink = LocalContentColor.current
            Text(label, style = MaterialTheme.typography.labelLarge, color = ink.copy(alpha = 0.75f))
            Text(value, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
            Text(caption, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun AppRow(pkg: String, status: AppStatus?, modifier: Modifier) {
    val name = rememberLabel(pkg)
    ElevatedCard(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconFor(pkg, name, 44.dp)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimatedContent(status, label = "status") { s -> if (s != null) StatusChip(s) }
            }
        }
    }
}

@Composable
fun StatusChip(s: AppStatus) {
    val cs = MaterialTheme.colorScheme
    val (text, bg, fg) = when (s) {
        is AppStatus.Open -> Triple(stringResource(R.string.status_open, s.usedMin, s.capMin), cs.secondaryContainer, cs.onSecondaryContainer)
        is AppStatus.InSession -> Triple(stringResource(R.string.status_session, timeOfDay(s.until)), cs.primaryContainer, cs.onPrimaryContainer)
        is AppStatus.Locked -> Triple(stringResource(R.string.status_locked, timeOfDay(s.until)), cs.tertiaryContainer, cs.onTertiaryContainer)
        AppStatus.CapReached -> Triple(stringResource(R.string.status_cap), cs.errorContainer, cs.onErrorContainer)
    }
    Surface(Modifier.padding(top = 6.dp), color = bg, contentColor = fg, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Banner(title: String, body: String, action: String, onAction: () -> Unit, error: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val bg = if (error) cs.errorContainer else cs.secondaryContainer
    val fg = if (error) cs.onErrorContainer else cs.onSecondaryContainer
    Surface(modifier.fillMaxWidth(), color = bg, contentColor = fg, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (error) R.drawable.ic_warning else R.drawable.ic_schedule), null)
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, Modifier.padding(top = 4.dp, end = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (error) Button(onAction, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(cs.error, cs.onError)) { Text(action) }
                else TextButton(onAction, Modifier.heightIn(min = 48.dp)) { Text(action, color = fg) }
            }
        }
    }
}

