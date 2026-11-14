package com.jscyril.ebb.ui.app

import com.jscyril.ebb.engine.AppRule
import com.jscyril.ebb.engine.AppState
import com.jscyril.ebb.engine.Decision
import com.jscyril.ebb.engine.Reason
import com.jscyril.ebb.engine.SessionEngine
import com.jscyril.ebb.engine.Tier
import java.time.ZoneId

// Pure app-screen logic (no Android), unit-tested in AppLogicTest.

sealed class AppStatus {
    data class Open(val usedMin: Int, val capMin: Int) : AppStatus()
    data class InSession(val until: Long) : AppStatus()
    data class Locked(val until: Long) : AppStatus()
    data object CapReached : AppStatus()
}

/** What the Home list shows for one watched app; the same decision the service would make now. */
fun appStatus(rule: AppRule, s: AppState, now: Long, zone: ZoneId): AppStatus = when (val d = SessionEngine.decide(rule, s, now, zone)) {
    is Decision.Allow -> AppStatus.InSession(d.until)
    is Decision.Blocked -> if (d.reason == Reason.DAILY_CAP) AppStatus.CapReached else AppStatus.Locked(d.until)
    is Decision.Prompt -> AppStatus.Open(
        if (s.dayKey == SessionEngine.dayKey(now, zone)) s.usedTodaySec / 60 else 0, rule.dailyCapMin,
    )
}

const val SESSION_MAX = 240
const val LOCK_MAX = 1440

/** Error keys for a tier list; empty means it can be saved. */
fun validateTiers(tiers: List<Tier>): List<String> = buildList {
    if (tiers.isEmpty()) add("empty")
    if (tiers.map { it.sessionMin }.toSet().size != tiers.size) add("duplicate_session")
    if (tiers.any { it.sessionMin !in 1..SESSION_MAX }) add("session_range")
    if (tiers.any { it.lockMin !in 0..LOCK_MAX }) add("lock_range")
}

/** What the final "turn off protection" step asks the user to type. Not localised: matching must stay exact. */
const val DISABLE_PHRASE = "I am choosing to turn off Ebb"

fun disablePhraseMatches(input: String): Boolean = input.trim().equals(DISABLE_PHRASE, ignoreCase = true)

/** "ABCD EFGH …" so a 24-char passcode can be read aloud or copied by hand. */
fun formatGroupedPasscode(code: String): String = code.chunked(4).joinToString(" ")

const val PASSCODE_RETRY_MS = 30_000L

/** One passcode try per 30 s after a failure, to slow guessing. [now] should be monotonic (elapsed realtime). */
fun passcodeAttemptAllowed(lastFailAt: Long?, now: Long): Boolean = lastFailAt == null || now >= lastFailAt + PASSCODE_RETRY_MS

/** Next stepper value: up to the next multiple of [step], clamped to [max]. */
fun stepUp(v: Int, step: Int, max: Int): Int = minOf(max, (v / step + 1) * step)

/** Previous stepper value: down to the previous multiple of [step], clamped to [min]. */
fun stepDown(v: Int, step: Int, min: Int): Int = maxOf(min, if (v % step == 0) v - step else v / step * step)

/** During a pledge only tightening may be submitted: queuing loosenings (e.g. removing every app) would amount to disabling. */
fun allowSubmit(pendingWouldBeCreated: Boolean, pledgeActive: Boolean): Boolean = !(pendingWouldBeCreated && pledgeActive)

enum class MatureAction { Apply, Drop, Keep }

/** What to do with an existing pending change: a pledge drops it (defence in depth), otherwise apply once due. */
fun matureAction(pledgeActive: Boolean, matured: Boolean): MatureAction = when {
    pledgeActive -> MatureAction.Drop
    matured -> MatureAction.Apply
    else -> MatureAction.Keep
}
