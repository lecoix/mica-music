package com.mica.music.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class LandscapeClassicLeftColumnTest {
    @Test
    fun coverSizeRespectsMaxLaneAndSlotBounds() {
        assertEquals(200.dp, resolveLandscapeClassicCoverSize(200.dp, 280.dp, 240.dp))
        assertEquals(180.dp, resolveLandscapeClassicCoverSize(200.dp, 180.dp, 240.dp))
        assertEquals(160.dp, resolveLandscapeClassicCoverSize(200.dp, 280.dp, 160.dp))
    }

    @Test
    fun coverSizeNeverExceedsShorterSlotDimension() {
        val cover = resolveLandscapeClassicCoverSize(275.dp, 275.dp, 88.dp)
        assertEquals(88.dp, cover)
    }
}
