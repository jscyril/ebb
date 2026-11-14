package com.jscyril.ebb.service

/** Tracks the open foreground interval of the current watched app; pure, so it is unit-tested. */
class ForegroundTracker {
    data class Interval(val pkg: String, val start: Long, val end: Long)

    /** The watched package whose interval is open, if any. */
    var pkg: String? = null
        private set
    /** Start of the open interval; meaningful only while [pkg] is non-null. */
    var start = 0L
        private set

    /** Returns the closed interval of the previously tracked watched app, if any. */
    fun onForeground(pkg: String?, watched: Boolean, now: Long): Interval? {
        if (watched && pkg == this.pkg) return null
        val closed = close(now)
        if (watched && pkg != null) { this.pkg = pkg; start = now }
        return closed
    }

    fun onScreenOff(now: Long): Interval? = close(now)

    private fun close(now: Long): Interval? {
        val p = pkg ?: return null
        pkg = null
        return if (now > start) Interval(p, start, now) else null
    }
}

/** Which watched app is visible among [windowPkgs] (e.g. PiP, split screen); prefers [current]. */
fun visibleWatched(windowPkgs: List<String>, watched: Set<String>, current: String?): String? =
    current?.takeIf { it in watched && it in windowPkgs } ?: windowPkgs.firstOrNull { it in watched }

/** Counts one blocked attempt per package per [windowMs], so the wake re-checks don't double-count. */
class AttemptDedupe(private val windowMs: Long) {
    private var lastPkg: String? = null
    private var lastAt = 0L

    fun shouldCount(pkg: String, now: Long): Boolean {
        if (pkg == lastPkg && now - lastAt < windowMs) return false
        lastPkg = pkg; lastAt = now
        return true
    }
}

enum class OverlayMode { FULL, WINDOW }

/**
 * Full-screen when the watched app is the active window; window-limited when it is visible only beside a
 * non-watched active app. While our own overlay holds focus (active = own/unknown) the current mode is kept,
 * otherwise FULL -> WINDOW -> FULL would flip-flop on every relayout. Null: no watched app visible.
 */
fun overlayMode(activePkg: String?, ownPkg: String, watchedVisible: String?, watched: Set<String>, current: OverlayMode?): OverlayMode? =
    when {
        activePkg != null && activePkg in watched -> OverlayMode.FULL
        watchedVisible == null -> null
        current != null && (activePkg == null || activePkg == ownPkg) -> current
        else -> OverlayMode.WINDOW
    }
