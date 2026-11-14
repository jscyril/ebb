package com.jscyril.ebb.ui.app

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.LruCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import com.jscyril.ebb.guard.Pending
import com.jscyril.ebb.guard.Rules
import com.jscyril.ebb.service.TrustedNow
import com.jscyril.ebb.ui.overlay.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Date

/** Trusted now, re-emitted every [periodMs]; drives countdowns and statuses on the app screens. */
fun trustedTicker(ctx: Context, periodMs: Long = 30_000): Flow<Long> = flow {
    while (true) { emit(TrustedNow.peek(ctx)); delay(periodMs) }
}

/** Applies a matured pending change, if any. The service does the same on each event. */
suspend fun maturePending(ctx: Context) {
    val repo = Repo.get(ctx)
    val p = repo.pending() ?: return
    val (rules, left) = ChangeQueue.mature(repo.rules(), p, TrustedNow.peek(ctx))
    if (left == null) { repo.saveRules(rules); repo.savePending(null) }
}

/**
 * Submits [proposed] through the 24 h cooldown: tighter saves now, looser waits. Returns the new pending change, or null if saved.
 * A tightening save also drops an older pending change, which was computed from the old rules and would undo this one.
 */
suspend fun submitRules(ctx: Context, proposed: Rules): Pending? {
    maturePending(ctx)
    val repo = Repo.get(ctx)
    val (rules, pending) = ChangeQueue.submit(repo.rules(), proposed, TrustedNow.peek(ctx))
    if (pending == null) repo.saveRules(rules)
    repo.savePending(pending)
    return pending
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
@Composable
fun dateText(at: Long): String {
    val ctx = LocalContext.current
    return remember(at) {
        DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_DATE)
    }
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
) {
    val shown = format(value)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        FilledTonalIconButton({ onChange(stepDown(value, step, min)) }, Modifier.size(48.dp), enabled = enabled && value > min) {
            Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.cd_decrease, label))
        }
        Text(
            shown, Modifier.widthIn(min = 72.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.Center,
        )
        FilledTonalIconButton({ onChange(stepUp(value, step, max)) }, Modifier.size(48.dp), enabled = enabled && upEnabled && value < max) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.cd_increase, label))
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
