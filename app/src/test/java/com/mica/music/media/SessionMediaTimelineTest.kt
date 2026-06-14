package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class SessionMediaTimelineTest {

    @Test
    fun currentWindowHasDurationAndOtherWindowsArePlaceholders() {
        val timeline = SessionMediaTimeline(
            items = listOf("a", "b", "c").map { MediaItem.Builder().setMediaId(it).build() },
            currentIndex = 1,
            currentDurationUs = 12_000_000,
            currentPositionUs = 3_000_000,
        )
        val current = timeline.getWindow(1, Timeline.Window(), 0)
        val other = timeline.getWindow(0, Timeline.Window(), 0)

        assertEquals(12_000_000, current.durationUs)
        assertEquals(3_000_000, current.positionInFirstPeriodUs)
        assertFalse(current.isPlaceholder)
        assertEquals(C.TIME_UNSET, other.durationUs)
        assertTrue(other.isPlaceholder)
        assertEquals(1, timeline.getIndexOfPeriod(1))
        assertEquals(C.INDEX_UNSET, timeline.getIndexOfPeriod("bad"))
    }
}
