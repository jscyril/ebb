package com.jscyril.ebb.ui.overlay

// Pure overlay helpers (no Android), unit-tested in OverlayTextTest.

/** What the emergency flow asks the user to type. Not localised: matching must stay exact. */
const val EMERGENCY_PHRASE = "I need this right now"

/** "1:05:09" from 1 h, "12:04" from 1 min, else "0:09". Rounds up so a live lock never reads 0:00; never negative. */
fun formatRemaining(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun phraseMatches(input: String): Boolean = input.trim().equals(EMERGENCY_PHRASE, ignoreCase = true)

/** Fraction of [start]..[end] elapsed at [now], clamped to 0..1; an empty span counts as done. */
fun ringProgress(now: Long, start: Long, end: Long): Float =
    if (end <= start) 1f else ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
