package com.jscyril.ebb.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.engine.Decision
import com.jscyril.ebb.engine.SessionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * While a watched app is locked, its notifications are snoozed until the lock ends, so they can't pull you back in
 * and nothing is lost. Ongoing ones (media, calls) are left alone.
 */
class NotificationSilencer : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() { instance = this; sweep() }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; scope.cancel(); super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) { scope.launch { silence(listOf(sbn)) } }

    private fun sweep() { scope.launch { silence(runCatching { activeNotifications.toList() }.getOrDefault(emptyList())) } }

    private suspend fun silence(list: List<StatusBarNotification>) {
        val repo = Repo.get(this)
        if (!repo.protectionEnabledFlow.first()) return
        val all = repo.rules()
        if (list.none { it.packageName in all.apps }) return
        val now = TrustedNow.peek(this)
        val zone = repo.zone()
        val rules = SessionEngine.withSharedCap(all.apps, all.sharedCapMin, repo.statesFlow.first(), now, zone)
        for (sbn in list) {
            if (sbn.isOngoing) continue
            val rule = rules[sbn.packageName] ?: continue
            val d = SessionEngine.decide(rule, repo.state(sbn.packageName), now, zone) as? Decision.Blocked ?: continue
            runCatching { snoozeNotification(sbn.key, d.until - now) }
        }
    }

    companion object {
        @Volatile private var instance: NotificationSilencer? = null

        /** A lock just began: snooze what that app already posted. */
        fun sweepNow() = instance?.sweep()

        fun component(ctx: Context) = ComponentName(ctx, NotificationSilencer::class.java)

        fun isGranted(ctx: Context) =
            ctx.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component(ctx)) == true

        fun settingsIntent(ctx: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(ctx).flattenToString())
            else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
