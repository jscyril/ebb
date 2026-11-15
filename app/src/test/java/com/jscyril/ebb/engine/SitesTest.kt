package com.jscyril.ebb.engine

import com.jscyril.ebb.guard.ChangeQueue
import com.jscyril.ebb.guard.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SitesTest {
    private val ig = AppRule("com.instagram.android")
    private val yt = AppRule("com.google.android.youtube")
    private val ytm = AppRule("com.google.android.apps.youtube.music")

    @Test fun hostFromAddressBarText() {
        assertEquals("instagram.com", Sites.host("instagram.com/reels/abc"))
        assertEquals("m.youtube.com", Sites.host("https://m.youtube.com/watch?v=1"))
        assertEquals("instagram.com", Sites.host("https://www.instagram.com"))
        assertEquals("example.com", Sites.host("user@example.com:8080/x"))
        assertNull(Sites.host("cute cats"))
        assertNull(Sites.host(""))
        assertNull(Sites.host("localhost"))
    }

    @Test fun matchesSubdomainsButNotLookalikes() {
        assertTrue(Sites.matches("instagram.com", "instagram.com"))
        assertTrue(Sites.matches("m.instagram.com", "instagram.com"))
        assertFalse(Sites.matches("notinstagram.com", "instagram.com"))
        assertFalse(Sites.matches("instagram.com.evil.io", "instagram.com"))
    }

    @Test fun appForPicksTheCoveringApp() {
        assertEquals(ig.pkg, Sites.appFor("www.instagram.com".removePrefix("www."), listOf(ig, yt)))
        assertEquals(yt.pkg, Sites.appFor("m.youtube.com", listOf(ig, yt)))
        assertNull(Sites.appFor("wikipedia.org", listOf(ig, yt)))
    }

    @Test fun mostSpecificDomainWins() {
        assertEquals(ytm.pkg, Sites.appFor("music.youtube.com", listOf(yt, ytm)))
        assertEquals(yt.pkg, Sites.appFor("youtube.com", listOf(yt, ytm)))
    }

    @Test fun editedListReplacesDefaults() {
        val custom = ig.copy(domains = listOf("instagram.com", "picuki.com"))
        assertEquals(custom.pkg, Sites.appFor("picuki.com", listOf(custom)))
        assertNull(Sites.appFor("instagram.com", listOf(ig.copy(domains = emptyList()))))
        assertNotNull(Sites.of(ig).firstOrNull())
    }

    @Test fun removingASiteIsLooserAddingIsNot() {
        val cur = Rules(mapOf(ig.pkg to ig), 2)
        val removed = cur.copy(apps = mapOf(ig.pkg to ig.copy(domains = emptyList())))
        val added = cur.copy(apps = mapOf(ig.pkg to ig.copy(domains = listOf("instagram.com", "threads.net"))))
        assertNotNull(ChangeQueue.submit(cur, removed, 0L).second)
        assertNull(ChangeQueue.submit(cur, added, 0L).second)
    }
}
