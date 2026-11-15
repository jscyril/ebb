package com.jscyril.ebb.data

import com.jscyril.ebb.engine.AppRule
import com.jscyril.ebb.engine.Tier
import com.jscyril.ebb.guard.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RulesBackupTest {
    private val rules = Rules(mapOf("a" to AppRule("a", listOf(Tier(5, 60)), 45, listOf("a.com"), "A", grayscale = true)), 2, sharedCapMin = 90)

    @Test fun roundTrips() = assertEquals(rules, decodeRulesBackup(encodeRulesBackup(rules)))

    @Test fun rejectsGarbage() = assertNull(decodeRulesBackup("not json"))

    @Test fun rejectsEmpty() = assertNull(decodeRulesBackup("""{"apps":{}}"""))

    @Test fun rekeysByPackageAndClamps() {
        val r = decodeRulesBackup("""{"apps":{"wrong":{"pkg":"b","dailyCapMin":99999}},"passesPerMonth":50,"sharedCapMin":0}""")!!
        assertEquals(setOf("b"), r.apps.keys)
        assertEquals(24 * 60, r.apps.getValue("b").dailyCapMin)
        assertEquals(4, r.passesPerMonth)
        assertEquals(1, r.sharedCapMin)
    }
}
