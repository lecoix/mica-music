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
        val fit = customLowerFitScale(panelHeightDp = 420f, config = config)
        val visible = config.order.filter(config::isVisible)
        val desired = visible.sumOf {
            customLowerBaseHeightDp(it).toDouble() * config.scalePercentOf(it) / 100.0
        }.toFloat() +
            config.spacingDp * (visible.size - 1) +
            config.topPaddingDp +
            config.bottomPaddingDp

        assertTrue(fit < 1f)
        assertTrue(desired * fit <= 420.001f)
    }

    @Test
    fun fitScaleDoesNotShrinkLayoutThatAlreadyFits() {
        val config = PlayerLowerLayoutConfig.Default.copy(
            hidden = PlayerLowerComponent.entries.filterNot { it == PlayerLowerComponent.TITLE }.toSet(),
            scalePercents = mapOf(PlayerLowerComponent.TITLE to 200),
        )

        assertEquals(1f, customLowerFitScale(420f, config), 0.0001f)
    }
}
