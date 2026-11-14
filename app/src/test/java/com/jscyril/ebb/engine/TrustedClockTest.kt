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

    // Saved at trusted 1_000_000 with the wall clock set a day ahead (skew = day); reboot 500 ms later.
    @Test fun forwardJumpThenRebootDoesNotAdvance() =
        assertEquals(1_000_500L, TrustedClock.trusted(0, 0, 0, 1_000_500 + day, 1_000_000, true, skew = day))

    // Clock set a day back, then a reboot: clamped to lastTrusted whether or not the skew caught the change.
    @Test fun backwardJumpThenRebootClamped() {
        assertEquals(10 * day, TrustedClock.trusted(0, 0, 0, 9 * day, 10 * day, true, skew = 0))
        assertEquals(10 * day, TrustedClock.trusted(0, 0, 0, 8 * day, 10 * day, true, skew = -day))
    }

    @Test fun zeroSkewSameAsBefore() {
        assertEquals(9_000L, TrustedClock.trusted(1_000_000, 0, 0, 9_000, 5_000, true, skew = 0))
        assertEquals(5_000L, TrustedClock.trusted(1_000_000, 0, 0, 1_000, 5_000, true, skew = 0))
        assertEquals(1_000_500L, TrustedClock.trusted(1_000_000, 0, 500, 1_000_500 + day, 0, false, skew = 0))
    }

    @Test fun neverBelowLastTrusted() =
        assertEquals(2_000_000L, TrustedClock.trusted(1_000_000, 0, 500, 0, 2_000_000, false))
}
