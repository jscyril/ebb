package com.jscyril.ebb.guard

import java.time.Instant
import java.time.ZoneId

/**
 * Payment apps (Google Pay in India) refuse to pay while a sideloaded app's accessibility service is on.
 * A pause switches Ebb's service off briefly; a few a day, and it always comes back on by itself.
 */
object PaymentPause {
    const val PER_DAY = 3
    const val DURATION_MS = 5 * 60_000L

    fun remaining(usedAt: List<Long>, now: Long, zone: ZoneId): Int {
        fun day(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        val today = day(now)
        return maxOf(0, PER_DAY - usedAt.count { day(it) == today })
    }

    fun isPaused(pausedUntil: Long?, now: Long) = pausedUntil != null && now < pausedUntil
}
