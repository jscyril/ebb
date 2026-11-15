package com.jscyril.ebb.ui.app

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.LruCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.guard.ChangeQueue
import com.jscyril.ebb.guard.MatureAction
import com.jscyril.ebb.guard.Pledge
import com.jscyril.ebb.guard.Rules
import com.jscyril.ebb.service.TrustedNow
import com.jscyril.ebb.ui.overlay.AppIcon
import com.jscyril.ebb.widget.WidgetRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Date
import kotlinx.coroutines.flow.first

/** Trusted now, re-emitted every [periodMs]; drives countdowns and statuses on the app screens. */
fun trustedTicker(ctx: Context, periodMs: Long = 30_000): Flow<Long> = flow {
    while (true) { emit(TrustedNow.peek(ctx)); delay(periodMs) }
}

/** Applies a matured pending change, if any. The service does the same on each event. */
suspend fun maturePending(ctx: Context): Rules {
    val repo = Repo.get(ctx)
    // Test mode (debug only): any waiting change lands right away.
    val now = if (repo.testModeFlow.first()) Long.MAX_VALUE else TrustedNow.peek(ctx)
    val (rules, action) = repo.maturePending(now)
    if (action == MatureAction.Apply) rulesChanged(ctx)
    return rules
}

/** Widgets list the watched apps and their caps: redraw them and re-arm their alarm after a rules write. */
fun rulesChanged(ctx: Context) { WidgetRefresher.requestUpdate(ctx); WidgetRefresher.scheduleNext(ctx) }

sealed interface SubmitResult {
    data object Saved : SubmitResult
    data class Queued(val effectiveAt: Long) : SubmitResult
    data class RefusedPledge(val pledgeEndsAt: Long) : SubmitResult
}

/**
 * Submits [proposed] through the 24 h cooldown: tighter saves now, looser waits, and during a pledge looser is refused.
 * A tightening save also drops an older pending change, which was computed from the old rules and would undo this one.
 */
suspend fun submitRules(ctx: Context, proposed: Rules): SubmitResult {
    val repo = Repo.get(ctx)
    while (true) {
        val current = maturePending(ctx)
        val now = TrustedNow.peek(ctx)
        val pledge = repo.pledge()
        val (queued, waiting) = ChangeQueue.submit(current, proposed, now)
        val test = repo.testModeFlow.first() // debug only: no 24 h wait, no pledge refusal
        val rules = if (test) waiting?.newRules ?: queued else queued
        val pending = if (test) null else waiting
        if (!allowSubmit(pending != null, Pledge.isActive(pledge, now))) return SubmitResult.RefusedPledge(pledge!!.endsAt)
        // Compare-and-set: if the service changed the rules meanwhile, decide again against the new ones.
        if (!repo.saveIfRulesUnchanged(current, rules.takeIf { pending == null }, pending)) continue
        if (pending == null) rulesChanged(ctx)
        return if (pending == null) SubmitResult.Saved else SubmitResult.Queued(pending.effectiveAt)
    }
}

/** Snackbar text for a [SubmitResult]. */
fun submitMessage(ctx: Context, r: SubmitResult): String = when (r) {
    SubmitResult.Saved -> ctx.getString(R.string.saved)
    is SubmitResult.Queued -> ctx.getString(R.string.saved_pending, formatDayTime(ctx, r.effectiveAt))
    is SubmitResult.RefusedPledge -> ctx.getString(R.string.pledge_refuses_loosening, formatDate(ctx, r.pledgeEndsAt))
}

/** Whether saving [draft] over [current] would be queued for 24 h. */
fun isLooser(current: Rules, draft: Rules) = ChangeQueue.submit(current, draft, 0).second != null

data class LaunchableApp(val pkg: String, val label: String)

object Apps {
    private val icons = LruCache<String, ImageBitmap>(96)
    private const val ICON_PX = 96

