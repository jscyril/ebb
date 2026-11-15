package com.jscyril.ebb.service

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.guard.PaymentPause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Switches Ebb's own accessibility service off for a payment and back on, and turns it back on whenever it went off
 * while protection is on. Needs WRITE_SECURE_SETTINGS, which only adb can grant (once): see [grantCommand].
 */
object SelfToggle {
    fun grantCommand(ctx: Context) = "adb shell pm grant ${ctx.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    fun canWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun me(ctx: Context) = ComponentName(ctx, BlockerService::class.java)

    private fun enabled(ctx: Context): List<String> =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }

    fun isOn(ctx: Context) = enabled(ctx).any { ComponentName.unflattenFromString(it) == me(ctx) }

    private fun set(ctx: Context, on: Boolean) = runCatching {
        val others = enabled(ctx).filter { ComponentName.unflattenFromString(it) != me(ctx) }
        val list = if (on) others + me(ctx).flattenToString() else others
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        if (on) Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }.isSuccess

    enum class Result { PAUSED, NONE_LEFT, NOT_SET_UP }

    suspend fun pause(ctx: Context): Result {
        if (!canWrite(ctx)) return Result.NOT_SET_UP
        val repo = Repo.get(ctx)
        val now = TrustedNow.peek(ctx)
        if (PaymentPause.isPaused(repo.pausedUntil(), now)) return Result.PAUSED
        if (PaymentPause.remaining(repo.pauseUses(), now, repo.zone()) == 0) return Result.NONE_LEFT
        repo.startPause(now, now + PaymentPause.DURATION_MS) // first, so the service going down doesn't heal itself
        schedule(ctx, PaymentPause.DURATION_MS)
        set(ctx, false)
        return Result.PAUSED
    }

    /** Ends a pause early (tightening, so always allowed). */
    suspend fun resume(ctx: Context) {
        Repo.get(ctx).endPause()
        heal(ctx)
    }

    /** Turns the service back on if protection is on and no pause is running; a running pause gets its end re-armed. */
    suspend fun heal(ctx: Context) {
        if (!canWrite(ctx)) return
        val repo = Repo.get(ctx)
        if (!repo.onboardingDoneFlow.first() || !repo.protectionEnabledFlow.first()) return
        val until = repo.pausedUntil()
        val left = (until ?: 0) - TrustedNow.peek(ctx)
        if (left > 0) return schedule(ctx, left)
        if (until != null) repo.endPause()
        if (!isOn(ctx)) set(ctx, true)
    }

    /** Elapsed-realtime alarm, so a clock change can't stretch a pause. */
    fun schedule(ctx: Context, inMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, SelfToggleReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val at = SystemClock.elapsedRealtime() + inMs
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    }
}

/** Pause end, boot and app updates (which turn the service off on some phones): [SelfToggle.heal]. */
class SelfToggleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { SelfToggle.heal(ctx) } finally { done.finish() } }
    }
}
