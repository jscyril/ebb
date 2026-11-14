package com.jscyril.ebb.ui.app

import com.jscyril.ebb.data.AppTotal
import com.jscyril.ebb.engine.AppRule
import com.jscyril.ebb.engine.Tier
import com.jscyril.ebb.guard.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsightsLogicTest {
    private val today = 20261115

    @Test fun emergencyTodayBreaksStreak() = assertEquals(0, streak(today, setOf(today), 20261101))
    @Test fun emergencyThreeDaysAgo() = assertEquals(3, streak(today, setOf(20261112), 20261101))
    @Test fun noEmergencySinceFirstUseCountsToday() = assertEquals(6, streak(today, emptySet(), 20261110))
    @Test fun noFirstUseIsZero() = assertEquals(0, streak(today, emptySet(), null))
    @Test fun firstUseTodayIsOne() = assertEquals(1, streak(today, emptySet(), today))
    @Test fun crossesMonthBoundary() = assertEquals(4, streak(20261202, setOf(20261128), 20261101))
    @Test fun crossesYearBoundary() = assertEquals(3, streak(20270101, emptySet(), 20261230))

    private val rules = Rules(
        mapOf(
            "a" to AppRule("a", listOf(Tier(5, 60), Tier(15, 120))),
            "b" to AppRule("b", listOf(Tier(5, 90), Tier(30, 240))),
        ),
    )

    @Test fun savedUsesMaxLockPerSessionLength() = assertEquals(90 + 90 + 120 + 240, estimatedSavedMin(listOf(5, 5, 15, 30), rules))
    @Test fun savedIgnoresUnknownLengths() = assertEquals(0, estimatedSavedMin(listOf(7, 0), rules))
    @Test fun savedIgnoresInvalidTiers() =
        assertEquals(0, estimatedSavedMin(listOf(0), Rules(mapOf("a" to AppRule("a", listOf(Tier(0, 60)))))))

    @Test fun peakHourNullWhenEmpty() = assertNull(peakHour(IntArray(24)))
    @Test fun peakHourPicksMax() = assertEquals(22, peakHour(IntArray(24).also { it[9] = 5; it[22] = 40 }))

    @Test fun splitHoursMinutes() {
        assertEquals(1L to 12, hoursMinutes(72 * 60 + 59))
        assertEquals(0L to 42, hoursMinutes(42 * 60))
        assertEquals(0L to 0, hoursMinutes(0))
        assertEquals(0L to 0, hoursMinutes(-5))
    }

    private fun t(pkg: String, s: Long) = AppTotal(pkg, s, 0, 0)

    @Test fun othersRowNullWhenFits() {
        val l = listOf(t("a", 3), t("b", 2))
        assertEquals(l to null, othersRow(l, 2))
    }

    @Test fun othersRowSortsAndSumsRest() =
        assertEquals(listOf(t("c", 9), t("a", 5)) to 3L, othersRow(listOf(t("a", 5), t("b", 2), t("c", 9), t("d", 1)), 2))
}
