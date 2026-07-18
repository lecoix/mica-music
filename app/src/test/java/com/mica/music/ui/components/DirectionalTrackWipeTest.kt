package com.mica.music.ui.components

import com.mica.music.data.TrackSkipDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionalTrackWipeTest {
    @Test
    fun nextAtQuarterProgressShowsRightQuarterOfIncomingAndLeftRemainderOfOutgoing() {
        assertEquals(
            TrackWipeHorizontalBounds(left = 75f, right = 100f),
            trackWipeHorizontalBounds(
                width = 100f,
                progress = 0.25f,
                direction = TrackSkipDirection.TO_NEXT,
                incoming = true,
            ),
        )
        assertEquals(
            TrackWipeHorizontalBounds(left = 0f, right = 75f),
            trackWipeHorizontalBounds(
                width = 100f,
                progress = 0.25f,
                direction = TrackSkipDirection.TO_NEXT,
                incoming = false,
            ),
        )
    }

    @Test
    fun previousAtQuarterProgressShowsLeftQuarterOfIncomingAndRightRemainderOfOutgoing() {
        assertEquals(
            TrackWipeHorizontalBounds(left = 0f, right = 25f),
            trackWipeHorizontalBounds(
                width = 100f,
                progress = 0.25f,
                direction = TrackSkipDirection.TO_PREVIOUS,
                incoming = true,
            ),
        )
        assertEquals(
            TrackWipeHorizontalBounds(left = 25f, right = 100f),
            trackWipeHorizontalBounds(
                width = 100f,
                progress = 0.25f,
                direction = TrackSkipDirection.TO_PREVIOUS,
                incoming = false,
            ),
        )
    }
}
