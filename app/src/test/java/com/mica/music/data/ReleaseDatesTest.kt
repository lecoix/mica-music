package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseDatesTest {
    @Test
    fun acceptsOnlyStrictRealFullDates() {
        assertEquals("2024-02-29", ReleaseDates.canonicalFullDate("2024-02-29"))
        assertEquals("", ReleaseDates.canonicalFullDate("2024-02-30"))
        assertEquals("", ReleaseDates.canonicalFullDate("2024-2-09"))
        assertEquals("", ReleaseDates.canonicalFullDate("2024"))
        assertEquals("", ReleaseDates.canonicalFullDate("2024-02-29T00:00:00"))
    }

    @Test
    fun albumAggregationPrefersEarliestFullDateThenFallsBackToEarliestYear() {
        val dated = listOf(
            SongFixtures.song("year").copy(year = 1990, releaseDate = ""),
            SongFixtures.song("late").copy(year = 2024, releaseDate = "2024-08-16"),
            SongFixtures.song("early").copy(year = 2024, releaseDate = "2024-01-05"),
        )
        val yearOnly = listOf(
            SongFixtures.song("late-year").copy(year = 2024, releaseDate = ""),
            SongFixtures.song("early-year").copy(year = 1990, releaseDate = ""),
        )

        assertEquals("2024-01-05", ReleaseDates.earliestFullDate(dated))
        assertEquals(2024, ReleaseDates.aggregateYear(dated))
        assertEquals("", ReleaseDates.earliestFullDate(yearOnly))
        assertEquals(1990, ReleaseDates.aggregateYear(yearOnly))
    }
}
