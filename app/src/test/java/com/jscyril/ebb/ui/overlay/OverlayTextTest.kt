package com.jscyril.ebb.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTextTest {
    private fun ms(h: Int, m: Int, s: Int) = ((h * 60L + m) * 60 + s) * 1000

    @Test fun hoursFormat() = assertEquals("1:05:09", formatRemaining(ms(1, 5, 9)))
    @Test fun minutesFormat() = assertEquals("12:04", formatRemaining(ms(0, 12, 4)))
    @Test fun secondsFormat() = assertEquals("0:09", formatRemaining(ms(0, 0, 9)))
    @Test fun exactlyOneHour() = assertEquals("1:00:00", formatRemaining(ms(1, 0, 0)))
    @Test fun exactlyOneMinute() = assertEquals("1:00", formatRemaining(ms(0, 1, 0)))
    @Test fun partialSecondRoundsUp() = assertEquals("0:01", formatRemaining(1))
    @Test fun neverNegative() {
        assertEquals("0:00", formatRemaining(0))
        assertEquals("0:00", formatRemaining(-5_000))
    }

    @Test fun phraseMatchesIgnoringCaseAndSpace() = assertTrue(phraseMatches("  i need this RIGHT now "))
    @Test fun phraseExact() = assertTrue(phraseMatches("I need this right now"))
    @Test fun phrasePartialFails() = assertFalse(phraseMatches("I need this"))
    @Test fun phraseEmptyFails() = assertFalse(phraseMatches(""))
    @Test fun phraseExtraWordsFail() = assertFalse(phraseMatches("I need this right now please"))

    @Test fun ringMid() = assertEquals(0.25f, ringProgress(now = 150, start = 100, end = 300), 1e-6f)
    @Test fun ringClampsLow() = assertEquals(0f, ringProgress(now = 50, start = 100, end = 300), 0f)
    @Test fun ringClampsHigh() = assertEquals(1f, ringProgress(now = 400, start = 100, end = 300), 0f)
    @Test fun ringEmptySpanIsDone() = assertEquals(1f, ringProgress(now = 100, start = 100, end = 100), 0f)
}
