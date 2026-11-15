package com.jscyril.ebb.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Weekly step-down of an app's daily limit toward a target. Pure; applied wherever pending rule changes mature. */
object Tapering {
    /** This week's Monday as an epoch day. */
    fun monday(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()

    /** Applies every drop due since each taper last stepped (catching up missed weeks); null when nothing is due. */
    fun step(apps: Map<String, AppRule>, now: Long, zone: ZoneId): Map<String, AppRule>? {
        if (apps.values.none { it.taper != null }) return null
        val mon = monday(now, zone)
        var changed = false
        val out = apps.mapValues { (_, r) ->
            val t = r.taper ?: return@mapValues r
            val weeks = (mon - t.lastMonday) / 7
            if (weeks <= 0) return@mapValues r
            changed = true
            val cap = if (r.dailyCapMin <= t.targetMin) r.dailyCapMin
            else maxOf(t.targetMin, r.dailyCapMin - (t.stepMin.toLong() * weeks).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            r.copy(dailyCapMin = cap, taper = t.copy(lastMonday = mon))
        }
        return if (changed) out else null
    }
}
