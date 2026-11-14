package com.jscyril.ebb.ui.app

import android.app.Application
import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jscyril.ebb.R
import com.jscyril.ebb.data.AppTotal
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.data.dateOfKey
import com.jscyril.ebb.ui.app.charts.HourHeatmap
import com.jscyril.ebb.ui.app.charts.RingChart
import com.jscyril.ebb.ui.app.charts.WeekBars
import com.jscyril.ebb.ui.overlay.AppIcon
import com.jscyril.ebb.ui.theme.EbbMotion
import com.jscyril.ebb.ui.theme.SpringCard
import com.jscyril.ebb.ui.theme.Tide
import com.jscyril.ebb.ui.theme.rememberReducedMotion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** firstUse sentinels: still loading vs. no usage recorded ever. */
private const val LOADING = -1
private const val NO_DATA = 0
/** shortcut: events are pruned after 90 days, so the streak can't see further back; widen both together if needed. */
private const val STREAK_LOOKBACK_DAYS = 90
private const val MAX_APP_ROWS = 5

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsVm(app: Application) : AndroidViewModel(app) {
    private val repo = Repo.get(app)
    private val sub = SharingStarted.WhileSubscribed(5_000)
    val rules = repo.rulesFlow.stateIn(viewModelScope, sub, null)
    val week = repo.dailyTotals(7).stateIn(viewModelScope, sub, emptyList())
    val hourly = repo.hourly(7).stateIn(viewModelScope, sub, IntArray(24))
    val emergencyDays = repo.emergencyDays(STREAK_LOOKBACK_DAYS).stateIn(viewModelScope, sub, emptySet())
    val firstUse = repo.firstUseDayKey().map { it ?: NO_DATA }.stateIn(viewModelScope, sub, LOADING)

    /** plannedMin of sessions since the start of the 7-day window; follows the window across midnight. */
    val weekSessions = week.mapNotNull { it.firstOrNull()?.dayKey }.distinctUntilChanged()
        .flatMapLatest { k -> repo.sessionsSince(dateOfKey(k).atStartOfDay(repo.zone()).toInstant().toEpochMilli()) }
        .stateIn(viewModelScope, sub, emptyList())

    private val picked = MutableStateFlow<Int?>(null)
    /** The picked day while it is still in the window, else today. */
    val selectedKey = combine(picked, week) { p, w -> p?.takeIf { k -> w.any { it.dayKey == k } } ?: w.lastOrNull()?.dayKey }
        .stateIn(viewModelScope, sub, null)
    val apps = selectedKey.filterNotNull().distinctUntilChanged().flatMapLatest { repo.perApp(it) }
        .stateIn(viewModelScope, sub, emptyList())

    fun select(dayKey: Int) { picked.value = dayKey }
}

/** "1h 12m" / "42m". */
@Composable
private fun shortDuration(seconds: Long): String {
    val (h, m) = hoursMinutes(seconds)
    return if (h > 0) stringResource(R.string.dur_short_hm, h.toInt(), m) else stringResource(R.string.dur_short_m, m)
}

/** "1 hour 5 minutes" for TalkBack. */
@Composable
private fun spokenDuration(seconds: Long): String {
    val (h, m) = hoursMinutes(seconds)
    val mins = pluralStringResource(R.plurals.a11y_minutes, m, m)
    return if (h > 0) pluralStringResource(R.plurals.a11y_hours, h.toInt(), h.toInt()) + " " + mins else mins
}

@Composable
private fun hourName(hour: Int): String {
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(hour, locale) {
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:00" else "h a", locale).format(LocalTime.of(hour, 0))
    }
}

