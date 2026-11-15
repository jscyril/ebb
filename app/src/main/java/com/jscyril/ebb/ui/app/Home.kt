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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.toShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.Canvas

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
 * Bento summary. A deep hero card lit by two soft colour glows, with a big split number ("1h 12m"),
 * a trend pill and a segmented limit bar; then two tiles: opens/blocked and a time-left ring.
 */
@Composable
private fun Hero(today: DayTotal?, yesterday: DayTotal?, capSec: Long, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val base = if (dark) cs.primaryContainer else cs.primary
    val ink = if (dark) cs.onPrimaryContainer else cs.onPrimary
    val glowA = if (dark) cs.tertiary else cs.tertiaryContainer
    val glowB = if (dark) cs.primary else cs.inversePrimary
    val sec = today?.seconds ?: 0L
    val fraction = if (capSec > 0) (sec.toFloat() / capSec).coerceIn(0f, 1f) else 0f
    val fill by animateFloatAsState(fraction, EbbMotion.Slow(), label = "fill")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.fillMaxWidth(), color = base, contentColor = ink, shape = MaterialTheme.shapes.extraLarge) {
            Box(
                Modifier.drawBehind {
                    drawRect(Brush.radialGradient(listOf(glowA.copy(alpha = 0.55f), Color.Transparent), Offset(size.width * 0.95f, 0f), size.width * 0.75f))
                    drawRect(Brush.radialGradient(listOf(glowB.copy(alpha = 0.35f), Color.Transparent), Offset(0f, size.height), size.width * 0.7f))
                },
            ) {
                // Decorative expressive shape peeking in from the corner.
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 36.dp, y = (-36).dp).size(150.dp).rotate(18f)
                        .clip(MaterialShapes.Cookie9Sided.toShape()).background(ink.copy(alpha = 0.08f)),
                )
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(ink.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_schedule), null, Modifier.size(20.dp))
                        }
                        Spacer(Modifier.size(10.dp))
                        Text(stringResource(R.string.home_today), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (yesterday != null) TrendPill(sec - yesterday.seconds, ink)
                    }
                    AnimatedContent(sec / 60, transitionSpec = { fadeIn(EbbMotion.Standard()) togetherWith fadeOut(EbbMotion.Fast()) }, label = "total") { m ->
                        BigDuration(m, Modifier.padding(top = 20.dp))
                    }
                    Text(
                        if (capSec > 0) stringResource(R.string.home_of_limit, usageText(capSec)) else stringResource(R.string.home_today_sub),
                        style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.75f),
                    )
                    if (capSec > 0) {
                        SegmentBar(fill, ink.copy(alpha = 0.16f), Brush.horizontalGradient(listOf(glowB, glowA)), Modifier.padding(top = 20.dp))
                        Text(
                            stringResource(R.string.home_used_pct, (fraction * 100).toInt()), Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.labelLarge, color = ink.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val blocked = today?.blocked ?: 0
            Tile(cs.secondaryContainer, cs.secondary, Modifier.weight(1f)) {
                TileIcon(R.drawable.ic_lock, cs.secondary, cs.onSecondary)
                Text(stringResource(R.string.home_opens), Modifier.padding(top = 14.dp), style = MaterialTheme.typography.labelLarge)
                Text((today?.opens ?: 0).toString(), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold))
                Text(pluralStringResource(R.plurals.insights_blocked_caption, blocked, blocked), style = MaterialTheme.typography.bodySmall)
            }
            Tile(cs.tertiaryContainer, cs.tertiary, Modifier.weight(1f)) {
                val left = (capSec - sec).coerceAtLeast(0)
                Box(contentAlignment = Alignment.Center) {
                    MiniRing(if (capSec > 0) left.toFloat() / capSec else 0f, cs.tertiary, cs.onTertiaryContainer.copy(alpha = 0.15f), Modifier.size(44.dp))
                }
                Text(stringResource(R.string.home_left), Modifier.padding(top = 14.dp), style = MaterialTheme.typography.labelLarge)
                Text(if (capSec > 0) usageText(left) else "—", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                Text(stringResource(R.string.home_left_caption), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** "1h 12m" with big digits and small units. */
@Composable
private fun BigDuration(minutes: Long, modifier: Modifier) {
    val big = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.SemiBold)
    val small = MaterialTheme.typography.headlineSmall
    val h = stringResource(R.string.unit_h); val m = stringResource(R.string.unit_m)
    val text = buildAnnotatedString {
        if (minutes >= 60) { withStyle(big.toSpanStyle()) { append((minutes / 60).toString()) }; withStyle(small.toSpanStyle()) { append("$h ") } }
        withStyle(big.toSpanStyle()) { append((minutes % 60).toString()) }; withStyle(small.toSpanStyle()) { append(m) }
    }
    Text(text, modifier)
}

/** 20 rounded segments filling left to right, like a level meter. */
@Composable
private fun SegmentBar(fraction: Float, track: Color, fill: Brush, modifier: Modifier) {
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val n = 20; val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val lit = fraction * n
        for (i in 0 until n) {
            val x = i * (w + gap)
            val r = CornerRadius(w / 2, w / 2)
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), r)
            val f = (lit - i).coerceIn(0f, 1f)
            if (f > 0f) drawRoundRect(fill, Offset(x, 0f), Size(w, size.height * 1f), r, alpha = 0.35f + 0.65f * f)
        }
    }
}

@Composable
private fun MiniRing(fraction: Float, color: Color, track: Color, modifier: Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), EbbMotion.Slow(), label = "ring")
    Canvas(modifier) {
        val w = 5.dp.toPx()
        val tl = Offset(w / 2, w / 2); val sz = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
        drawArc(color, -90f, 360f * f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
private fun TileIcon(icon: Int, bg: Color, fg: Color) =
    Box(Modifier.size(44.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(bg), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = fg)
    }

/** Small bento tile: container colour with a soft glow of [glow] from the top-left. */
@Composable
private fun Tile(color: Color, glow: Color, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxHeight(), color = color, shape = MaterialTheme.shapes.extraLarge) {
        Column(
            Modifier.drawBehind { drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.22f), Color.Transparent), Offset.Zero, size.maxDimension)) }
                .padding(20.dp),
            content = content,
        )
    }
}

/** "+12m" / "−8m" vs yesterday. */
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

