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
