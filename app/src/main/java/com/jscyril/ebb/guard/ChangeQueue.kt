package com.jscyril.ebb.guard

import com.jscyril.ebb.engine.AppRule
import com.jscyril.ebb.engine.Sites
import com.jscyril.ebb.engine.Defaults
import kotlinx.serialization.Serializable

@Serializable
data class Rules(
    val apps: Map<String, AppRule> = emptyMap(),
    val passesPerMonth: Int = Defaults.PASSES_PER_MONTH,
    /** One daily budget shared by all watched apps, on top of each app's own limit; null = off. */
    val sharedCapMin: Int? = null,
) {
    /** The day's total limit: the summed app limits, or the shared limit when lower. */
    val totalCapMin: Int get() = apps.values.sumOf { it.dailyCapMin.coerceAtLeast(0) }.let { s -> sharedCapMin?.let { minOf(s, it) } ?: s }
}
@Serializable
data class Pending(val newRules: Rules = Rules(), val effectiveAt: Long = Long.MAX_VALUE)

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
            (cur.sharedCapMin != null && (new.sharedCapMin == null || new.sharedCapMin > cur.sharedCapMin)) ||
            cur.apps.any { (pkg, old) -> new.apps[pkg]?.let { appLooser(old, it) } ?: true }

    private fun appLooser(old: AppRule, new: AppRule): Boolean {
        if (new.pkg != old.pkg || new.dailyCapMin > old.dailyCapMin) return true
        if (old.grayscale && !new.grayscale) return true
        old.taper?.let { ot ->
            val nt = new.taper ?: return true
            if (nt.targetMin > ot.targetMin || nt.stepMin < ot.stepMin || nt.lastMonday > ot.lastMonday) return true
        }
        if (!Sites.of(new).containsAll(Sites.of(old))) return true // a site stops being covered
        if (new.tiers.any { !it.isValid }) return true
        val oldTiers = old.tiers.filter { it.isValid }
        val shortest = oldTiers.minOfOrNull { it.sessionMin }
        return new.tiers.any { t ->
            val same = oldTiers.filter { it.sessionMin == t.sessionMin }
            when {
                same.isNotEmpty() -> t.lockMin < same.maxOf { it.lockMin }
                shortest == null || t.sessionMin > shortest -> true
                else -> t.lockMin < oldTiers.filter { it.sessionMin == shortest }.maxOf { it.lockMin }
            }
        }
    }
}

enum class MatureAction { Apply, Drop, Keep }

/** What to do with an existing pending change: a pledge drops it (defence in depth), otherwise apply once due. */
fun matureAction(pledgeActive: Boolean, matured: Boolean): MatureAction = when {
    pledgeActive -> MatureAction.Drop
    matured -> MatureAction.Apply
    else -> MatureAction.Keep
}
