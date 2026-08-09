package com.mica.music.ui.screens

import androidx.compose.ui.geometry.Offset
import com.mica.music.data.PlayerLowerComponent
import com.mica.music.data.PlayerLowerElementOffset
import com.mica.music.data.PlayerLowerLayoutConfig
import com.mica.music.ui.theme.CustomPlayerInfoRowHeightDp
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
        assertEquals(10f + CustomPlayerInfoRowHeightDp + 8f, metrics.coverTopDp ?: -1f, 0.0001f)
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

    @Test
    fun transformStoresNormalizedOffsetAndScale() {
        val updated = updateCustomPlayerElementTransform(
            config = PlayerLowerLayoutConfig.Default,
            component = PlayerLowerComponent.TITLE,
            pan = Offset(100f, -50f),
            zoom = 1.25f,
            panelWidthPx = 1_000f,
            panelHeightPx = 500f,
        )

        assertEquals(100, updated.offsetOf(PlayerLowerComponent.TITLE).xPermille)
        assertEquals(-100, updated.offsetOf(PlayerLowerComponent.TITLE).yPermille)
        assertEquals(125, updated.scalePercentOf(PlayerLowerComponent.TITLE))
    }

    @Test
    fun repeatedSmallPanAccumulatesAcrossWeakenedSnapZone() {
        var config = PlayerLowerLayoutConfig.Default
        repeat(20) {
            config = updateCustomPlayerElementTransform(
                config = config,
                component = PlayerLowerComponent.LYRICS,
                pan = Offset(2f, 0f),
                zoom = 1f,
                panelWidthPx = 1_000f,
                panelHeightPx = 1_000f,
            )
        }

        assertEquals(40, config.offsetOf(PlayerLowerComponent.LYRICS).xPermille)
        assertEquals(40, effectiveCustomPlayerOffset(config.offsetOf(PlayerLowerComponent.LYRICS)).xPermille)
    }

    @Test
    fun originalPositionSnapIsFivePermille() {
        assertEquals(0, snapCustomPlayerAxis(5))
        assertEquals(6, snapCustomPlayerAxis(6))
        assertEquals(0, snapCustomPlayerAxis(-5))
        assertEquals(-6, snapCustomPlayerAxis(-6))
    }

    @Test
    fun saveSnapOnlyClearsAxesInsideFivePermille() {
        val config = PlayerLowerLayoutConfig.Default.copy(
            elementOffsets = mapOf(
                PlayerLowerComponent.TITLE to PlayerLowerElementOffset(5, -6),
            ),
        )

        val snapped = snapCustomPlayerLayoutOffsets(config)

        assertEquals(0, snapped.offsetOf(PlayerLowerComponent.TITLE).xPermille)
        assertEquals(-6, snapped.offsetOf(PlayerLowerComponent.TITLE).yPermille)
    }

    @Test
    fun freeformScaleKeepsFollowingComponentsOnStableBaseline() {
        val config = PlayerLowerLayoutConfig.Default.copy(
            freeformEnabled = true,
            scalePercents = mapOf(PlayerLowerComponent.COVER to 150),
        )
        val visible = config.order.filter(config::isVisible)

        val coverCompensation = customPlayerFreeformFlowCompensationDp(
            PlayerLowerComponent.COVER,
            visible,
            config,
            coverBaseHeightDp = 300f,
            fitScale = 1f,
        )
        val infoCompensation = customPlayerFreeformFlowCompensationDp(
            PlayerLowerComponent.INFO,
            visible,
            config,
            coverBaseHeightDp = 300f,
            fitScale = 1f,
        )

        assertEquals(-75f, coverCompensation, 0.0001f)
        assertEquals(-150f, infoCompensation, 0.0001f)
    }

    @Test
    fun transformKeepsElementCenterInsideEditableCanvas() {
        val updated = updateCustomPlayerElementTransform(
            config = PlayerLowerLayoutConfig.Default.copy(freeformEnabled = true),
            component = PlayerLowerComponent.CONTROLS,
            pan = Offset(5_000f, 5_000f),
            zoom = 1f,
            panelWidthPx = 1_000f,
            panelHeightPx = 1_000f,
            minYPermille = -850,
            maxYPermille = 100,
        )

        assertEquals(500, updated.offsetOf(PlayerLowerComponent.CONTROLS).xPermille)
        assertEquals(100, updated.offsetOf(PlayerLowerComponent.CONTROLS).yPermille)
    }

    @Test
    fun freeformScaleChangesSizeWithoutMovingBaselineCenter() {
        val base = PlayerLowerLayoutConfig.Default.copy(
            freeformEnabled = true,
            topPaddingDp = 10,
        )
        val scaled = base.withScalePercent(PlayerLowerComponent.COVER, 150)
        val visible = base.order.filter(base::isVisible)

        val baseCenter = customPlayerFreeformBaselineCenterDp(
            PlayerLowerComponent.COVER,
            visible,
            base,
            coverBaseHeightDp = 300f,
            fitScale = 1f,
        )
        val scaledCenter = customPlayerFreeformBaselineCenterDp(
            PlayerLowerComponent.COVER,
            visible,
            scaled,
            coverBaseHeightDp = 300f,
            fitScale = 1f,
        )

        assertEquals(160f, baseCenter, 0.0001f)
        assertEquals(baseCenter, scaledCenter, 0.0001f)
    }
}
