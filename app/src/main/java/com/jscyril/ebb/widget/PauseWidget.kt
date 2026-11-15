package com.jscyril.ebb.widget

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
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
import com.jscyril.ebb.guard.PaymentPause
import com.jscyril.ebb.service.SelfToggle
import com.jscyril.ebb.service.TrustedNow
import kotlinx.coroutines.flow.first
import java.util.Date

/** One-tap payment pause, mirroring the Quick Settings tile. */
class PauseWidget : GlanceAppWidget() {
    private data class Data(val title: String, val sub: String, val paused: Boolean)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repo.get(context)
        val colors = widgetColors(context, repo.themeFlow.first())
        val now = TrustedNow.peek(context)
        val until = repo.pausedUntil()
        val paused = PaymentPause.isPaused(until, now)
        val sub = when {
            !SelfToggle.canWrite(context) -> context.getString(R.string.pause_tile_setup)
            paused -> context.getString(R.string.pause_tile_until, DateFormat.getTimeFormat(context).format(Date(until!!)))
            else -> context.getString(R.string.pause_tile_left, PaymentPause.remaining(repo.pauseUses(), now, repo.zone()))
        }
        val title = context.getString(if (paused) R.string.pause_widget_paused else R.string.pause_tile)
        provideContent { EbbGlanceTheme(colors) { Content(Data(title, sub, paused)) } }
    }

    @Composable
    private fun Content(d: Data) {
        val c = GlanceTheme.colors
        val bg = if (d.paused) c.primaryContainer else c.widgetBackground
        val fg = if (d.paused) c.onPrimaryContainer else c.onSurface
        val base = GlanceModifier.fillMaxSize().appWidgetBackground().background(bg)
        val shaped = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) base.cornerRadius(24.dp) else base
        Row(
            shaped.clickable(actionRunCallback<PauseToggleAction>()).padding(12.dp).semantics { contentDescription = "${d.title}, ${d.sub}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(ImageProvider(R.drawable.ic_schedule), null, GlanceModifier.size(28.dp), colorFilter = ColorFilter.tint(if (d.paused) fg else c.primary))
            Spacer(GlanceModifier.width(10.dp))
            Column {
                Text(d.title, style = TextStyle(color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(d.sub, style = TextStyle(color = if (d.paused) fg else c.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
            }
        }
    }

    companion object {
        suspend fun refresh(ctx: Context) { runCatching { PauseWidget().updateAll(ctx.applicationContext) } }
    }
}

class PauseToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val paused = PaymentPause.isPaused(Repo.get(context).pausedUntil(), TrustedNow.peek(context))
        if (paused) SelfToggle.resume(context) else when (SelfToggle.pause(context)) {
            SelfToggle.Result.NOT_SET_UP -> Toast.makeText(context, R.string.pause_not_set_up, Toast.LENGTH_LONG).show()
            SelfToggle.Result.NONE_LEFT -> Toast.makeText(context, R.string.pause_none_left, Toast.LENGTH_LONG).show()
            SelfToggle.Result.PAUSED -> {}
        }
        PauseWidget.refresh(context)
    }
}

class PauseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PauseWidget()
}
