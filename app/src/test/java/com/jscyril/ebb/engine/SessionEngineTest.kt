package com.jscyril.ebb.engine

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionEngineTest {
    private val z = ZoneId.of("UTC")
    private val tiers = listOf(Tier(5, 60), Tier(15, 120), Tier(30, 240), Tier(60, 480))
    private val rule = AppRule("a.b", tiers, 60)
    private val min = 60_000L
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 14, h, m, 0, 0, z).toInstant().toEpochMilli()
    private val midnight = ZonedDateTime.of(2026, 11, 15, 0, 0, 0, 0, z).toInstant().toEpochMilli()
    private fun fresh(used: Int = 0) = AppState(20261114, used, null, null, null, null)

    @Test fun freshPromptsAllTiers() =
        assertEquals(Decision.Prompt(tiers), SessionEngine.decide(rule, fresh(), at(10), z))

    @Test fun partlyUsedFiltersTiers() =
        assertEquals(Decision.Prompt(tiers.take(2)), SessionEngine.decide(rule, fresh(45 * 60), at(10), z))

    @Test fun capSpentBlocksUntilMidnight() =
        assertEquals(Decision.Blocked(midnight, Reason.DAILY_CAP), SessionEngine.decide(rule, fresh(60 * 60), at(10), z))

    @Test fun cooldownBlocks() {
        val s = fresh().copy(lockedUntil = at(12))
        assertEquals(Decision.Blocked(at(12), Reason.COOLDOWN), SessionEngine.decide(rule, s, at(10), z))
    }

    @Test fun activeSessionAllows() {
        val s = fresh().copy(sessionEndsAt = at(10, 5))
        assertEquals(Decision.Allow(at(10, 5)), SessionEngine.decide(rule, s, at(10), z))
    }

    @Test fun emergencyBeatsLockAndCap() {
        val s = fresh(60 * 60).copy(lockedUntil = at(12), emergencyUntil = at(10, 10))
        assertEquals(Decision.Allow(at(10, 10)), SessionEngine.decide(rule, s, at(10), z))
    }

    @Test fun expiredSessionWithoutEndSessionStillLocks() {
        val s = fresh().copy(sessionEndsAt = at(10), sessionLockMin = 60)
        assertEquals(Decision.Blocked(at(11), Reason.COOLDOWN), SessionEngine.decide(rule, s, at(10, 30), z))
        assertEquals(Decision.Prompt(tiers), SessionEngine.decide(rule, s, at(11, 1), z))
    }

    @Test fun endSessionSetsLock() {
        val s = SessionEngine.startSession(fresh(), Tier(5, 60), at(10))
        assertEquals(at(10, 5), s.sessionEndsAt)
        val e = SessionEngine.endSession(s, at(10, 5))
        assertEquals(at(10, 5) + 60 * min, e.lockedUntil)
        assertNull(e.sessionEndsAt); assertNull(e.sessionLockMin)
        assertEquals(e, SessionEngine.endSession(e, at(11)))
    }

    @Test fun addForegroundRollsDay() {
        val s = SessionEngine.addForeground(fresh(600), 30, midnight + 1000, z)
        assertEquals(20261115, s.dayKey); assertEquals(30, s.usedTodaySec)
        assertEquals(630, SessionEngine.addForeground(fresh(600), 30, at(10), z).usedTodaySec)
    }

    @Test fun sessionContinuesAfterMidnightWithNewDayCap() {
        val s = fresh(60 * 60).copy(sessionEndsAt = midnight + 5 * min, sessionLockMin = 60)
        assertEquals(Decision.Allow(midnight + 5 * min), SessionEngine.decide(rule, s, midnight + min, z))
        // after it ends: cooldown applies, then new-day cap is fresh
        assertEquals(Decision.Blocked(midnight + 65 * min, Reason.COOLDOWN), SessionEngine.decide(rule, s, midnight + 10 * min, z))
        assertEquals(Decision.Prompt(tiers), SessionEngine.decide(rule, s, midnight + 66 * min, z))
    }

    @Test fun badRulesNeverThrow() {
        val bad = listOf(rule.copy(tiers = emptyList()), rule.copy(dailyCapMin = 0), rule.copy(dailyCapMin = -5),
            rule.copy(tiers = listOf(Tier(-1, -1))))
        for (r in bad) assertEquals(Decision.Blocked(midnight, Reason.DAILY_CAP), SessionEngine.decide(r, fresh(), at(10), z))
    }

    @Test fun nextAlarm() {
        assertNull(SessionEngine.nextAlarmAt(rule, fresh(), at(10)))
        val s = fresh(55 * 60).copy(sessionEndsAt = at(10, 30))
        assertEquals(at(10, 5), SessionEngine.nextAlarmAt(rule, s, at(10)))
        assertEquals(at(10, 30), SessionEngine.nextAlarmAt(rule, fresh().copy(sessionEndsAt = at(10, 30)), at(10)))
        assertEquals(at(10, 10), SessionEngine.nextAlarmAt(rule, fresh().copy(emergencyUntil = at(10, 10)), at(10)))
    }

    @Test fun emergencyOnlySetsWindow() {
        val s = fresh(7).copy(lockedUntil = at(12))
        assertEquals(s.copy(emergencyUntil = at(10, 10)), SessionEngine.startEmergency(s, at(10)))
    }
}
