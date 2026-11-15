package com.jscyril.ebb.engine

import kotlinx.serialization.Serializable

@Serializable
data class Tier(val sessionMin: Int = 0, val lockMin: Int = 0) {
    /** The engine ignores tiers failing this; guard comparisons use the same rule. */
    val isValid get() = sessionMin > 0 && lockMin >= 0
}
@Serializable
data class AppRule(
    val pkg: String = "",
    val tiers: List<Tier> = Defaults.TIERS,
    val dailyCapMin: Int = Defaults.DAILY_CAP_MIN,
    /** Websites sharing this app's limits; null = the defaults in [Sites]. */
    val domains: List<String>? = null,
    /** The app's name when it was added, so an uninstalled app still reads well (its limits stay). */
    val name: String? = null,
    /** Grey the screen for the last [GREY_LEAD_MS] of a session. */
    val grayscale: Boolean = false,
    /** Lowers [dailyCapMin] every Monday toward a target; see [Tapering]. */
    val taper: Taper? = null,
) {
    companion object { const val GREY_LEAD_MS = 30_000L }
}
/** Drop the daily limit by [stepMin] each Monday until [targetMin]; [lastMonday] (epoch day) is when it last stepped. */
@Serializable
data class Taper(val targetMin: Int = 0, val stepMin: Int = 5, val lastMonday: Long = 0)

@Serializable
data class AppState(
    val dayKey: Int = 0,
    val usedTodaySec: Int = 0,
    val sessionEndsAt: Long? = null,
    val sessionLockMin: Int? = null,
    val lockedUntil: Long? = null,
    val emergencyUntil: Long? = null,
)

enum class Reason { COOLDOWN, DAILY_CAP }

sealed interface Decision {
    data class Allow(val until: Long) : Decision
    data class Prompt(val options: List<Tier>) : Decision
    data class Blocked(val until: Long, val reason: Reason) : Decision
}

object Defaults {
    val TIERS = listOf(Tier(5, 60), Tier(15, 120), Tier(30, 240), Tier(60, 480))
    const val DAILY_CAP_MIN = 60
    const val PASSES_PER_MONTH = 2
}
