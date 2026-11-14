package com.jscyril.ebb.guard

import com.jscyril.ebb.engine.AppRule

data class Rules(val apps: Map<String, AppRule>, val passesPerMonth: Int)
data class Pending(val newRules: Rules, val effectiveAt: Long)

object ChangeQueue {
    private const val COOLDOWN_MS = 24 * 3_600_000L

    /** Tightening applies at once; anything looser (or mixed) is queued whole for 24 h. */
    fun submit(current: Rules, proposed: Rules, trustedNow: Long): Pair<Rules, Pending?> =
        if (isLooser(current, proposed)) current to Pending(proposed, trustedNow + COOLDOWN_MS)
        else proposed to null

    fun mature(current: Rules, p: Pending?, trustedNow: Long): Pair<Rules, Pending?> =
        if (p != null && trustedNow >= p.effectiveAt) p.newRules to null else current to p

    private fun isLooser(cur: Rules, new: Rules): Boolean =
        new.passesPerMonth > cur.passesPerMonth ||
            cur.apps.any { (pkg, old) -> new.apps[pkg]?.let { appLooser(old, it) } ?: true }

    private fun appLooser(old: AppRule, new: AppRule): Boolean {
        if (new.dailyCapMin > old.dailyCapMin) return true
        val oldShortest = old.tiers.minByOrNull { it.sessionMin }
        return new.tiers.any { t ->
            val same = old.tiers.firstOrNull { it.sessionMin == t.sessionMin }
            if (same != null) t.lockMin < same.lockMin
            else oldShortest == null || t.sessionMin > oldShortest.sessionMin || t.lockMin < oldShortest.lockMin
        }
    }
}
