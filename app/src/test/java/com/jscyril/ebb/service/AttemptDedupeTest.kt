package com.jscyril.ebb.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptDedupeTest {
    private val d = AttemptDedupe(3_000)

    @Test fun firstCounts() = assertTrue(d.shouldCount("a", 0))
    @Test fun samePkgWithinWindowSuppressed() { d.shouldCount("a", 0); assertFalse(d.shouldCount("a", 2_999)) }
    @Test fun samePkgAfterWindowCounts() { d.shouldCount("a", 0); assertTrue(d.shouldCount("a", 3_000)) }
    @Test fun otherPkgCounts() { d.shouldCount("a", 0); assertTrue(d.shouldCount("b", 100)) }
    @Test fun suppressedDoesNotExtendWindow() {
        d.shouldCount("a", 0); d.shouldCount("a", 2_000)
        assertTrue(d.shouldCount("a", 3_000))
    }
}
