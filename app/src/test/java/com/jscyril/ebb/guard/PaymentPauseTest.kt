package com.jscyril.ebb.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PaymentPauseTest {
    private val z = ZoneId.of("Asia/Kolkata")
    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0).atZone(z).toInstant().toEpochMilli()

    @Test fun fullAllowanceWithNoUses() = assertEquals(PaymentPause.PER_DAY, PaymentPause.remaining(emptyList(), at(15, 12), z))

    @Test fun onlyTodaysUsesCount() =
        assertEquals(PaymentPause.PER_DAY - 1, PaymentPause.remaining(listOf(at(14, 23), at(15, 9)), at(15, 12), z))

    @Test fun neverNegative() = assertEquals(0, PaymentPause.remaining(List(5) { at(15, it) }, at(15, 12), z))

    @Test fun pausedOnlyBeforeItsEnd() {
        assertTrue(PaymentPause.isPaused(1_000, 999))
        assertFalse(PaymentPause.isPaused(1_000, 1_000))
        assertFalse(PaymentPause.isPaused(null, 0))
    }
}
