package com.jscyril.ebb.engine

import com.jscyril.ebb.guard.ChangeQueue
import com.jscyril.ebb.guard.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaperingTest {
    private val z = ZoneId.of("UTC")
    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(12, 0).atZone(z).toInstant().toEpochMilli()
    private val mon = LocalDate.of(2026, 11, 9).toEpochDay() // a Monday
    private fun rule(cap: Int, t: Taper?) = mapOf("a" to AppRule("a", dailyCapMin = cap, taper = t))

    @Test fun mondayOfAnyDay() = assertEquals(mon, Tapering.monday(at(2026, 11, 15), z))

    @Test fun nothingDueInTheSameWeek() = assertNull(Tapering.step(rule(60, Taper(20, 5, mon)), at(2026, 11, 15), z))

    @Test fun dropsOncePerMonday() {
        val out = Tapering.step(rule(60, Taper(20, 5, mon)), at(2026, 11, 16), z)!!.getValue("a")
        assertEquals(55, out.dailyCapMin)
        assertEquals(mon + 7, out.taper!!.lastMonday)
    }

    @Test fun catchesUpMissedWeeksButStopsAtTarget() {
        assertEquals(45, Tapering.step(rule(60, Taper(20, 5, mon)), at(2026, 11, 30), z)!!.getValue("a").dailyCapMin)
        assertEquals(20, Tapering.step(rule(60, Taper(20, 5, mon)), at(2027, 6, 1), z)!!.getValue("a").dailyCapMin)
    }

    @Test fun neverRaisesALimitAlreadyBelowTarget() =
        assertEquals(10, Tapering.step(rule(10, Taper(20, 5, mon)), at(2026, 11, 16), z)!!.getValue("a").dailyCapMin)

    @Test fun startingATaperIsTightening() =
        assertNull(ChangeQueue.submit(Rules(rule(60, null)), Rules(rule(60, Taper(20, 5, mon))), 0).second)

    @Test fun stoppingOrEasingATaperWaits() {
        val cur = Rules(rule(60, Taper(20, 5, mon)))
        assertNotNull(ChangeQueue.submit(cur, Rules(rule(60, null)), 0).second)
        assertNotNull(ChangeQueue.submit(cur, Rules(rule(60, Taper(30, 5, mon))), 0).second)
        assertNotNull(ChangeQueue.submit(cur, Rules(rule(60, Taper(20, 1, mon))), 0).second)
        assertNotNull(ChangeQueue.submit(cur, Rules(rule(60, Taper(20, 5, mon + 7))), 0).second) // postpones the next drop
    }

    @Test fun lowerTargetOrBiggerStepSavesNow() =
        assertNull(ChangeQueue.submit(Rules(rule(60, Taper(20, 5, mon))), Rules(rule(60, Taper(10, 10, mon))), 0).second)
}
