package com.jscyril.ebb.widget

import com.jscyril.ebb.engine.SessionEngine
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.service.TrustedNow
import com.jscyril.ebb.ui.app.AppStatus
import com.jscyril.ebb.ui.app.Apps
import com.jscyril.ebb.ui.app.appStatus
import kotlinx.coroutines.flow.first

/** Up to four watched apps with what each is doing now: open, in a session, locked or at its daily limit. */
class AppsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact // rows shown follow the height

    private class Item(val row: WidgetRow, val icon: Bitmap?)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repo.get(context)
        val colors = widgetColors(context, repo.themeFlow.first())
        val states = repo.statesFlow.first()
        val now = TrustedNow.peek(context)
        val zone = repo.zone()
        val r = repo.rules()
        val rows = SessionEngine.withSharedCap(r.apps, r.sharedCapMin, states, now, zone).map { (pkg, rule) ->
            WidgetRow(pkg, Apps.label(context, pkg), appStatus(rule, states[pkg] ?: Repo.emptyState(), now, zone))
        }
        val items = sortRows(rows).take(MAX_ROWS).map { Item(it, Apps.icon(context, it.pkg)?.asAndroidBitmap()) }
        provideContent { EbbGlanceTheme(colors) { Content(items) } }
    }

    @Composable
    private fun Content(items: List<Item>) {
        val ctx = LocalContext.current
        val colors = GlanceTheme.colors
        Box(widgetRoot(), contentAlignment = if (items.isEmpty()) Alignment.Center else Alignment.TopStart) {
            if (items.isEmpty()) {
                Text(ctx.getString(R.string.widget_empty), style = TextStyle(color = colors.onSurfaceVariant, fontSize = 14.sp))
                return@Box
            }
            val fit = ((LocalSize.current.height.value - 24) / ROW_DP).toInt().coerceIn(1, MAX_ROWS)
            Column(GlanceModifier.fillMaxWidth()) { for (it in items.take(fit)) AppRow(it) }
        }
    }

    @Composable
    private fun AppRow(item: Item) {
        val ctx = LocalContext.current
        val colors = GlanceTheme.colors
        val (text, spoken) = statusText(ctx, item.row)
        Row(
            GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.icon != null) Image(ImageProvider(item.icon), null, GlanceModifier.size(32.dp))
            else Spacer(GlanceModifier.size(32.dp))
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(item.row.label, style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                val active = item.row.status !is AppStatus.Open
                Text(text, style = TextStyle(color = if (active) colors.primary else colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
            }
        }
    }

    /** Shown text and the full sentence TalkBack reads for the row. */
    private fun statusText(ctx: Context, r: WidgetRow): Pair<String, String> = when (val s = r.status) {
        is AppStatus.Open -> ctx.getString(R.string.widget_status_open, s.usedMin, s.capMin) to
            ctx.resources.getQuantityString(R.plurals.widget_status_open_cd, s.capMin, r.label, s.usedMin, s.capMin)
        is AppStatus.InSession -> clockText(ctx, s.until).let { ctx.getString(R.string.widget_status_session, it) to ctx.getString(R.string.widget_status_session_cd, r.label, it) }
        is AppStatus.Locked -> clockText(ctx, s.until).let { ctx.getString(R.string.widget_status_locked, it) to ctx.getString(R.string.widget_status_locked_cd, r.label, it) }
        AppStatus.CapReached -> ctx.getString(R.string.widget_status_cap) to ctx.getString(R.string.widget_status_cap_cd, r.label)
    }

    private companion object {
        const val MAX_ROWS = 4
        const val ROW_DP = 44f
    }
}

class AppsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AppsWidget()

    /** Also on first placement: start the refresh alarm, which stays off while no widget exists. */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        WidgetRefresher.widgetsChanged()
        WidgetRefresher.scheduleNext(context)
    }
}
