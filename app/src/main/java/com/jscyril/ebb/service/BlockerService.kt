package com.jscyril.ebb.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Kind
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.engine.AppRule
import com.jscyril.ebb.engine.Decision
import com.jscyril.ebb.engine.Reason
import com.jscyril.ebb.engine.SessionEngine
import com.jscyril.ebb.engine.Tier
import com.jscyril.ebb.guard.Admin
import com.jscyril.ebb.guard.ChangeQueue
import com.jscyril.ebb.guard.EmergencyPass
import com.jscyril.ebb.guard.GuardMatcher
import com.jscyril.ebb.guard.Pledge
import com.jscyril.ebb.ui.overlay.BlockScreen
import com.jscyril.ebb.ui.overlay.EmergencyActivity
import com.jscyril.ebb.ui.overlay.PromptScreen
import com.jscyril.ebb.ui.overlay.TimesUpScreen
import com.jscyril.ebb.ui.theme.EbbThemeFromRepo
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
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

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
    private var rescan: Job? = null
    private var rescanDue = 0L // uptime by which a pending debounced recheck must run
    private var mode: OverlayMode? = null // null = no overlay
    private val attempts = AttemptDedupe(3_000)
    private val focus by lazy { AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).build() }
    private var holdingFocus = false
    private var guardScan: Job? = null
    private var guardPkg: String? = null // last guard-package window state, so fragment-nav scans keep its class
    private var guardCls: String? = null
    private var lastToast = 0L
    private val icons = LruCache<String, ImageBitmap>(8)

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
        instance = this
        repo = Repo.get(this)
        clock = TrustedNow(this, repo, scope)
        overlay = OverlayHost(this, ::goHome)
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
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Clicks fire constantly in every app: bail before any allocation unless it is a guard package.
            val p = event.packageName ?: return
            if (GuardMatcher.isGuardPackage(p)) scheduleGuard() // fragment navigation sends no state event
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // PiP move/resize, split divider etc. Fires often, so stay idle unless something is gated or a window appeared.
            val added = event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ADDED != 0
            if (tracker.pkg != null || overlay?.isShowing == true || added) scheduleRecheck(250)
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (GuardMatcher.isGuardPackage(pkg)) {
            guardPkg = pkg; guardCls = event.className?.toString()
            guard(retry = true)
        }
        if (!ignored(pkg)) serial { onForeground(pkg) }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean { teardown(); return super.onUnbind(intent) }
    override fun onDestroy() { teardown(); super.onDestroy() }

    private fun scheduleGuard() {
        guardScan?.cancel()
        guardScan = scope.launch { delay(300); guard(retry = false) }
    }

    /** Scans the active window now; ejects (Back, then Home) if it could disable or uninstall Ebb. */
    private fun guard(retry: Boolean) {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (pkg == null || !GuardMatcher.isGuardPackage(pkg)) {
            @Suppress("DEPRECATION") root?.recycle()
            if (retry) scheduleGuard() // active window not updated yet
            return
        }
        val texts = texts(root)
        val cls = guardCls.takeIf { guardPkg == pkg }
        serial {
            if (!repo.protectionEnabledFlow.first()) return@serial
            val pledge = Pledge.isActive(repo.pledge(), clock.now())
            if (!GuardMatcher.shouldEject(pkg, cls, texts, getString(R.string.app_name), pledge)) return@serial
            // Our own activation screen (Admin.enableIntent) must stay usable until the admin is on.
            if (cls?.contains("DeviceAdminAdd") == true && !Admin.isActive(this)) return@serial
            eject()
        }
    }

    /** Breadth-first text and content descriptions of up to [GUARD_NODES] nodes; recycles every node. */
    private fun texts(root: AccessibilityNodeInfo): List<CharSequence> {
        val out = ArrayList<CharSequence>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            seen++
            n.text?.let { out += it.toString() }
            n.contentDescription?.let { out += it.toString() }
            for (i in 0 until n.childCount) {
                if (seen + queue.size >= GUARD_NODES) break
                n.getChild(i)?.let(queue::add)
            }
            @Suppress("DEPRECATION") n.recycle()
        }
        return out
    }

    private fun eject() {
        performGlobalAction(GLOBAL_ACTION_BACK) // closes a dialog
        scope.launch { delay(150); performGlobalAction(GLOBAL_ACTION_HOME) } // leaves Settings
        val now = SystemClock.uptimeMillis()
        if (now - lastToast < 5_000) return
        lastToast = now
        Toast.makeText(this, R.string.guard_toast, Toast.LENGTH_SHORT).show()
    }

    private fun ignored(pkg: String) = pkg == packageName || pkg == SYSTEM_UI || pkg in imes

    private fun loadImes(): Set<String> =
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.mapTo(HashSet()) { it.packageName } ?: emptySet()

    /** At unlock the active window is often still null or keyguard, so look again a little later. */
    private fun wakeRecheck() {
        imes = loadImes()
        for (d in longArrayOf(0, 500, 1_500)) scope.launch { delay(d); recheck() }
    }

    /** Trailing debounce: a newer request replaces a pending one, but a pending one runs within 1 s at most. */
    private fun scheduleRecheck(ms: Long) {
        val now = SystemClock.uptimeMillis()
        if (rescan?.isActive != true) rescanDue = now + 1_000
        rescan?.cancel()
        rescan = scope.launch { delay(minOf(ms, rescanDue - now).coerceAtLeast(0)); recheck() }
    }

    private fun recheck() {
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return // USER_PRESENT follows
        val root = rootInActiveWindow
        val raw = root?.packageName?.toString()
        @Suppress("DEPRECATION") root?.recycle()
        if (raw == packageName && overlay?.isShowing == true) return // our overlay has focus: leave it be (no flip-flop)
        val pkg = raw?.takeUnless(::ignored)
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
        val m = overlayMode(pkg, packageName, target, rules.keys, mode)
        val bounds = if (m == OverlayMode.WINDOW) wins.first { it.first == target }.second else null
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
                val name = label(pkg)
                val icon = icon(pkg)
                val used = if (s.dayKey == SessionEngine.dayKey(now, zone)) s.usedTodaySec / 60 else 0
                show(bounds, sticky = false) {
                    PromptScreen(name, icon, d.options, used, rule.dailyCapMin, onPick = { t -> serial { pick(pkg, t) } }, onGoHome = ::goHome)
                }
            }
            is Decision.Blocked -> {
                cancelTimer()
                if (isNew && attempts.shouldCount(pkg, now)) {
                    repo.logSession(pkg, now, now, 0, Kind.BLOCKED_ATTEMPT)
                    performGlobalAction(GLOBAL_ACTION_HOME) // background it so audio/video usually stop
                }
                showBlock(pkg, rule, d, now, bounds)
            }
        }
    }

    private suspend fun showBlock(pkg: String, rule: AppRule, d: Decision.Blocked, now: Long, bounds: Rect?) {
        val name = label(pkg)
        val icon = icon(pkg)
        val passes = passesLeft(now)
        val from = lockStart(rule, d, now)
        show(bounds, sticky = true) {
            BlockScreen(name, icon, d.until, d.reason, passes, onEmergency = { serial { openEmergency(pkg) } }, onGoHome = ::goHome, onUnlocked = ::unlocked, now = now, lockedFrom = from)
        }
    }

    /**
     * When the lock began, for the ring. shortcut: AppState keeps no lock start, so a cooldown takes the shortest
     * tier lock that covers the time left; store the start in AppState if the ring must be exact.
     */
    private fun lockStart(rule: AppRule, d: Decision.Blocked, now: Long): Long = when (d.reason) {
        Reason.DAILY_CAP -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        Reason.COOLDOWN -> d.until - (rule.tiers.map { it.lockMin * 60_000L }.filter { d.until - it <= now }.minOrNull() ?: (d.until - now))
    }

    private suspend fun passesLeft(now: Long) =
        EmergencyPass.remaining(repo.emergencyUses(), repo.rules().passesPerMonth, now, zone)

    /** Block's emergency action: leaves the overlay for [EmergencyActivity], a normal window where the keyboard works. */
    private suspend fun openEmergency(pkg: String) {
        val now = clock.now()
        val rule = activeRules(now)[pkg] ?: return
        val passes = passesLeft(now)
        if (!EmergencyPass.mayUse(SessionEngine.decide(rule, repo.state(pkg), now, zone), passes)) return
        cancelTimer()
        dismiss()
        // Our own package's events are ignored, so close tracking here as if the user left.
        record(tracker.onForeground(null, false, now))
        fgPkg = null
        startActivity(EmergencyActivity.intent(this, pkg, label(pkg), passes))
    }

    /** Called by [EmergencyActivity] after the wait and the typed phrase. */
    fun confirmEmergency(pkg: String) = serial {
        val now = clock.now()
        val rule = activeRules(now)[pkg] ?: return@serial
        if (EmergencyPass.mayUse(SessionEngine.decide(rule, repo.state(pkg), now, zone), passesLeft(now))) {
            repo.addEmergencyUse(now)
            repo.logSession(pkg, now, now + SessionEngine.EMERGENCY_MIN * 60_000L, SessionEngine.EMERGENCY_MIN, Kind.EMERGENCY)
            repo.updateState(pkg) { SessionEngine.startEmergency(it, now) }
        }
        // Either way open the app: its window event gates it (Allow now, or Prompt/Block if no pass was spent).
        runCatching { packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(::startActivity) }
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
        val d = SessionEngine.decide(rule, s, now, zone)
        if (d is Decision.Allow) {
            tracker.onForeground(pkg, true, now) // midnight or emergency still active: keep going
            schedule(rule, pkg, now)
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val name = label(pkg)
            val icon = icon(pkg)
            val next = (d as? Decision.Blocked)?.until ?: now // Prompt: nothing locked, the session just ended
            show(null, sticky = true) { TimesUpScreen(name, icon, next, ::goHome) }
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

    /** [sticky] (Block/TimesUp) survives the Home it triggers when full screen. Any overlay holds audio focus to pause a playing video. */
    private fun show(bounds: Rect?, sticky: Boolean, content: @Composable () -> Unit) {
        this.sticky = sticky && bounds == null
        mode = if (bounds == null) OverlayMode.FULL else OverlayMode.WINDOW
        overlay?.show(bounds) { EbbThemeFromRepo(content) }
        audioFocus(true)
    }

    private fun dismiss() { overlay?.dismiss(); sticky = false; mode = null; audioFocus(false) }

    private fun audioFocus(hold: Boolean) {
        if (hold == holdingFocus) return
        val am = getSystemService(AudioManager::class.java) ?: return
        holdingFocus = hold
        if (hold) am.requestAudioFocus(focus) else am.abandonAudioFocusRequest(focus)
    }

    /** Block's lock ran out: drop it and re-check, so the user can carry on in the now-unlocked app. */
    private fun unlocked() {
        dismiss()
        scheduleRecheck(0)
    }

    /** Go home button, Back key and TimesUp; re-checks after, so a PiP that survives Home is covered again. */
    private fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        dismiss()
        scheduleRecheck(400)
    }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Small launcher icon for the overlays, rendered off the main thread and cached. */
    private suspend fun icon(pkg: String): ImageBitmap? = icons[pkg] ?: withContext(Dispatchers.Default) {
        runCatching { packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
    }?.also { icons.put(pkg, it) }

    private fun teardown() {
        if (torn) return
        torn = true
        if (instance === this) instance = null
        runCatching { unregisterReceiver(receiver) }
        dismiss(); overlay = null
        val iv = if (ready) tracker.onScreenOff(clock.now()) else null
        scope.cancel()
        if (iv != null) CoroutineScope(Dispatchers.IO).launch { runCatching { record(iv) } }
    }

    companion object {
        /** The connected service, for [EmergencyActivity]; null while protection isn't running. */
        @Volatile var instance: BlockerService? = null
            private set

        private const val TAG = "EbbBlocker"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val GUARD_NODES = 250
        private const val ICON_PX = 96
    }
}
