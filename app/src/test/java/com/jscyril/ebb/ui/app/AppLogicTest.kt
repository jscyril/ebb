package com.jscyril.ebb.ui.app

import com.jscyril.ebb.engine.AppRule
import com.jscyril.ebb.engine.AppState
import com.jscyril.ebb.engine.SessionEngine
import com.jscyril.ebb.engine.Tier
import com.jscyril.ebb.guard.MatureAction
import com.jscyril.ebb.guard.matureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AppLogicTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 11, 14, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val today = SessionEngine.dayKey(now, zone)
    private val rule = AppRule("a", listOf(Tier(5, 60), Tier(15, 120)), 60)
    private val min = 60_000L

    @Test fun openShowsUsedOfCap() =
        assertEquals(AppStatus.Open(12, 60), appStatus(rule, AppState(dayKey = today, usedTodaySec = 12 * 60 + 30), now, zone))

    @Test fun usageFromYesterdayCountsZero() =
        assertEquals(AppStatus.Open(0, 60), appStatus(rule, AppState(dayKey = today - 1, usedTodaySec = 3000), now, zone))

    @Test fun inSession() =
        assertEquals(AppStatus.InSession(now + 5 * min), appStatus(rule, AppState(sessionEndsAt = now + 5 * min, sessionLockMin = 60), now, zone))

    @Test fun emergencyWindowCountsAsSession() =
        assertEquals(AppStatus.InSession(now + 3 * min), appStatus(rule, AppState(emergencyUntil = now + 3 * min, lockedUntil = now + 60 * min), now, zone))

    @Test fun lockedUntil() =
        assertEquals(AppStatus.Locked(now + 40 * min), appStatus(rule, AppState(lockedUntil = now + 40 * min), now, zone))

    @Test fun expiredSessionImposesItsLock() =
        assertEquals(AppStatus.Locked(now + 59 * min), appStatus(rule, AppState(sessionEndsAt = now - min, sessionLockMin = 60), now, zone))

    @Test fun capReached() =
        assertEquals(AppStatus.CapReached, appStatus(rule, AppState(dayKey = today, usedTodaySec = 58 * 60), now, zone))

    @Test fun validTiersHaveNoErrors() = assertEquals(emptyList<String>(), validateTiers(rule.tiers))

    @Test fun emptyTiers() = assertEquals(listOf("empty"), validateTiers(emptyList()))

    @Test fun duplicateSession() = assertEquals(listOf("duplicate_session"), validateTiers(listOf(Tier(5, 60), Tier(5, 120))))

    @Test fun sessionRange() {
        assertEquals(listOf("session_range"), validateTiers(listOf(Tier(0, 60))))
        assertEquals(listOf("session_range"), validateTiers(listOf(Tier(241, 60))))
        assertEquals(emptyList<String>(), validateTiers(listOf(Tier(1, 0), Tier(240, 1440))))
    }

    @Test fun lockRange() {
        assertEquals(listOf("lock_range"), validateTiers(listOf(Tier(5, -1))))
        assertEquals(listOf("lock_range"), validateTiers(listOf(Tier(5, 1441))))
    }

    @Test fun errorsAreDistinctAndCombined() =
        assertEquals(setOf("duplicate_session", "lock_range"), validateTiers(listOf(Tier(5, 2000), Tier(5, 3000), Tier(5, 60))).toSet())

    @Test fun disablePhrase() {
        assertTrue(disablePhraseMatches("I am choosing to turn off Ebb"))
        assertTrue(disablePhraseMatches("  i am CHOOSING to turn off ebb \n"))
        assertFalse(disablePhraseMatches("I am choosing to turn off"))
        assertFalse(disablePhraseMatches(""))
    }

    @Test fun groupedPasscode() {
        assertEquals("ABCD EFGH JKLM NPQR STUV WXYZ", formatGroupedPasscode("ABCDEFGHJKLMNPQRSTUVWXYZ"))
        assertEquals("ABCD EF", formatGroupedPasscode("ABCDEF"))
        assertEquals("", formatGroupedPasscode(""))
    }

    @Test fun passcodeAttempts() {
        assertTrue(passcodeAttemptAllowed(null, 1_000))
        assertFalse(passcodeAttemptAllowed(1_000, 1_000))
        assertFalse(passcodeAttemptAllowed(1_000, 30_999))
        assertTrue(passcodeAttemptAllowed(1_000, 31_000))
        assertFalse(passcodeAttemptAllowed(50_000, 10_000)) // time going backwards never opens the gate
    }

    @Test fun stepperSnapsToStepAndClamps() {
        assertEquals(5, stepUp(1, 5, 240))
        assertEquals(10, stepUp(5, 5, 240))
        assertEquals(10, stepUp(7, 5, 240))
        assertEquals(240, stepUp(240, 5, 240))
        assertEquals(5, stepDown(7, 5, 1))
        assertEquals(5, stepDown(10, 5, 1))
        assertEquals(1, stepDown(5, 5, 1))
        assertEquals(1, stepDown(1, 5, 1))
    }

    @Test fun submitDuringPledgeOnlyIfTightening() {
        assertTrue(allowSubmit(pendingWouldBeCreated = false, pledgeActive = true))
        assertFalse(allowSubmit(pendingWouldBeCreated = true, pledgeActive = true))
        assertTrue(allowSubmit(pendingWouldBeCreated = true, pledgeActive = false))
        assertTrue(allowSubmit(pendingWouldBeCreated = false, pledgeActive = false))
    }

    @Test fun pendingIsDroppedDuringPledge() {
        assertEquals(MatureAction.Drop, matureAction(pledgeActive = true, matured = true))
        assertEquals(MatureAction.Drop, matureAction(pledgeActive = true, matured = false))
        assertEquals(MatureAction.Apply, matureAction(pledgeActive = false, matured = true))
        assertEquals(MatureAction.Keep, matureAction(pledgeActive = false, matured = false))
    }
}
