package com.jscyril.ebb.guard

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

object EmergencyPass {
    fun remaining(usedAt: List<Long>, perMonth: Int, now: Long, zone: ZoneId): Int {
        fun ym(t: Long) = YearMonth.from(Instant.ofEpochMilli(t).atZone(zone))
        val cur = ym(now)
        return maxOf(0, perMonth - usedAt.count { ym(it) == cur })
    }
}
