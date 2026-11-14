package com.jscyril.ebb.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuppressFullTest {
    @Test fun holdsFullForLaunchingPkg() = assertTrue(suppressFull("a", "a", full = true, nowUptime = 100, untilUptime = 200))
    @Test fun windowOverPipStillShows() = assertFalse(suppressFull("a", "a", full = false, nowUptime = 100, untilUptime = 200))
    @Test fun otherPkgNotHeld() = assertFalse(suppressFull("a", "b", full = true, nowUptime = 100, untilUptime = 200))
    @Test fun nothingLaunching() = assertFalse(suppressFull(null, "a", full = true, nowUptime = 100, untilUptime = 200))
    @Test fun expiresAtDeadline() = assertFalse(suppressFull("a", "a", full = true, nowUptime = 200, untilUptime = 200))
}
