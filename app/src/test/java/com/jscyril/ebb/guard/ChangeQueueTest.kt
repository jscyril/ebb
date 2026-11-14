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
        val l1 = rules(cap = 90, extra = true)
        val l2 = rules(cap = 120, extra = true)
        val (c1, p1) = ChangeQueue.submit(base, l1, now)
        assertEquals(base, c1); assertEquals(l1, p1!!.newRules)
        val p2 = ChangeQueue.submit(c1, l2, now + 5_000).second!!
        assertEquals(l2, p2.newRules)
        assertEquals(now + 5_000 + h24, p2.effectiveAt)
    }

    @Test fun pkgChangePending() =
        looser(Rules(mapOf("a" to AppRule("x", listOf(Tier(15, 120)), 60), "b" to base.apps.getValue("b")), 2))
    @Test fun invalidOldTierIgnored() {
        val cur = rules(listOf(Tier(20, -5)))
        val p = rules(listOf(Tier(20, 0)))
        assertEquals(Pending(p, now + h24), ChangeQueue.submit(cur, p, now).second)
    }
    @Test fun invalidProposedTierPending() = looser(rules(listOf(Tier(15, 120), Tier(0, 10)), extra = true))
    @Test fun sessionBetweenOldTiersPending() {
        val cur = rules(listOf(Tier(10, 60), Tier(30, 120)))
        assertEquals(Pending(rules(listOf(Tier(20, 500))), now + h24), ChangeQueue.submit(cur, rules(listOf(Tier(20, 500))), now).second)
    }
    @Test fun shorterSessionLowerLockPending() {
        val cur = rules(listOf(Tier(10, 60)))
        assertEquals(Pending(rules(listOf(Tier(5, 30))), now + h24), ChangeQueue.submit(cur, rules(listOf(Tier(5, 30))), now).second)
    }
    @Test fun emptyOldPlusAddedTierPending() {
        val cur = rules(emptyList())
        assertEquals(Pending(rules(), now + h24), ChangeQueue.submit(cur, rules(), now).second)
    }
    @Test fun emptyProposedImmediate() {
        val p = rules(emptyList())
        assertEquals(p to null, ChangeQueue.submit(rules(), p, now))
    }
    @Test fun duplicateOldSessionMinsUseMaxLock() {
        val cur = rules(listOf(Tier(15, 60), Tier(15, 200)))
        assertEquals(rules(listOf(Tier(15, 200))) to null, ChangeQueue.submit(cur, rules(listOf(Tier(15, 200))), now))
        assertEquals(Pending(rules(listOf(Tier(15, 100))), now + h24), ChangeQueue.submit(cur, rules(listOf(Tier(15, 100))), now).second)
    }

    @Test fun matureOnlyAfterEffective() {
        val p = Pending(rules(cap = 90), 5_000L)
        assertEquals(base to p, ChangeQueue.mature(base, p, 4_999L))
        assertEquals(p.newRules to null, ChangeQueue.mature(base, p, 5_000L))
        assertEquals(base to null, ChangeQueue.mature(base, null, 9_999L))
    }
}
