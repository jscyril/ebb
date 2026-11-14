package com.jscyril.ebb.service

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.jscyril.ebb.data.ClockAnchor
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.engine.TrustedClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Tamper-resistant "now" for engine calls. Call [init] once before [now]. */
class TrustedNow(private val ctx: Context, private val repo: Repo, private val scope: CoroutineScope) {
    private lateinit var anchor: ClockAnchor
    private var lastSavedElapsed = 0L

    suspend fun init() {
        val boot = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 0)
        val a = repo.clockAnchor()
        val elapsed = SystemClock.elapsedRealtime()
        val t = TrustedClock.trusted(
            a?.wall ?: 0, a?.elapsed ?: 0, elapsed, System.currentTimeMillis(), a?.lastTrusted ?: 0,
            rebooted = a == null || a.bootCount != boot,
        )
        anchor = ClockAnchor(t, elapsed, t, boot)
        lastSavedElapsed = elapsed
        repo.saveClockAnchor(anchor)
    }

    fun now(): Long {
        val elapsed = SystemClock.elapsedRealtime()
        val t = TrustedClock.trusted(anchor.wall, anchor.elapsed, elapsed, System.currentTimeMillis(), anchor.lastTrusted, rebooted = false)
        if (elapsed - lastSavedElapsed >= 60_000) {
            lastSavedElapsed = elapsed
            anchor = anchor.copy(lastTrusted = t)
            val a = anchor
            scope.launch { repo.saveClockAnchor(a) }
        }
        return t
    }
}
