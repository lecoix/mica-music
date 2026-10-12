package com.mica.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLayoutTraceTest {
    @Test
    fun disabledTracingDoesNotReadCoordinatesOrBuildMessages() {
        val trace = LyricsLayoutTrace(enabled = { false }, sink = { error("unexpected log") })
        trace.placed(0) { error("coordinates read while disabled") }
        trace.event { error("message built while disabled") }
        trace.sample({ error("context built while disabled") })
        trace.close()
    }

    @Test
    fun samplesLiveDrawPositionsAtMostEveryQuarterSecondAndKeepsBoundedRows() {
        var now = 0L
        var rootY = 40f
        val lines = mutableListOf<String>()
        val trace = LyricsLayoutTrace(enabled = { true }, clockMs = { now }, sink = lines::add)
        repeat(10_000) { index ->
            trace.placed(index) { placement(index, rootY) }
        }
        trace.sample({ "first=4" })
        assertEquals(1, lines.size)
        assertEquals(LyricsLayoutTrace.MAX_ROWS, Regex("k=").findAll(lines.last()).count())
        assertTrue(lines.last().contains("y=40"))
        rootY = 300f
        now = 249
        trace.sample({ "first=4" })
        assertEquals(1, lines.size)
        now = 250
        trace.sample({ "first=4" })
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("y=300"))
        now = 500
        trace.sample({ "first=4" })
        assertEquals(2, lines.size)
        trace.sample({ "first=4" }, force = true)
        assertEquals(3, lines.size)
    }

    @Test
    fun retiredPageCannotWriteAfterNewPagePublishesItsGeometry() {
        val lines = mutableListOf<String>()
        val old = LyricsLayoutTrace(enabled = { true }, sink = lines::add)
        old.placed(0) { placement(0, 50f) }
        old.close()
        val replacement = LyricsLayoutTrace(enabled = { true }, sink = lines::add)
        replacement.placed(1) { placement(1, 200f) }
        replacement.sample({ "new-page" }, force = true)
        val published = lines.toList()
        // An old placement or follow continuation arrives after the replacement was displayed.
        old.placed(0) { placement(0, 500f) }
        old.sample({ "old-page" }, force = true)
        old.event { "old-follow-end" }
        old.removed(0)
        assertEquals(published, lines)
        assertTrue(lines.last().contains("new-page"))
        replacement.removed(1)
        assertTrue(lines.last().contains("detach index=1"))
    }

    private fun placement(index: Int, y: Float) = LyricsTracePlacement(
        displayIndex = index, keyHash = index, timeMs = index * 1000,
        rootY = y, heightPx = 60, lagPx = 0f,
    )
}
