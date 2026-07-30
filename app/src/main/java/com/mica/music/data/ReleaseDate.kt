package com.mica.music.data

import java.time.LocalDate
import java.time.format.DateTimeParseException

object ReleaseDates {
    private val fullDatePattern = Regex("""\d{4}-\d{2}-\d{2}""")

    /** Returns a canonical full date only when the whole tag is a real yyyy-MM-dd date. */
    fun canonicalFullDate(raw: String?): String {
        val candidate = raw?.trim().orEmpty()
        if (!fullDatePattern.matches(candidate)) return ""
        return try {
            LocalDate.parse(candidate).toString()
        } catch (_: DateTimeParseException) {
            ""
        }
    }

    fun yearFromFullDate(fullDate: String): Int =
        canonicalFullDate(fullDate).take(4).toIntOrNull() ?: 0

    fun displayLabel(year: Int, fullDate: String): String =
        fullDate.ifBlank {
            year.takeIf { it > 0 }?.toString().orEmpty()
        }

    fun earliestFullDate(songs: List<Song>): String =
        songs.asSequence()
            .map { it.releaseDate }
            .filter { it.isNotEmpty() }
            .minOrNull()
            .orEmpty()

    fun aggregateYear(songs: List<Song>, fullDate: String = earliestFullDate(songs)): Int =
        yearFromFullDate(fullDate).takeIf { it > 0 }
            ?: songs.asSequence().map { it.year }.filter { it > 0 }.minOrNull()
            ?: 0

    /**
     * Compares mixed-precision values. Unknown values are always last; within one year, full
     * dates are chronological and precede a year-only value in both directions.
     */
    fun compare(
        leftYear: Int,
        leftFullDate: String,
        rightYear: Int,
        rightFullDate: String,
        direction: SortDirection,
    ): Int {
        // Scanner/persistence owns validation. Keep this hot comparator allocation- and parse-free.
        val leftDate = leftFullDate
        val rightDate = rightFullDate
        val leftEffectiveYear = fastCanonicalYear(leftDate).takeIf { it > 0 } ?: leftYear
        val rightEffectiveYear = fastCanonicalYear(rightDate).takeIf { it > 0 } ?: rightYear
        val leftUnknown = leftEffectiveYear <= 0
        val rightUnknown = rightEffectiveYear <= 0
        if (leftUnknown || rightUnknown) {
            return when {
                leftUnknown && rightUnknown -> 0
                leftUnknown -> 1
                else -> -1
            }
        }

        if (leftEffectiveYear != rightEffectiveYear) {
            val chronological = leftEffectiveYear.compareTo(rightEffectiveYear)
            return if (direction == SortDirection.ASC) chronological else -chronological
        }
        if (leftDate.isNotEmpty() != rightDate.isNotEmpty()) {
            return if (leftDate.isNotEmpty()) -1 else 1
        }
        if (leftDate != rightDate) {
            val chronological = leftDate.compareTo(rightDate)
            return if (direction == SortDirection.ASC) chronological else -chronological
        }
        return 0
    }

    private fun fastCanonicalYear(fullDate: String): Int {
        if (fullDate.length != 10) return 0
        val a = fullDate[0].digitToIntOrNull() ?: return 0
        val b = fullDate[1].digitToIntOrNull() ?: return 0
        val c = fullDate[2].digitToIntOrNull() ?: return 0
        val d = fullDate[3].digitToIntOrNull() ?: return 0
        return a * 1_000 + b * 100 + c * 10 + d
    }
}
