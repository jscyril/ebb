package com.jscyril.ebb.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TrustedClockTest {
    private val day = 86_400_000L

    @Test fun wallClockBackNotRebootedIgnored() =
        assertEquals(1_000_500L, TrustedClock.trusted(1_000_000, 0, 500, 1_000_500 - day, 0, false))

    @Test fun wallClockForwardNotRebootedIgnored() =
        assertEquals(1_000_500L, TrustedClock.trusted(1_000_000, 0, 500, 1_000_500 + day, 0, false))

    @Test fun rebootClampsToLastTrusted() =
        assertEquals(5_000L, TrustedClock.trusted(1_000_000, 0, 0, 1_000, 5_000, true))

    @Test fun rebootUsesWallWhenAhead() =
        assertEquals(9_000L, TrustedClock.trusted(1_000_000, 0, 0, 9_000, 5_000, true))

    @Test fun neverBelowLastTrusted() =
        assertEquals(2_000_000L, TrustedClock.trusted(1_000_000, 0, 500, 0, 2_000_000, false))
}
