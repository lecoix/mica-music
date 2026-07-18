package com.mica.music.ui.screens

import com.mica.music.data.PlayerLowerComponent
import com.mica.music.data.PlayerLowerLayoutConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPlayerLowerPanelTest {
    @Test
    fun fitScaleKeepsConfiguredLayoutInsidePanel() {
        val config = PlayerLowerLayoutConfig(
            scalePercents = PlayerLowerComponent.entries.associateWith { 200 },
            spacingDp = 24,
            topPaddingDp = 120,
            bottomPaddingDp = 120,
        )
        val fit = customPlayerLayoutMetrics(
            panelHeightDp = 420f,
            coverBaseHeightDp = 360f,
            config = config,
        ).fitScale
        val visible = config.order.filter(config::isVisible)
        val desired = visible.sumOf {
            customPlayerBaseHeightDp(it, config.lyricsLineCount, 360f).toDouble() *
                config.scalePercentOf(it) / 100.0
        }.toFloat() +
            config.spacingDp * (visible.size - 1) +
            config.topPaddingDp +
            config.bottomPaddingDp

        assertTrue(fit < 1f)
        assertTrue(desired * fit <= 420.001f)
    }

    @Test
    fun singleLineLyricsUsesShorterLayoutHeight() {
        val single = customPlayerBaseHeightDp(
            PlayerLowerComponent.LYRICS,
            PlayerLowerLayoutConfig.SINGLE_LYRICS_LINE_COUNT,
        )
        val three = customPlayerBaseHeightDp(
            PlayerLowerComponent.LYRICS,
            PlayerLowerLayoutConfig.THREE_LYRICS_LINE_COUNT,
        )

        assertEquals(48f, single, 0.0001f)
        assertEquals(112f, three, 0.0001f)
        assertTrue(single < three)
    }

    @Test
    fun fitScaleDoesNotShrinkLayoutThatAlreadyFits() {
        val config = PlayerLowerLayoutConfig.Default.copy(
            hidden = PlayerLowerComponent.entries.filterNot { it == PlayerLowerComponent.TITLE }.toSet(),
            scalePercents = mapOf(PlayerLowerComponent.TITLE to 200),
        )

        assertEquals(
            1f,
            customPlayerLayoutMetrics(420f, 360f, config).fitScale,
            0.0001f,
        )
    }

    @Test
    fun coverPlacementFollowsConfiguredOrder() {
        val config = PlayerLowerLayoutConfig.Default
            .move(PlayerLowerComponent.COVER, 1)
            .copy(topPaddingDp = 10, spacingDp = 8)
        val metrics = customPlayerLayoutMetrics(
            panelHeightDp = 2_000f,
            coverBaseHeightDp = 360f,
            config = config,
        )

        assertEquals(1f, metrics.fitScale, 0.0001f)
        assertEquals(42f, metrics.coverTopDp ?: -1f, 0.0001f)
        assertEquals(1f, metrics.coverVisualScale, 0.0001f)
    }

    @Test
    fun hiddenCoverHasNoPlacement() {
        val config = PlayerLowerLayoutConfig.Default.withVisibility(PlayerLowerComponent.COVER, false)

        val metrics = customPlayerLayoutMetrics(
            panelHeightDp = 800f,
            coverBaseHeightDp = 360f,
            config = config,
        )

        assertEquals(null, metrics.coverTopDp)
    }

    @Test
    fun dynamicCoverHeightParticipatesInFitScale() {
        val onlyCover = PlayerLowerLayoutConfig.Default.copy(
            hidden = PlayerLowerComponent.entries.filterNot { it == PlayerLowerComponent.COVER }.toSet(),
            topPaddingDp = 0,
            bottomPaddingDp = 0,
            scalePercents = mapOf(PlayerLowerComponent.COVER to 200),
        )

        val metrics = customPlayerLayoutMetrics(
            panelHeightDp = 360f,
            coverBaseHeightDp = 360f,
            config = onlyCover,
        )

        assertEquals(0.5f, metrics.fitScale, 0.0001f)
        assertEquals(1f, metrics.coverVisualScale, 0.0001f)
    }
}
