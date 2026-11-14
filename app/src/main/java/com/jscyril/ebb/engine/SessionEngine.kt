package com.jscyril.ebb.engine

import java.time.Instant
import java.time.ZoneId

object SessionEngine {
    private const val MIN = 60_000L
    private const val EMERGENCY_MIN = 10

    fun dayKey(now: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }

    private fun nextMidnight(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun usedToday(s: AppState, now: Long, zone: ZoneId) = if (s.dayKey == dayKey(now, zone)) s.usedTodaySec else 0

    fun decide(rule: AppRule, s: AppState, now: Long, zone: ZoneId): Decision {
        s.emergencyUntil?.takeIf { now < it }?.let { return Decision.Allow(it) }
        s.sessionEndsAt?.takeIf { now < it }?.let { return Decision.Allow(it) }
        // An expired but unprocessed session (missed alarm) still imposes its lockout.
        val expiredLock = s.sessionEndsAt?.let { it + (s.sessionLockMin ?: 0).coerceAtLeast(0) * MIN }
        val lock = listOfNotNull(s.lockedUntil, expiredLock).maxOrNull()
        if (lock != null && lock > now) return Decision.Blocked(lock, Reason.COOLDOWN)
        val remaining = rule.dailyCapMin.coerceAtLeast(0) * 60 - usedToday(s, now, zone)
        val options = rule.tiers.filter { it.sessionMin > 0 && it.lockMin >= 0 && it.sessionMin * 60L <= remaining }
            .sortedBy { it.sessionMin }
        return if (options.isEmpty()) Decision.Blocked(nextMidnight(now, zone), Reason.DAILY_CAP) else Decision.Prompt(options)
    }

    fun startSession(s: AppState, tier: Tier, now: Long) =
        s.copy(sessionEndsAt = now + tier.sessionMin * MIN, sessionLockMin = tier.lockMin)

    fun endSession(s: AppState, now: Long): AppState {
        val end = s.sessionEndsAt ?: return s
        return s.copy(sessionEndsAt = null, sessionLockMin = null, lockedUntil = end + (s.sessionLockMin ?: 0).coerceAtLeast(0) * MIN)
    }

    fun addForeground(s: AppState, deltaSec: Int, now: Long, zone: ZoneId): AppState =
        s.copy(dayKey = dayKey(now, zone), usedTodaySec = usedToday(s, now, zone) + deltaSec.coerceAtLeast(0))

    fun nextAlarmAt(rule: AppRule, s: AppState, now: Long, zone: ZoneId): Long? {
        s.emergencyUntil?.takeIf { now < it }?.let { return it } // decide ignores cap/session during emergency
        val end = s.sessionEndsAt?.takeIf { now < it } ?: return null
        val remainingSec = (rule.dailyCapMin.coerceAtLeast(0) * 60 - usedToday(s, now, zone)).coerceAtLeast(0)
        // usage resets at midnight, so the cap alarm never lands past it
        return minOf(end, now + remainingSec * 1000L, nextMidnight(now, zone))
    }

    fun startEmergency(s: AppState, now: Long) = s.copy(emergencyUntil = now + EMERGENCY_MIN * MIN)
}
