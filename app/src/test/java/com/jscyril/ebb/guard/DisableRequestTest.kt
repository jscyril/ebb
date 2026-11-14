package com.jscyril.ebb.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisableRequestTest {
    private val h = 3_600_000L
    private val ended = PledgeRecord(0L, ByteArray(1), ByteArray(1))
    private val now = 1_000 * h

    @Test fun falseDuringPledge() = assertFalse(DisableRequest.canDisable(PledgeRecord(now + 1, ByteArray(1), ByteArray(1)), now - 100 * h, now))
    @Test fun falseWithoutRequest() = assertFalse(DisableRequest.canDisable(ended, null, now))
    @Test fun false47h() = assertFalse(DisableRequest.canDisable(ended, now - 47 * h, now))
    @Test fun true48h() = assertTrue(DisableRequest.canDisable(ended, now - 48 * h, now))
    @Test fun trueNoPledge() = assertTrue(DisableRequest.canDisable(null, now - 48 * h, now))

    @Test fun requestFiledDuringPledgeNeverCounts() {
        val p = PledgeRecord(100 * h, ByteArray(1), ByteArray(1))
        assertFalse(DisableRequest.canDisable(p, 1 * h, 100 * h + 48 * h))
    }
    @Test fun requestAtPledgeEndCounts() {
        val p = PledgeRecord(100 * h, ByteArray(1), ByteArray(1))
        assertTrue(DisableRequest.canDisable(p, 100 * h, 148 * h))
        assertFalse(DisableRequest.canDisable(p, 100 * h, 147 * h))
    }
}
