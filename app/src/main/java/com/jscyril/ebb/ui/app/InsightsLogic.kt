package com.jscyril.ebb.ui.app

import com.jscyril.ebb.data.AppTotal
import com.jscyril.ebb.data.dateOfKey
import com.jscyril.ebb.data.dayKeyOf
import com.jscyril.ebb.guard.Rules

/**
 * Consecutive days back from [todayKey] (included) with no emergency pass, stopping before [firstUseKey].
 * Day keys are already local dates, so no zone is needed.
 */
fun streak(todayKey: Int, emergencyDays: Set<Int>, firstUseKey: Int?): Int {
    if (firstUseKey == null) return 0
    var d = dateOfKey(todayKey)
    var n = 0
    while (dayKeyOf(d).let { it >= firstUseKey && it !in emergencyDays }) { n++; d = d.minusDays(1) }
    return n
}

/** Lockout minutes the sessions triggered: each plannedMin maps to the longest lock any watched app pairs with it; unknown lengths add 0. */
fun estimatedSavedMin(sessionPlannedMins: List<Int>, rules: Rules): Int {
    val lockFor = HashMap<Int, Int>()
    for (t in rules.apps.values.flatMap { it.tiers }) if (t.isValid) lockFor[t.sessionMin] = maxOf(lockFor[t.sessionMin] ?: 0, t.lockMin)
    return sessionPlannedMins.sumOf { lockFor[it] ?: 0 }
}

/** Hour of day with the most seconds, or null when all are zero. */
fun peakHour(hourly: IntArray): Int? = hourly.indices.maxByOrNull { hourly[it] }?.takeIf { hourly[it] > 0 }

/** Whole hours and leftover minutes of [seconds]; the UI adds the localised units ("1h 12m"). */
fun hoursMinutes(seconds: Long): Pair<Long, Int> = (seconds.coerceAtLeast(0) / 60).let { it / 60 to (it % 60).toInt() }

/** The top [max] apps by time, plus the summed seconds of the rest (null when nothing is left over). */
fun othersRow(list: List<AppTotal>, max: Int): Pair<List<AppTotal>, Long?> {
    val sorted = list.sortedByDescending { it.seconds }
    return if (sorted.size <= max) sorted to null else sorted.take(max) to sorted.drop(max).sumOf { it.seconds }
}
