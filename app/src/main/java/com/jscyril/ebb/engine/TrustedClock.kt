package com.jscyril.ebb.engine

object TrustedClock {
    // shortcut: reboot + forward wall-clock jump can still shorten a cooldown, close with Device Owner
    /** Elapsed-realtime based while the boot is unchanged (wall clock ignored); after reboot wall time, never below [lastTrusted]. */
    fun trusted(anchorWall: Long, anchorElapsed: Long, elapsedNow: Long, wallNow: Long, lastTrusted: Long, rebooted: Boolean): Long =
        if (rebooted) maxOf(wallNow, lastTrusted)
        else maxOf(anchorWall + (elapsedNow - anchorElapsed), lastTrusted)
}
