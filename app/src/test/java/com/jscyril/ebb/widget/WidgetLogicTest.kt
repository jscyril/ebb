package com.jscyril.ebb.widget

import com.jscyril.ebb.engine.AppState
import com.jscyril.ebb.ui.app.AppStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLogicTest {
    private val now = 1_000_000L
    private val midnight = 9_000_000L

    @Test fun earliestFutureWins() = assertEquals(
        now + 10,
        nextChangeAt(listOf(AppState(sessionEndsAt = now + 500), AppState(lockedUntil = now + 10), AppState(emergencyUntil = now + 99)), now, midnight),
    )

    @Test fun pastAndNowAreIgnored() = assertEquals(
        now + 7,
        nextChangeAt(listOf(AppState(sessionEndsAt = now - 1, lockedUntil = now, emergencyUntil = now + 7)), now, midnight),
    )

    @Test fun midnightIsTheFallback() = assertEquals(midnight, nextChangeAt(listOf(AppState(lockedUntil = now - 5)), now, midnight))

    @Test fun emptyIsMidnight() = assertEquals(midnight, nextChangeAt(emptyList(), now, midnight))

    @Test fun nullFieldsAreSkipped() = assertEquals(now + 3, nextChangeAt(listOf(AppState(), AppState(sessionEndsAt = now + 3)), now, midnight))

    @Test fun laterThanMidnightLosesToMidnight() = assertEquals(midnight, nextChangeAt(listOf(AppState(lockedUntil = midnight + 1)), now, midnight))

    @Test fun sortsByStatusThenLabel() {
        val rows = listOf(
            WidgetRow("o", "beta", AppStatus.Open(1, 60)),
            WidgetRow("c", "Cap", AppStatus.CapReached),
            WidgetRow("l", "Lock", AppStatus.Locked(5)),
            WidgetRow("s2", "zed", AppStatus.InSession(9)),
            WidgetRow("s1", "Alpha", AppStatus.InSession(3)),
            WidgetRow("o2", "Able", AppStatus.Open(0, 60)),
        )
        assertEquals(listOf("s1", "s2", "l", "c", "o2", "o"), sortRows(rows).map { it.pkg })
    }

    @Test fun sweepClampsAndScales() {
        assertEquals(0f, sweepDegrees(-0.5f), 0f)
        assertEquals(0f, sweepDegrees(0f), 0f)
        assertEquals(90f, sweepDegrees(0.25f), 0.001f)
        assertEquals(360f, sweepDegrees(1f), 0f)
        assertEquals(360f, sweepDegrees(3f), 0f)
        assertEquals(0f, sweepDegrees(Float.NaN), 0f)
    }
}
