package com.mica.music.ui.system

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusBarInsetsTest {

    @Test
    fun animatedStatusBarRevealNeverShrinksTopPaddingBelowFixedHeight() {
        val fixedHeight = 24.dp

        listOf(0, 1, 6, 12, 18, 24).forEach { animatedInsetDp ->
            assertEquals(
                fixedHeight,
                stableStatusBarTopPadding(animatedInsetDp.dp, fixedHeight),
            )
        }
    }

    @Test
    fun tallerVisibleInsetStillWinsForCutouts() {
        assertEquals(40.dp, stableStatusBarTopPadding(insetTop = 40.dp, fixedHeight = 24.dp))
    }
}
