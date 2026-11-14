package com.jscyril.ebb.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
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
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.ui.app.Apps
import kotlinx.coroutines.flow.first

/** Today's watched-app time against the summed daily caps, as a ring. */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SQUARE, SLIM, WIDE, FULL))

    private data class Data(val usedSec: Long, val capSec: Long, val top: List<Pair<String, Long>>)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repo.get(context)
        val colors = widgetColors(context, repo.themeFlow.first())
        val caps = repo.rules().apps.values.sumOf { it.dailyCapMin.coerceAtLeast(0) } * 60L
        val today = repo.dailyTotals(1).first().last()
        val top = repo.perApp(today.dayKey).first().filter { it.seconds >= 60 }.take(3).map { Apps.label(context, it.pkg) to it.seconds }
        val data = Data(today.seconds, caps, top)
        provideContent { EbbGlanceTheme(colors) { Content(data) } }
    }

    @Composable
    private fun Content(d: Data) {
        val ctx = LocalContext.current
        val size = LocalSize.current
        val fraction = if (d.capSec > 0) d.usedSec.toFloat() / d.capSec else 0f
        val used = shortDuration(ctx, d.usedSec)
        val desc = if (d.capSec > 0) ctx.getString(R.string.widget_ring_desc, spokenDuration(ctx, d.usedSec), spokenDuration(ctx, d.capSec))
        else ctx.getString(R.string.widget_ring_desc_nocap, spokenDuration(ctx, d.usedSec))
        val of = if (d.capSec > 0) ctx.getString(R.string.widget_of, shortDuration(ctx, d.capSec)) else null
        val colors = GlanceTheme.colors
        val big = TextStyle(color = colors.onSurface, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        val small = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp)

        Box(widgetRoot(), contentAlignment = Alignment.Center) {
            if (size.height < 100.dp) {
                // 4x1: ring, "42m today", "18m left"
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Ring(fraction, 36.dp, desc)
                    Spacer(GlanceModifier.width(12.dp))
                    Text(ctx.getString(R.string.widget_today_used, used), GlanceModifier.defaultWeight(), style = big.copy(fontSize = 18.sp), maxLines = 1)
                    if (d.capSec > 0 && size.width >= 200.dp) {
                        val left = shortDuration(ctx, (d.capSec - d.usedSec).coerceAtLeast(0))
                        Text(ctx.getString(R.string.widget_left, left), style = small.copy(fontSize = 14.sp), maxLines = 1)
                    }
                }
            } else {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                    val ring = (minOf(size.width, size.height) - 24.dp).coerceIn(64.dp, 120.dp)
                    Box(contentAlignment = Alignment.Center) {
                        Ring(fraction, ring, desc)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(used, style = if (used.length > 4) big.copy(fontSize = 20.sp) else big, maxLines = 1)
                            if (of != null) Text(of, style = small, maxLines = 1)
                        }
                    }
                    if (size.width >= 200.dp) TopApps(d.top, small)
                }
            }
        }
    }

    @Composable
    private fun RowScope.TopApps(top: List<Pair<String, Long>>, style: TextStyle) {
        val ctx = LocalContext.current
        val colors = GlanceTheme.colors
        Column(GlanceModifier.defaultWeight().padding(start = 16.dp)) {
            Text(ctx.getString(R.string.widget_top_apps), style = style.copy(color = colors.primary, fontWeight = FontWeight.Medium), maxLines = 1)
            if (top.isEmpty()) Text(ctx.getString(R.string.widget_no_use), GlanceModifier.padding(top = 6.dp), style = style, maxLines = 2)
            for ((label, sec) in top) Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(label, GlanceModifier.defaultWeight(), style = style.copy(color = colors.onSurface, fontSize = 14.sp), maxLines = 1)
                Text(shortDuration(ctx, sec), style = style.copy(fontSize = 14.sp), maxLines = 1)
            }
        }
    }

    @Composable
    private fun Ring(fraction: Float, size: Dp, desc: String) {
        val ctx = LocalContext.current
        Image(
            ImageProvider(ringFor(ctx, size, fraction)), desc, GlanceModifier.size(size),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        )
    }

    companion object {
        private val SQUARE = DpSize(110.dp, 110.dp)
        private val SLIM = DpSize(110.dp, 50.dp) // 2x1: the row without "left"
        private val WIDE = DpSize(250.dp, 50.dp)
        private val FULL = DpSize(250.dp, 110.dp)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    /** Also on first placement: start the refresh alarm, which stays off while no widget exists. */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        WidgetRefresher.scheduleNext(context)
    }
}
