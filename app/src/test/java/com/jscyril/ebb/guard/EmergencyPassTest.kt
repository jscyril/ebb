package com.jscyril.ebb.guard

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class EmergencyPassTest {
    private val z = ZoneId.of("UTC")
    private fun ms(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 12, 0, 0, 0, z).toInstant().toEpochMilli()

    @Test fun countsOnlyThisMonth() {
        val used = listOf(ms(2026, 10, 31), ms(2026, 11, 2), ms(2025, 11, 5))
        assertEquals(1, EmergencyPass.remaining(used, 2, ms(2026, 11, 14), z))
    }

    @Test fun neverNegative() =
        assertEquals(0, EmergencyPass.remaining(listOf(ms(2026, 11, 1), ms(2026, 11, 2), ms(2026, 11, 3)), 2, ms(2026, 11, 14), z))
}
