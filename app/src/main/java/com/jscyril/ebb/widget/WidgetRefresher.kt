package com.jscyril.ebb.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.service.TrustedNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.time.Instant

/** Redraws the widgets after data changes (coalesced) and keeps one inexact alarm for the next status change. */
object WidgetRefresher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    /** Calls within ~2 s share one redraw, which reads the data as it is when it runs. */
    fun requestUpdate(ctx: Context): Job = synchronized(this) {
        pending?.takeIf { it.isActive } ?: run {
            val app = ctx.applicationContext
            scope.launch {
                delay(2_000)
                synchronized(this@WidgetRefresher) { pending = null } // a write from here on gets its own redraw
                runCatching { TodayWidget().updateAll(app); AppsWidget().updateAll(app) }
            }.also { pending = it }
        }
    }

    /** Sets (or clears, with no widgets placed) the alarm for the next session end, unlock or midnight. */
    fun scheduleNext(ctx: Context): Job {
        val app = ctx.applicationContext
        return scope.launch {
            runCatching {
                val am = app.getSystemService(AlarmManager::class.java) ?: return@launch
                val pi = PendingIntent.getBroadcast(
                    app, 0, Intent(app, WidgetRefreshReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val gm = GlanceAppWidgetManager(app)
                if (gm.getGlanceIds(TodayWidget::class.java).isEmpty() && gm.getGlanceIds(AppsWidget::class.java).isEmpty()) {
                    am.cancel(pi) // nothing to keep fresh: no wake-ups at all
                    return@launch
                }
                val repo = Repo.get(app)
                val watched = repo.rules().apps.keys
                val states = repo.statesFlow.first().filterKeys { it in watched }.values
                val trusted = TrustedNow.peek(app)
                val z = repo.zone()
                val midnight = Instant.ofEpochMilli(trusted).atZone(z).toLocalDate().plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
                // States hold trusted time; RTC alarms run on the wall clock, so shift by their offset. +1 s: the status has flipped.
                val at = nextChangeAt(states, trusted, midnight) - trusted + System.currentTimeMillis() + 1_000
                am.set(AlarmManager.RTC, at, pi) // inexact, non-waking: fires on the next wake after it is due
            }
        }
    }
}

/** The refresh alarm, plus clock and zone changes. Not exported: only the system and Ebb send to it. */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val done = goAsync() // keep the process alive through the 2 s coalescing delay
        val jobs = listOf(WidgetRefresher.requestUpdate(ctx), WidgetRefresher.scheduleNext(ctx))
        CoroutineScope(Dispatchers.Default).launch { jobs.joinAll(); done.finish() }
    }
}
