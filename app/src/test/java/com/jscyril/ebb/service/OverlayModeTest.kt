package com.jscyril.ebb.service

import com.jscyril.ebb.service.OverlayMode.FULL
import com.jscyril.ebb.service.OverlayMode.WINDOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayModeTest {
    private val own = "com.jscyril.ebb"
    private val watched = setOf("a")
    private fun mode(active: String?, visible: String?, current: OverlayMode?) = overlayMode(active, own, visible, watched, current)

    @Test fun watchedActiveIsFull() = assertEquals(FULL, mode("a", "a", WINDOW))
    @Test fun loopCaseOwnActiveKeepsFull() = assertEquals(FULL, mode(own, "a", FULL))
    @Test fun unknownActiveKeepsFull() = assertEquals(FULL, mode(null, "a", FULL))
    @Test fun launcherActiveDowngradesToWindow() = assertEquals(WINDOW, mode("launcher", "a", FULL))
    @Test fun noOverlayOtherActiveIsWindow() = assertEquals(WINDOW, mode("launcher", "a", null))
    @Test fun ownActiveKeepsWindow() = assertEquals(WINDOW, mode(own, "a", WINDOW))
    @Test fun nothingVisibleIsNull() = assertNull(mode("launcher", null, FULL))
}
