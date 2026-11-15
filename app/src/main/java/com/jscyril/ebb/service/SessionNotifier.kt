package com.jscyril.ebb.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.jscyril.ebb.MainActivity
import com.jscyril.ebb.R

/**
 * One ongoing countdown notification per running session. On Android 16+ it asks to be promoted to a
 * Live Update, which shows as a timer chip in the status bar. It removes itself when the session ends.
 */
object SessionNotifier {
    private const val CHANNEL = "session"
    // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36.1); set by name so it compiles against SDK 36.
    private const val EXTRA_PROMOTED = "android.requestPromotedOngoing"

    fun show(ctx: Context, pkg: String, label: String, until: Long, now: Long) {
        if (until <= now) return cancel(ctx, pkg)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setSound(null, null); enableVibration(false); setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ebb)
            .setContentTitle(ctx.getString(R.string.notif_title, label))
            .setContentText(ctx.getString(R.string.notif_text))
            .setWhen(until).setShowWhen(true)
            .setUsesChronometer(true).setChronometerCountDown(true)
            .setOngoing(true).setOnlyAlertOnce(true) // the channel itself is silent
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setTimeoutAfter(until - now) // the session is a wall-clock window: drop it right at the end
            .setContentIntent(open)
            .addExtras(Bundle().apply { putBoolean(EXTRA_PROMOTED, true) })
            .build()
        runCatching { nm.notify(id(pkg), n) } // no POST_NOTIFICATIONS grant: silently skip
    }

    fun cancel(ctx: Context, pkg: String) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id(pkg))
    }

    private fun id(pkg: String) = pkg.hashCode()
}
