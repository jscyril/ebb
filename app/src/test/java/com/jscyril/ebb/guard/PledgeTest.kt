package com.jscyril.ebb.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PledgeTest {
    @Test fun createVerifyRoundTrip() {
        val (r, code) = Pledge.create(30, 1_000L)
        assertEquals(24, code.length)
        assertEquals(1_000L + 30 * 86_400_000L, r.endsAt)
        assertTrue(Pledge.verify(r, code))
        val bad = (if (code[0] == 'A') 'B' else 'A') + code.substring(1)
        assertFalse(Pledge.verify(r, bad))
    }

    @Test fun verifyNormalisesGroupsAndCase() {
        val (r, code) = Pledge.create(7, 0L)
        assertTrue(Pledge.verify(r, " " + code.chunked(4).joinToString("-").lowercase() + " "))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOtherDays() { Pledge.create(8, 0L) }

    @Test fun isActive() {
        val (r, _) = Pledge.create(7, 0L)
        assertTrue(Pledge.isActive(r, r.endsAt - 1))
        assertFalse(Pledge.isActive(r, r.endsAt))
        assertFalse(Pledge.isActive(null, 0L))
    }
}