    /** Launchable apps except Ebb, sorted by label. Slow: call off the main thread. */
    @Suppress("DEPRECATION") // the flags overload is API 33+
    fun launchable(ctx: Context): List<LaunchableApp> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .map { LaunchableApp(it.first, it.second) }
            .sortedBy { it.label.lowercase() }
    }

    fun label(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    suspend fun icon(ctx: Context, pkg: String): ImageBitmap? = icons[pkg] ?: withContext(Dispatchers.Default) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
    }?.also { icons.put(pkg, it) }
}

/** An app's icon, loaded off the main thread and cached. Decorative: its name is always in text nearby. */
@Composable
fun AppIconFor(pkg: String, name: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) { value = Apps.icon(ctx, pkg) }
    AppIcon(name, icon, size)
}

@Composable
fun rememberLabel(pkg: String): String {
    val ctx = LocalContext.current
    return remember(pkg) { Apps.label(ctx, pkg) }
}

@Composable
fun timeOfDay(at: Long): String {
    val ctx = LocalContext.current
    return remember(at) { DateFormat.getTimeFormat(ctx).format(Date(at)) }
}

/** "Tue 4:30 PM". */
fun formatDayTime(ctx: Context, at: Long): String =
    DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_TIME)

@Composable
fun dayAndTime(at: Long): String {
    val ctx = LocalContext.current
    return remember(at) { formatDayTime(ctx, at) }
}

/** "Tue, Dec 15". */
fun formatDate(ctx: Context, at: Long): String =
    DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_DATE)

@Composable
fun dateText(at: Long): String {
    val ctx = LocalContext.current
    return remember(at) { formatDate(ctx, at) }
}

/** "3 d 4 h", "23 h 12 min" or "12 min"; rounds up so a pending wait never reads 0 min. */
@Composable
fun durationText(ms: Long): String {
    val totalMin = ((ms.coerceAtLeast(0) + 59_999) / 60_000).toInt()
    val d = totalMin / 1440
    val h = totalMin / 60 % 24
    val m = totalMin % 60
    return when {
        d > 0 -> stringResource(R.string.dur_days_hours, d, h)
        totalMin >= 60 -> stringResource(R.string.dur_hours_min, totalMin / 60, m)
        else -> stringResource(R.string.dur_min, m)
    }
}

/** "1 h 12 min" / "12 min" from seconds of usage. */
@Composable
fun usageText(seconds: Long): String {
    val min = (seconds / 60).toInt()
    return if (min >= 60) stringResource(R.string.dur_hours_min, min / 60, min % 60) else stringResource(R.string.dur_min, min)
}

/** A − value + row with 48 dp targets; [format] renders the value, [label] names it for TalkBack. */
@Composable
fun Stepper(
    label: String, value: Int, onChange: (Int) -> Unit, min: Int, max: Int, step: Int,
    format: @Composable (Int) -> String, modifier: Modifier = Modifier, enabled: Boolean = true, upEnabled: Boolean = true,
    steps: List<Int> = emptyList(),
) {
    val shown = format(value)
    // With [steps], bubbles below pick how far − and + move (plain add/subtract); otherwise they snap by [step].
    var size by rememberSaveable { mutableIntStateOf(steps.firstOrNull() ?: step) }
    val down = if (steps.isEmpty()) stepDown(value, step, min) else maxOf(min, value - size)
    val up = if (steps.isEmpty()) stepUp(value, step, max) else minOf(max, value + size)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            FilledTonalIconButton({ onChange(down) }, Modifier.size(48.dp), enabled = enabled && value > min) {
                Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.cd_decrease, label))
            }
            Text(
                shown, Modifier.widthIn(min = 72.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.Center,
            )
            FilledTonalIconButton({ onChange(up) }, Modifier.size(48.dp), enabled = enabled && upEnabled && value < max) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.cd_increase, label))
            }
        }
        if (steps.isNotEmpty()) Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (q in steps) {
                val cd = stringResource(R.string.cd_step_size, label, q)
                FilterChip(
                    q == size, { size = q }, { Text(stringResource(R.string.step_size, q)) },
                    Modifier.semantics { contentDescription = cd }, enabled = enabled, shape = CircleShape,
                )
            }
        }
    }
}

/** Section title used across the tabs. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
fun Body(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