@Composable
fun Dashboard(contentPadding: PaddingValues, vm: InsightsVm = viewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val hourly by vm.hourly.collectAsStateWithLifecycle()
    val emergencyDays by vm.emergencyDays.collectAsStateWithLifecycle()
    val firstUse by vm.firstUse.collectAsStateWithLifecycle()
    val sessions by vm.weekSessions.collectAsStateWithLifecycle()
    val selectedKey by vm.selectedKey.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(stringResource(R.string.tab_insights), Modifier.padding(vertical = 8.dp).semantics { heading() }, style = MaterialTheme.typography.displaySmall)
        }
        val today = week.lastOrNull()
        val r = rules
        if (firstUse == LOADING || today == null || r == null) return@LazyColumn
        if (firstUse == NO_DATA) {
            item(key = "empty") { EmptyState(Modifier.animateItem()) }
            return@LazyColumn
        }
        val caps = r.apps.values.map { it.dailyCapMin.coerceAtLeast(0) }
        item(key = "hero") {
            val capSec = caps.sum() * 60L
            HeroRing(today.seconds, capSec, Modifier.animateItem())
        }
        item(key = "tiles") {
            val saved = remember(sessions, r) { estimatedSavedMin(sessions, r) }
            val streak = remember(today.dayKey, emergencyDays, firstUse) { streak(today.dayKey, emergencyDays, firstUse) }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).animateItem(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(stringResource(R.string.insights_streak), pluralStringResource(R.plurals.insights_days, streak, streak), stringResource(R.string.insights_streak_caption), Modifier.weight(1f))
                StatTile(stringResource(R.string.insights_opens), today.opens.toString(), pluralStringResource(R.plurals.insights_blocked_caption, today.blocked, today.blocked), Modifier.weight(1f))
                StatTile(stringResource(R.string.insights_saved), stringResource(R.string.insights_saved_value, shortDuration(saved * 60L)), stringResource(R.string.insights_estimate), Modifier.weight(1f))
            }
        }
        item(key = "week") {
            // One guide only when every watched app shares the same cap.
            val guide = caps.distinct().singleOrNull()?.takeIf { it > 0 }?.let { it * 60L }
            WeekCard(week.map { it.dayKey }, week.map { it.seconds }, selectedKey ?: today.dayKey, guide, vm::select, Modifier.animateItem())
        }
        item(key = "apps") {
            AppsCard(apps, isToday = selectedKey == today.dayKey, Modifier.animateItem())
        }
        item(key = "hours") { HoursCard(hourly, Modifier.animateItem()) }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    val reduced = rememberReducedMotion()
    SpringCard(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(MaterialTheme.shapes.large)) {
            Tide(Modifier.matchParentSize(), animate = !reduced, level = 0.45f)
            Illustration(MaterialShapes.Clover8Leaf, 88.dp, Modifier.align(Alignment.Center))
        }
        Text(stringResource(R.string.insights_empty_title), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge)
        Body(stringResource(R.string.insights_empty_body), Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun HeroRing(todaySec: Long, capSec: Long, modifier: Modifier) {
    val total = shortDuration(todaySec)
    val cap = shortDuration(capSec)
    SpringCard(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.home_today), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RingChart(
            if (capSec > 0) todaySec.toFloat() / capSec else 0f,
            stringResource(R.string.insights_ring_desc, spokenDuration(todaySec), spokenDuration(capSec)),
            Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp).size(220.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(total, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
                if (capSec > 0) Text(stringResource(R.string.insights_of_limit, cap), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, caption: String, modifier: Modifier) {
    SpringCard(modifier.fillMaxHeight()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        Text(value, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CardTitle(text: String, sub: String? = null) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WeekCard(keys: List<Int>, seconds: List<Long>, selectedKey: Int, guide: Long?, onSelect: (Int) -> Unit, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val days = remember(keys, locale) { keys.map { dateOfKey(it).dayOfWeek } }
    val initials = remember(days) { days.map { it.getDisplayName(TextStyle.NARROW, locale) } }
    val shortNames = remember(days) { days.map { it.getDisplayName(TextStyle.SHORT, locale) } }
    val sel = keys.indexOf(selectedKey).coerceAtLeast(0)
    val items = seconds.mapIndexed { i, s -> stringResource(R.string.insights_day_item, shortNames[i], spokenDuration(s)) }
    val actions = shortNames.map { stringResource(R.string.insights_show_day, it) }
    SpringCard(modifier.fillMaxWidth()) {
        CardTitle(stringResource(R.string.insights_week))
        WeekBars(
            seconds, initials, sel, keys.lastIndex, guide,
            stringResource(R.string.insights_day_item, shortNames[sel], shortDuration(seconds[sel])),
            stringResource(R.string.insights_week_desc, items.joinToString(", ")),
            actions, { onSelect(keys[it]) }, Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun AppsCard(apps: List<AppTotal>, isToday: Boolean, modifier: Modifier) {
    val (top, other) = remember(apps) { othersRow(apps.filter { it.seconds >= 60 }, MAX_APP_ROWS) }
    val max = top.firstOrNull()?.seconds?.coerceAtLeast(other ?: 0) ?: 1L
    SpringCard(modifier.fillMaxWidth()) {
        CardTitle(stringResource(R.string.insights_by_app), if (isToday) stringResource(R.string.insights_by_app_today) else null)
        if (top.isEmpty()) Body(stringResource(R.string.insights_no_apps), Modifier.padding(top = 8.dp))
        top.forEach { a -> key(a.pkg) { AppBarRow(a.pkg, rememberLabel(a.pkg), a.seconds, max) } }
        if (other != null) { val o = stringResource(R.string.insights_other); AppBarRow(null, o, other, max) }
    }
}

@Composable
private fun AppBarRow(pkg: String?, name: String, seconds: Long, max: Long) {
    val f by animateFloatAsState((seconds.toFloat() / max).coerceIn(0f, 1f), EbbMotion.Standard(), label = "appBar")
    val bar = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (pkg != null) AppIconFor(pkg, name, 32.dp) else AppIcon(name, null, 32.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                Text(shortDuration(seconds), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
            Box(
                Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).drawBehind {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(bar, size = Size(size.width * f, size.height), cornerRadius = r)
                },
            )
        }
    }
}

@Composable
private fun HoursCard(hourly: IntArray, modifier: Modifier) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val peak = remember(hourly) { peakHour(hourly) }
    val labels = listOf(R.string.insights_hour_12a, R.string.insights_hour_6a, R.string.insights_hour_12p, R.string.insights_hour_6p).map { stringResource(it) }
    val tip = selected?.let { stringResource(R.string.insights_hour_item, hourName(it), shortDuration(hourly[it].toLong())) }
    val nonZero = hourly.indices.filter { hourly[it] >= 60 }.map { stringResource(R.string.insights_hour_item, hourName(it), spokenDuration(hourly[it].toLong())) }
    SpringCard(modifier.fillMaxWidth()) {
        CardTitle(stringResource(R.string.insights_when), stringResource(R.string.insights_when_sub))
        HourHeatmap(
            hourly, labels, selected, tip,
            stringResource(R.string.insights_when_desc, nonZero.joinToString(", ").ifEmpty { stringResource(R.string.insights_when_none) }),
            { selected = if (selected == it) null else it }, Modifier.padding(top = 12.dp),
        )
        if (peak != null) Body(stringResource(R.string.insights_peak, hourName(peak)), Modifier.padding(top = 4.dp))
    }
}
