package com.jscyril.ebb.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisibleWatchedTest {
    private val watched = setOf("a", "b")

    @Test fun noWatchedWindowIsNull() = assertNull(visibleWatched(listOf("launcher", "x"), watched, "a"))
    @Test fun emptyIsNull() = assertNull(visibleWatched(emptyList(), watched, null))
    @Test fun findsWatchedAmongOthers() = assertEquals("b", visibleWatched(listOf("x", "b"), watched, null))
    @Test fun prefersCurrent() = assertEquals("b", visibleWatched(listOf("a", "b"), watched, "b"))
    @Test fun currentNotVisibleFallsBack() = assertEquals("a", visibleWatched(listOf("x", "a"), watched, "b"))
    @Test fun currentNotWatchedIgnored() = assertEquals("a", visibleWatched(listOf("x", "a"), watched, "x"))
}
