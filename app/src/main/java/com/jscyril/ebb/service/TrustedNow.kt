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
        val boot = bootCount(ctx)
        val elapsed = SystemClock.elapsedRealtime()
        val t = fromAnchor(repo.clockAnchor(), boot, elapsed)
        anchor = ClockAnchor(t, elapsed, t, boot, System.currentTimeMillis() - t)
        lastSavedElapsed = elapsed
        repo.saveClockAnchor(anchor)
    }

    /** [persist] saves the anchor now instead of at most once a minute; used when the wall clock was changed. */
    fun now(persist: Boolean = false): Long {
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val t = TrustedClock.trusted(anchor.wall, anchor.elapsed, elapsed, wall, anchor.lastTrusted, rebooted = false)
        if (persist || elapsed - lastSavedElapsed >= 60_000) {
            lastSavedElapsed = elapsed
            anchor = anchor.copy(lastTrusted = t, skew = wall - t)
            val a = anchor
            scope.launch { repo.saveClockAnchor(a) }
        }
        return t
    }

    companion object {
        private fun bootCount(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 0)

        private fun fromAnchor(a: ClockAnchor?, boot: Int, elapsed: Long) = TrustedClock.trusted(
            a?.wall ?: 0, a?.elapsed ?: 0, elapsed, System.currentTimeMillis(), a?.lastTrusted ?: 0,
            rebooted = a == null || a.bootCount != boot || elapsed < a.elapsed, skew = a?.skew ?: 0,
        )

        /** Trusted now for the app's screens: read-only, so only the service ever moves the anchor. */
        suspend fun peek(ctx: Context): Long =
            fromAnchor(Repo.get(ctx).clockAnchor(), bootCount(ctx), SystemClock.elapsedRealtime())
    }
}
