package com.jscyril.ebb.guard

import com.jscyril.ebb.engine.AppRule
import com.jscyril.ebb.engine.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangeQueueTest {
    private val h24 = 86_400_000L
    private val now = 1_000L
    private fun rules(tiers: List<Tier> = listOf(Tier(15, 120)), cap: Int = 60, passes: Int = 2, extra: Boolean = false): Rules {
        val m = mutableMapOf("a" to AppRule("a", tiers, cap))
        if (extra) m["b"] = AppRule("b", tiers, cap)
        return Rules(m, passes)
    }
    private val base = rules(extra = true)

    private fun tighter(p: Rules) { val (c, pend) = ChangeQueue.submit(base, p, now); assertEquals(p, c); assertNull(pend) }
    private fun looser(p: Rules) { val (c, pend) = ChangeQueue.submit(base, p, now); assertEquals(base, c); assertEquals(Pending(p, now + h24), pend) }

    @Test fun identicalImmediate() = tighter(base)
    @Test fun shorterSessionImmediate() = tighter(rules(listOf(Tier(10, 120)), extra = true))
    @Test fun longerLockoutImmediate() = tighter(rules(listOf(Tier(15, 180)), extra = true))
    @Test fun lowerCapImmediate() = tighter(rules(cap = 30, extra = true))
    @Test fun fewerPassesImmediate() = tighter(rules(passes = 1, extra = true))
    @Test fun removedTierImmediate() = tighter(base.copy(apps = base.apps.mapValues { it.value.copy(tiers = listOf(Tier(15, 120))) }))

    @Test fun longerSessionPending() = looser(rules(listOf(Tier(30, 120)), extra = true))
    @Test fun shorterLockoutPending() = looser(rules(listOf(Tier(15, 60)), extra = true))
    @Test fun higherCapPending() = looser(rules(cap = 90, extra = true))
    @Test fun removedAppPending() = looser(rules())
    @Test fun morePassesPending() = looser(rules(passes = 3, extra = true))

    @Test fun addedAppImmediate() {
        val cur = rules()
        val p = rules(extra = true)
        assertEquals(p to null, ChangeQueue.submit(cur, p, now))
    }

    @Test fun mixedGoesPending() = looser(rules(listOf(Tier(15, 180)), cap = 90, extra = true))

    @Test fun newPendingReplacesOld() {
        val p = rules(cap = 90, extra = true)
        assertEquals(Pending(p, now + h24), ChangeQueue.submit(base, p, now).second)
    }

    @Test fun matureOnlyAfterEffective() {
        val p = Pending(rules(cap = 90), 5_000L)
        assertEquals(base to p, ChangeQueue.mature(base, p, 4_999L))
        assertEquals(p.newRules to null, ChangeQueue.mature(base, p, 5_000L))
        assertEquals(base to null, ChangeQueue.mature(base, null, 9_999L))
    }
}
