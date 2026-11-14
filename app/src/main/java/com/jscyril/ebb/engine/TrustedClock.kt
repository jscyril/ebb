package com.jscyril.ebb.engine

object TrustedClock {
    // shortcut: a wall-clock change made while the service is not running, then a reboot, can still jump time; close with Device Owner
    /**
     * Elapsed-realtime based while the boot is unchanged (wall clock ignored). After a reboot, wall time minus the
     * last saved [skew] (wall - trusted), so a clock change the service saw before the reboot is undone; never below [lastTrusted].
     */
    fun trusted(anchorWall: Long, anchorElapsed: Long, elapsedNow: Long, wallNow: Long, lastTrusted: Long, rebooted: Boolean, skew: Long = 0): Long =
        if (rebooted) maxOf(wallNow - skew, lastTrusted)
        else maxOf(anchorWall + (elapsedNow - anchorElapsed), lastTrusted)
}
