package com.jscyril.ebb.widget

import com.jscyril.ebb.engine.AppState
import com.jscyril.ebb.ui.app.AppStatus

// Pure widget logic (no Android), unit-tested in WidgetLogicTest.

/** When any widget text next goes stale: the earliest future session end, lock end or emergency end, else [nextMidnight]. */
fun nextChangeAt(states: Collection<AppState>, now: Long, nextMidnight: Long): Long =
    states.asSequence()
        .flatMap { sequenceOf(it.sessionEndsAt, it.lockedUntil, it.emergencyUntil) }
        .filterNotNull().filter { it > now }
        .fold(nextMidnight, ::minOf)

data class WidgetRow(val pkg: String, val label: String, val status: AppStatus)

private fun rank(s: AppStatus) = when (s) {
    is AppStatus.InSession -> 0
    is AppStatus.Locked -> 1
    AppStatus.CapReached -> 2
    is AppStatus.Open -> 3
}

/** What matters right now first (running session, then locks), then by label. */
fun sortRows(rows: List<WidgetRow>): List<WidgetRow> = rows.sortedWith(compareBy({ rank(it.status) }, { it.label.lowercase() }))

/** Arc sweep for a progress [fraction], clamped to 0..360 (NaN reads as empty). */
fun sweepDegrees(fraction: Float): Float = if (fraction.isNaN()) 0f else 360f * fraction.coerceIn(0f, 1f)
