package com.jscyril.ebb.guard

import com.jscyril.ebb.engine.Decision
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

object EmergencyPass {
    fun remaining(usedAt: List<Long>, perMonth: Int, now: Long, zone: ZoneId): Int {
        fun ym(t: Long) = YearMonth.from(Instant.ofEpochMilli(t).atZone(zone))
        val cur = ym(now)
        return maxOf(0, perMonth - usedAt.count { ym(it) == cur })
    }

    /** A pass may be spent only while the app is actually locked and one is left. */
    fun mayUse(d: Decision, passesLeft: Int) = d is Decision.Blocked && passesLeft > 0
}
