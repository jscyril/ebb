package com.jscyril.ebb.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
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
    private var fgPkg: String? = null // effective foreground: the gated watched app, else the last package seen
    private var timer: Job? = null
    private var imes: Set<String> = emptySet()
    private var sticky = false // full-screen Block/TimesUp survives the Home it triggers

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> serial { onScreenOff() }
                Intent.ACTION_INPUT_METHOD_CHANGED -> imes = loadImes()
                else -> wakeRecheck()
            }
        }
    }

    override fun onServiceConnected() {
        repo = Repo.get(this)
        clock = TrustedNow(this, repo, scope)
        overlay = OverlayHost(this)
        imes = loadImes()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_INPUT_METHOD_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        serial {} // init early
        recheck() // gate an app that is already open when the service (re)starts
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!ignored(pkg)) serial { onForeground(pkg) }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean { teardown(); return super.onUnbind(intent) }
    override fun onDestroy() { teardown(); super.onDestroy() }

    private fun ignored(pkg: String) = pkg == packageName || pkg == SYSTEM_UI || pkg in imes

    private fun loadImes(): Set<String> =
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.mapTo(HashSet()) { it.packageName } ?: emptySet()

    /** At unlock the active window is often still null or keyguard, so look again a little later. */
    private fun wakeRecheck() {
        imes = loadImes()
        for (d in longArrayOf(0, 500, 1_500)) scope.launch { delay(d); recheck() }
    }

    private fun recheck() {
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return // USER_PRESENT follows
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()?.takeUnless(::ignored)
        @Suppress("DEPRECATION") root?.recycle()
        serial { onForeground(pkg) } // null pkg still scans the visible windows
    }

    /** Runs [block] after all earlier ones; a failure is logged, never crashes the service. */
    private fun serial(block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                try {
                    if (!ready) { clock.init(); zone = repo.zone(); ready = true } // retried on the next event if it throws
                    block()
                } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "event failed", e) }
            }
        }
    }

    /** [pkg] changed to the foreground (null = unknown); a watched app still visible in PiP/split screen stays gated. */
    private suspend fun onForeground(pkg: String?) {
        val now = clock.now()
        val rules = activeRules(now)
        val direct = pkg != null && pkg in rules
        val wins = if (direct) emptyList() else appWindows()
        val target = if (direct) pkg else visibleWatched(wins.map { it.first }, rules.keys, tracker.pkg ?: fgPkg)
        val isNew = (target ?: pkg) != fgPkg
        fgPkg = target ?: pkg
        if (target == null) {
            cancelTimer(); if (!sticky) dismiss()
            record(tracker.onForeground(pkg, false, now))
            return
        }
        val bounds = if (direct) null else wins.first { it.first == target }.second
        gate(target, rules.getValue(target), now, isNew, bounds)
    }

    private suspend fun gate(pkg: String, rule: AppRule, now: Long, isNew: Boolean, bounds: Rect?) {
        var s = repo.state(pkg)
        if (s.sessionEndsAt?.let { now >= it } == true && s.emergencyUntil?.let { now < it } != true) {
            s = repo.updateState(pkg) { SessionEngine.endSession(it, now) } // timer missed while away: settle it
        }
        val d = SessionEngine.decide(rule, s, now, zone)
        record(tracker.onForeground(pkg, d is Decision.Allow, now))
        when (d) {
            is Decision.Allow -> { dismiss(); schedule(rule, pkg, now) }
            is Decision.Prompt -> {
                cancelTimer()
                val label = label(pkg)
                show(bounds, sticky = false) { PromptOverlay(label, d.options) { t -> serial { pick(pkg, t) } } }
            }
            is Decision.Blocked -> {
                cancelTimer()
                if (isNew) {
                    repo.logSession(pkg, now, now, 0, Kind.BLOCKED_ATTEMPT)
                    performGlobalAction(GLOBAL_ACTION_HOME) // background it so audio/video usually stop
                }
                val label = label(pkg)
                val until = if (d.reason == Reason.COOLDOWN) timeText(d.until) else null
                show(bounds, sticky = true) { BlockOverlay(label, until, ::goHome) }
            }
        }
    }

    private suspend fun pick(pkg: String, tier: Tier) {
        if (fgPkg != pkg) return
        val now = clock.now()
        val rule = activeRules(now)[pkg] ?: return
        val d = SessionEngine.decide(rule, repo.state(pkg), now, zone)
        if (d !is Decision.Prompt || tier !in d.options) return // double tap or state moved on
        repo.updateState(pkg) { SessionEngine.startSession(it, tier, now) }
        repo.logSession(pkg, now, now + tier.sessionMin * 60_000L, tier.sessionMin, Kind.SESSION)
        gate(pkg, rule, now, isNew = false, bounds = null) // now Allow: dismisses, starts the interval, schedules
    }

    /** Session end, cap reached or midnight while a watched app is foreground. */
    private suspend fun onTimer() {
        val pkg = tracker.pkg ?: return // user already left; the next open settles state
        val now = clock.now()
        record(tracker.onScreenOff(now)) // flush usage so the cap check below is current
        val rule = activeRules(now)[pkg] ?: run { dismiss(); return }
        val s = repo.updateState(pkg) { s ->
            val at = SessionEngine.nextAlarmAt(rule, s, now, zone)
            if (at == null || at <= now) SessionEngine.endSession(s, now) else s
        }
        if (SessionEngine.decide(rule, s, now, zone) is Decision.Allow) {
            tracker.onForeground(pkg, true, now) // midnight or emergency still active: keep going
            schedule(rule, pkg, now)
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
            show(null, sticky = true) { TimesUpOverlay(::goHome) }
        }
    }

    private suspend fun onScreenOff() {
        cancelTimer()
        fgPkg = null
        dismiss() // the wake re-check gates again
        record(tracker.onScreenOff(clock.now()))
    }

    /** Watched rules after maturing pending changes; empty while protection is off. */
    private suspend fun activeRules(now: Long): Map<String, AppRule> {
        val rules = repo.rules()
        val pending = repo.pending()
        val (matured, left) = ChangeQueue.mature(rules, pending, now)
        if (pending != null && left == null) { repo.saveRules(matured); repo.savePending(null) }
        return if (repo.protectionEnabledFlow.first()) matured.apps else emptyMap()
    }

    /** Package and screen bounds of each visible application window. */
    private fun appWindows(): List<Pair<String, Rect>> {
        val out = ArrayList<Pair<String, Rect>>()
        for (w in runCatching { windows }.getOrNull().orEmpty()) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root
            val p = root?.packageName?.toString()
            @Suppress("DEPRECATION") root?.recycle()
            if (p != null) out += p to Rect().also(w::getBoundsInScreen)
        }
        return out
    }

    /** Recomputes from fresh rules and state, counting the still-open interval so a tightened cap applies. */
    private suspend fun schedule(rule: AppRule, pkg: String, now: Long) {
        cancelTimer()
        var s = repo.state(pkg)
        if (tracker.pkg == pkg) s = SessionEngine.addForeground(s, ((now - tracker.start) / 1000).toInt(), now, zone)
        val at = SessionEngine.nextAlarmAt(rule, s, now, zone) ?: return
        // shortcut: Main delay pauses in deep sleep; fine because screen-off cancels it anyway
        // A cancelled timer may still run onTimer once (already queued in serial); benign, it recomputes.
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

    private fun show(bounds: Rect?, sticky: Boolean, content: @Composable () -> Unit) {
        this.sticky = sticky && bounds == null
        overlay?.show(bounds, content)
    }

    private fun dismiss() { overlay?.dismiss(); sticky = false }

    private fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        dismiss()
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
        runCatching { unregisterReceiver(receiver) }
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
