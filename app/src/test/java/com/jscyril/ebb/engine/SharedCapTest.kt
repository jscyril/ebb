package com.jscyril.ebb.engine

import com.jscyril.ebb.guard.ChangeQueue
import com.jscyril.ebb.guard.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SharedCapTest {
    private val z = ZoneId.of("UTC")
    private val now = 1_800_000_000_000L
    private val day = SessionEngine.dayKey(now, z)
    private val apps = mapOf("a" to AppRule("a", dailyCapMin = 60), "b" to AppRule("b", dailyCapMin = 60))
    private fun used(min: Int) = AppState(dayKey = day, usedTodaySec = min * 60)

    @Test fun offLeavesCapsAlone() = assertEquals(apps, SessionEngine.withSharedCap(apps, null, emptyMap(), now, z))

    @Test fun shrinksEachCapToWhatIsLeft() {
        val eff = SessionEngine.withSharedCap(apps, 30, mapOf("a" to used(20)), now, z)
        assertEquals(30, eff.getValue("a").dailyCapMin) // 20 used + 10 left
        assertEquals(10, eff.getValue("b").dailyCapMin)
    }

    @Test fun ownLimitStillAppliesWhenLower() =
        assertEquals(60, SessionEngine.withSharedCap(apps, 500, emptyMap(), now, z).getValue("a").dailyCapMin)

    @Test fun spentBudgetBlocksEveryApp() {
        val eff = SessionEngine.withSharedCap(apps, 30, mapOf("a" to used(30)), now, z)
        assertTrue(SessionEngine.decide(eff.getValue("b"), AppState(), now, z) is Decision.Blocked)
    }

    @Test fun yesterdaysUseDoesNotCount() {
        val old = AppState(dayKey = day - 1, usedTodaySec = 3600)
        assertEquals(30, SessionEngine.withSharedCap(apps, 30, mapOf("a" to old), now, z).getValue("b").dailyCapMin)
    }

    @Test fun turningOnOrLoweringIsTightening() {
        val off = Rules(apps)
        assertNull(ChangeQueue.submit(off, off.copy(sharedCapMin = 60), now).second)
        assertNull(ChangeQueue.submit(off.copy(sharedCapMin = 60), off.copy(sharedCapMin = 30), now).second)
    }

    @Test fun turningOffOrRaisingWaits() {
        val on = Rules(apps, sharedCapMin = 30)
        assertNotNull(ChangeQueue.submit(on, on.copy(sharedCapMin = null), now).second)
        assertNotNull(ChangeQueue.submit(on, on.copy(sharedCapMin = 45), now).second)
    }

    @Test fun totalCapIsTheLowerOfSumAndShared() {
        assertEquals(120, Rules(apps).totalCapMin)
        assertEquals(30, Rules(apps, sharedCapMin = 30).totalCapMin)
    }
}
