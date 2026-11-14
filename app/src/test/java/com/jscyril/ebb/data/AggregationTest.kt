package com.jscyril.ebb.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.jscyril.ebb.engine.AppState
import com.jscyril.ebb.guard.Rules
import java.time.LocalDateTime
import java.time.ZoneId

class AggregationTest {
    private val z = ZoneId.of("UTC")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(z).toInstant().toEpochMilli()

    @Test fun splitsAcrossMidnight() {
        val r = splitByDay(ms(2026, 1, 31, 23, 50), ms(2026, 2, 1, 0, 20), z)
        assertEquals(listOf(20260131 to 600L, 20260201 to 1200L), r)
    }

    @Test fun withinOneDay() =
        assertEquals(listOf(20260110 to 3600L), splitByDay(ms(2026, 1, 10, 1), ms(2026, 1, 10, 2), z))

    @Test fun emptyInterval() = assertEquals(emptyList<Pair<Int, Long>>(), splitByDay(5, 5, z))

    @Test fun hourlySplitsAtBoundary() {
        val h = hourlyBuckets(listOf(ms(2026, 1, 10, 9, 50) to ms(2026, 1, 10, 10, 10)), z)
        assertEquals(600, h[9]); assertEquals(600, h[10]); assertEquals(1200, h.sum())
    }

    @Test fun hourlyWrapsMidnight() {
        val h = hourlyBuckets(listOf(ms(2026, 1, 10, 23, 30) to ms(2026, 1, 11, 0, 30)), z)
        assertArrayEquals(IntArray(24).also { it[23] = 1800; it[0] = 1800 }, h)
    }

    @Test fun fillDaysAcrossMonthOldestFirst() {
        val rows = listOf(DayTotal(20260301, 50, 1, 0), DayTotal(20260228, 10, 2, 3))
        val r = fillDays(rows, 20260302, 4)
        assertEquals(listOf(20260227, 20260228, 20260301, 20260302), r.map { it.dayKey })
        assertEquals(DayTotal(20260227, 0, 0, 0), r[0])
        assertEquals(DayTotal(20260228, 10, 2, 3), r[1])
        assertEquals(DayTotal(20260302, 0, 0, 0), r[3])
    }

    @Test fun bumpsPerKind() {
        val a = ms(2026, 1, 31, 23, 50); val b = ms(2026, 2, 1, 0, 20)
        assertEquals(listOf(Bump(20260131, 600, 0, 0), Bump(20260201, 1200, 0, 0)), bumpsFor(Kind.FOREGROUND, a, b, z))
        assertEquals(listOf(Bump(20260131, 0, 1, 0)), bumpsFor(Kind.SESSION, a, b, z))
        assertEquals(listOf(Bump(20260131, 0, 0, 1)), bumpsFor(Kind.BLOCKED_ATTEMPT, a, b, z))
        assertEquals(emptyList<Bump>(), bumpsFor(Kind.EMERGENCY, a, b, z))
    }

    @Test fun decodeGarbageGivesDefaultAndReports() {
        var seen: String? = null
        assertEquals(AppState(), decodeOr("{nope", AppState.serializer(), AppState()) { seen = it })
        assertEquals("{nope", seen)
        assertEquals(AppState(), decodeOr(null, AppState.serializer(), AppState()))
    }

    @Test fun decodeOldJsonMissingFieldsUsesDefaults() {
        val s = decodeOr("""{"usedTodaySec":7,"future":1}""", AppState.serializer(), AppState(dayKey = 99))
        assertEquals(AppState(usedTodaySec = 7), s)
        assertEquals(Rules(), decodeOr("{}", Rules.serializer(), Rules(mapOf())))
    }
}
