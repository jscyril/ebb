package com.jscyril.ebb.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.jscyril.ebb.data.Kind
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.engine.AppRule
import com.jscyril.ebb.engine.Decision
import com.jscyril.ebb.engine.Reason
import com.jscyril.ebb.engine.SessionEngine
import com.jscyril.ebb.engine.Tier
import com.jscyril.ebb.guard.ChangeQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Watches the foreground app, gates watched apps through [SessionEngine], tracks foreground time
 * and ends sessions on time. All handling runs serialized on the main thread via [serial].
 */
class BlockerService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val tracker = ForegroundTracker()
    private lateinit var repo: Repo
    private lateinit var clock: TrustedNow
    private lateinit var zone: ZoneId
    private var overlay: OverlayHost? = null
    private var ready = false
    private var torn = false
    private var fgPkg: String? = null // last foreground package (any, after filtering)
    private var timer: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) serial { onScreenOff() } else recheckForeground()
        }
    }

    override fun onServiceConnected() {
        repo = Repo.get(this)
        clock = TrustedNow(this, repo, scope)
        overlay = OverlayHost(this)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, f)
        scope.launch {
            mutex.withLock {
                try { clock.init(); zone = repo.zone(); ready = true } catch (e: Exception) { Log.e(TAG, "init failed", e) }
            }
        }
        recheckForeground() // gate an app that is already open when the service (re)starts
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        foregroundChanged(event.packageName?.toString())
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean { teardown(); return super.onUnbind(intent) }
    override fun onDestroy() { teardown(); super.onDestroy() }

    private fun recheckForeground() = foregroundChanged(rootInActiveWindow?.packageName?.toString())

    private fun foregroundChanged(pkg: String?) {
        if (pkg == null || pkg == packageName || pkg == SYSTEM_UI || pkg in imePackages()) return
        serial { onForeground(pkg) }
    }

    private fun imePackages(): Set<String> =
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.mapTo(HashSet()) { it.packageName } ?: emptySet()

    /** Runs [block] after all earlier ones; a failure is logged, never crashes the service. */
    private fun serial(block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                try { if (ready) block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "event failed", e) }
            }
        }
    }

    private suspend fun onForeground(pkg: String) {
        val now = clock.now()
        val isNew = pkg != fgPkg
        fgPkg = pkg
        val rule = ruleFor(pkg, now)
        if (rule == null) {
            cancelTimer(); overlay?.dismiss()
            record(tracker.onForeground(pkg, false, now))
            return
        }
        var s = repo.state(pkg)
        if (s.sessionEndsAt?.let { now >= it } == true && s.emergencyUntil?.let { now < it } != true) {
            s = repo.updateState(pkg) { SessionEngine.endSession(it, now) } // alarm missed while away: settle it
        }
        val d = SessionEngine.decide(rule, s, now, zone)
        val continuing = d is Decision.Allow && tracker.pkg == pkg
        record(tracker.onForeground(pkg, d is Decision.Allow, now))
        when (d) {
            is Decision.Allow -> { overlay?.dismiss(); if (!continuing) schedule(rule, pkg, now) }
            is Decision.Prompt -> {
                cancelTimer()
                val label = label(pkg)
                overlay?.show { PromptOverlay(label, d.options) { t -> serial { pick(pkg, t) } } }
            }
            is Decision.Blocked -> {
                cancelTimer()
                if (isNew) repo.logSession(pkg, now, now, 0, Kind.BLOCKED_ATTEMPT)
                val label = label(pkg)
                val until = if (d.reason == Reason.COOLDOWN) timeText(d.until) else null
                overlay?.show { BlockOverlay(label, until, ::goHome) }
            }
        }
    }

    private suspend fun pick(pkg: String, tier: Tier) {
        if (fgPkg != pkg) return
        val now = clock.now()
        val rule = ruleFor(pkg, now) ?: return
        val d = SessionEngine.decide(rule, repo.state(pkg), now, zone)
        if (d !is Decision.Prompt || tier !in d.options) return // double tap or state moved on
        repo.updateState(pkg) { SessionEngine.startSession(it, tier, now) }
        repo.logSession(pkg, now, now + tier.sessionMin * 60_000L, tier.sessionMin, Kind.SESSION)
        onForeground(pkg) // now Allow: dismisses, starts the interval, schedules the timer
    }

    /** Session end, cap reached or midnight while a watched app is foreground. */
    private suspend fun onTimer() {
        val pkg = tracker.pkg ?: return // user already left; the next open settles state
        val now = clock.now()
        record(tracker.onScreenOff(now)) // flush usage so the cap check below is current
        val rule = ruleFor(pkg, now) ?: run { overlay?.dismiss(); return }
        val s = repo.updateState(pkg) { s ->
            val at = SessionEngine.nextAlarmAt(rule, s, now, zone)
            if (at == null || at <= now) SessionEngine.endSession(s, now) else s
        }
        if (SessionEngine.decide(rule, s, now, zone) is Decision.Allow) {
            tracker.onForeground(pkg, true, now) // midnight or emergency still active: keep going
            schedule(rule, pkg, now)
        } else {
            overlay?.show { TimesUpOverlay(::goHome) }
        }
    }

    private suspend fun onScreenOff() {
        cancelTimer()
        fgPkg = null
        record(tracker.onScreenOff(clock.now()))
    }

    private suspend fun ruleFor(pkg: String, now: Long): AppRule? {
        val rules = repo.rules()
        val pending = repo.pending()
        val (matured, left) = ChangeQueue.mature(rules, pending, now)
        if (pending != null && left == null) { repo.saveRules(matured); repo.savePending(null) }
        return if (repo.protectionEnabledFlow.first()) matured.apps[pkg] else null
    }

    private suspend fun schedule(rule: AppRule, pkg: String, now: Long) {
        cancelTimer()
        val at = SessionEngine.nextAlarmAt(rule, repo.state(pkg), now, zone) ?: return
        // shortcut: Main delay pauses in deep sleep; fine because screen-off cancels it anyway
        timer = scope.launch { delay((at - now).coerceAtLeast(0)); serial { onTimer() } }
    }

    private fun cancelTimer() { timer?.cancel(); timer = null }

    private suspend fun record(iv: ForegroundTracker.Interval?) {
        iv ?: return
        repo.logForeground(iv.pkg, iv.start, iv.end)
        val sec = ((iv.end - iv.start + 500) / 1000).toInt()
        // end - 1: a flush at the midnight timer belongs to the day that just ended
        repo.updateState(iv.pkg) { SessionEngine.addForeground(it, sec, iv.end - 1, zone) }
    }

    private fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        overlay?.dismiss()
    }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun timeText(at: Long): String =
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a")
            .withZone(zone).format(Instant.ofEpochMilli(at))

    private fun teardown() {
        if (torn) return
        torn = true
        runCatching { unregisterReceiver(screenReceiver) }
        overlay?.dismiss(); overlay = null
        val iv = if (ready) tracker.onScreenOff(clock.now()) else null
        scope.cancel()
        if (iv != null) CoroutineScope(Dispatchers.IO).launch { runCatching { record(iv) } }
    }

    private companion object {
        const val TAG = "EbbBlocker"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
