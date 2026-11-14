package com.jscyril.ebb.engine

data class Tier(val sessionMin: Int, val lockMin: Int)
data class AppRule(val pkg: String, val tiers: List<Tier>, val dailyCapMin: Int)
data class AppState(
    val dayKey: Int,
    val usedTodaySec: Int,
    val sessionEndsAt: Long?,
    val sessionLockMin: Int?,
    val lockedUntil: Long?,
    val emergencyUntil: Long?,
)

enum class Reason { COOLDOWN, DAILY_CAP }

sealed interface Decision {
    data class Allow(val until: Long) : Decision
    data class Prompt(val options: List<Tier>) : Decision
    data class Blocked(val until: Long, val reason: Reason) : Decision
}
