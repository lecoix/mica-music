package com.mica.music.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLowerBackgroundModeTest {

    @Test
    fun standardCoverEdgeProgress_supportsStarMap() {
        assertTrue(PlayerLowerBackgroundMode.THEME.supportsStandardCoverEdgeProgress)
        assertTrue(PlayerLowerBackgroundMode.COVER_GLOW.supportsStandardCoverEdgeProgress)
        assertTrue(PlayerLowerBackgroundMode.DYNAMIC_LIGHT.supportsStandardCoverEdgeProgress)
        assertTrue(PlayerLowerBackgroundMode.DYNAMIC_ARTWORK.supportsStandardCoverEdgeProgress)
        assertTrue(PlayerLowerBackgroundMode.STAR_MAP.supportsStandardCoverEdgeProgress)
        assertFalse(PlayerLowerBackgroundMode.ARTWORK_GRADIENT.supportsStandardCoverEdgeProgress)
    }
}
