package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerLowerLayoutConfigTest {
    @Test
    fun normalizedRepairsOrderAndClampsSpacing() {
        val normalized = PlayerLowerLayoutConfig(
            order = listOf(PlayerLowerComponent.TITLE, PlayerLowerComponent.TITLE),
            hidden = setOf(PlayerLowerComponent.INFO),
            scalePercents = mapOf(PlayerLowerComponent.TITLE to 999),
            spacingDp = 99,
            topPaddingDp = 999,
            bottomPaddingDp = -1,
        ).normalized()

        assertEquals(PlayerLowerComponent.entries.size, normalized.order.size)
        assertEquals(PlayerLowerComponent.TITLE, normalized.order.first())
        assertEquals(PlayerLowerLayoutConfig.MAX_SPACING_DP, normalized.spacingDp)
        assertEquals(PlayerLowerLayoutConfig.MAX_SCALE_PERCENT, normalized.scalePercentOf(PlayerLowerComponent.TITLE))
        assertEquals(PlayerLowerLayoutConfig.MAX_BOUNDARY_PADDING_DP, normalized.topPaddingDp)
        assertEquals(PlayerLowerLayoutConfig.MIN_BOUNDARY_PADDING_DP, normalized.bottomPaddingDp)
        assertFalse(normalized.isVisible(PlayerLowerComponent.INFO))
    }

    @Test
    fun moveStopsAtEdgesAndKeepsEveryComponent() {
        val moved = PlayerLowerLayoutConfig.Default
            .move(PlayerLowerComponent.INFO, -1)
            .move(PlayerLowerComponent.CONTROLS, -1)

        assertEquals(PlayerLowerComponent.INFO, moved.order.first())
        assertEquals(PlayerLowerComponent.CONTROLS, moved.order[moved.order.lastIndex - 1])
        assertEquals(PlayerLowerComponent.entries.toSet(), moved.order.toSet())
    }

    @Test
    fun customThemeUsesHorizontalLyricsAndRejectsImmersiveLower() {
        assertEquals(false, PlayerCoverFlowMode.CUSTOM_STANDARD.supportsImmersiveLower)
        assertEquals(true, PlayerCoverFlowMode.CUSTOM_STANDARD.usesHorizontalLyricsPage)
    }
}
