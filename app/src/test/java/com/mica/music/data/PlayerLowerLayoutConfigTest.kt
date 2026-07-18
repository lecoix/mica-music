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
            lyricsLineCount = 2,
        ).normalized()

        assertEquals(PlayerLowerComponent.entries.size, normalized.order.size)
        assertEquals(PlayerLowerComponent.TITLE, normalized.order.first())
        assertEquals(PlayerLowerLayoutConfig.MAX_SPACING_DP, normalized.spacingDp)
        assertEquals(PlayerLowerLayoutConfig.MAX_SCALE_PERCENT, normalized.scalePercentOf(PlayerLowerComponent.TITLE))
        assertEquals(PlayerLowerLayoutConfig.MAX_BOUNDARY_PADDING_DP, normalized.topPaddingDp)
        assertEquals(PlayerLowerLayoutConfig.MIN_BOUNDARY_PADDING_DP, normalized.bottomPaddingDp)
        assertEquals(PlayerLowerLayoutConfig.THREE_LYRICS_LINE_COUNT, normalized.lyricsLineCount)
        assertFalse(normalized.isVisible(PlayerLowerComponent.INFO))
    }

    @Test
    fun normalizedKeepsSupportedSingleLyricsLineCount() {
        val normalized = PlayerLowerLayoutConfig.Default
            .copy(lyricsLineCount = PlayerLowerLayoutConfig.SINGLE_LYRICS_LINE_COUNT)
            .normalized()

        assertEquals(PlayerLowerLayoutConfig.SINGLE_LYRICS_LINE_COUNT, normalized.lyricsLineCount)
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
    fun defaultCustomLayoutStartsWithVisibleCover() {
        val config = PlayerLowerLayoutConfig.Default

        assertEquals(PlayerLowerComponent.COVER, config.order.first())
        assertEquals(true, config.isVisible(PlayerLowerComponent.COVER))
        assertEquals(PlayerLowerLayoutConfig.DEFAULT_SCALE_PERCENT, config.scalePercentOf(PlayerLowerComponent.COVER))
    }

    @Test
    fun customThemeUsesHorizontalLyricsAndRejectsImmersiveLower() {
        assertEquals(false, PlayerCoverFlowMode.CUSTOM_STANDARD.supportsImmersiveLower)
        assertEquals(true, PlayerCoverFlowMode.CUSTOM_STANDARD.usesHorizontalLyricsPage)
    }
}
