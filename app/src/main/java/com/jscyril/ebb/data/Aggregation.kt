package com.jscyril.ebb.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayTotal(val dayKey: Int, val seconds: Long, val opens: Int, val blocked: Int)
data class AppTotal(val pkg: String, val seconds: Long, val opens: Int, val blocked: Int)

fun dayKeyOf(date: LocalDate) = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
fun dayKeyOf(millis: Long, zone: ZoneId) = dayKeyOf(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

/** Splits [start,end) at local midnights into (dayKey, seconds). Sub-second remainders per segment are dropped. */
fun splitByDay(start: Long, end: Long, zone: ZoneId): List<Pair<Int, Long>> {
    val out = ArrayList<Pair<Int, Long>>()
    var cur = start
    while (cur < end) {
        val date = Instant.ofEpochMilli(cur).atZone(zone).toLocalDate()
        val seg = minOf(end, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        out += dayKeyOf(date) to (seg - cur) / 1000
        cur = seg
    }
    return out
}

/** Seconds per local hour of day, summed over all intervals. */
fun hourlyBuckets(intervals: List<Pair<Long, Long>>, zone: ZoneId): IntArray {
    val h = IntArray(24)
    for ((s, e) in intervals) {
        var cur = s
        while (cur < e) {
            val z = Instant.ofEpochMilli(cur).atZone(zone)
            val seg = minOf(e, z.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli())
            h[z.hour] += ((seg - cur) / 1000).toInt()
            cur = seg
        }
    }
    return h
}

/** The last [days] day keys ending at [todayKey], oldest first, zero rows for missing days. */
@Suppress("UNUSED_PARAMETER")
fun fillDays(rows: List<DayTotal>, todayKey: Int, days: Int, zone: ZoneId): List<DayTotal> {
    val today = LocalDate.of(todayKey / 10000, todayKey / 100 % 100, todayKey % 100)
    val byKey = rows.associateBy { it.dayKey }
    return (days - 1 downTo 0).map { dayKeyOf(today.minusDays(it.toLong())).let { k -> byKey[k] ?: DayTotal(k, 0, 0, 0) } }
}
