package com.jscyril.ebb.service

import com.jscyril.ebb.service.ForegroundTracker.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTrackerTest {
    private val t = ForegroundTracker()

    @Test fun watchedToOtherClosesInterval() {
        assertNull(t.onForeground("a", true, 100))
        assertEquals(Interval("a", 100, 500), t.onForeground("launcher", false, 500))
    }

    @Test fun watchedToSameWatchedContinues() {
        t.onForeground("a", true, 100)
        assertNull(t.onForeground("a", true, 300))
        assertEquals(Interval("a", 100, 900), t.onScreenOff(900))
    }

    @Test fun screenOffClosesAndStops() {
        t.onForeground("a", true, 100)
        assertEquals(Interval("a", 100, 200), t.onScreenOff(200))
        assertNull(t.onScreenOff(300))
        assertNull(t.onForeground("launcher", false, 400))
    }

    @Test fun nonWatchedToNonWatchedNull() {
        assertNull(t.onForeground("x", false, 100))
        assertNull(t.onForeground("y", false, 200))
        assertNull(t.onForeground(null, false, 300))
    }

    @Test fun watchedAToWatchedBClosesAStartsB() {
        t.onForeground("a", true, 100)
        assertEquals(Interval("a", 100, 400), t.onForeground("b", true, 400))
        assertEquals(Interval("b", 400, 700), t.onForeground("x", false, 700))
    }

    @Test fun zeroOrNegativeLengthIsNull() {
        t.onForeground("a", true, 100)
        assertNull(t.onScreenOff(100))
        t.onForeground("a", true, 500)
        assertNull(t.onScreenOff(400))
    }
}
