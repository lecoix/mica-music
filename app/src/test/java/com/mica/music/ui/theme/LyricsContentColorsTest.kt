package com.mica.music.ui.theme

import androidx.compose.ui.graphics.Color
import com.mica.music.data.PlaybackContentColorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LyricsContentColorsTest {

    @Test
    fun resolveLyricsContentColors_autoUsesBackgroundDerivedColors() {
        val auto = darkPlayerContentColors()
        val resolved = resolvePlaybackContentColors(auto, PlaybackContentColorMode.AUTO)
        assertSame(auto, resolved)
    }

    @Test
    fun readableTextColors_usesDarkOnLightCoverAndLightOnDarkCover() {
        assertEquals(
            darkPlayerContentColors(),
            PlayerBackgroundBlend.readableTextColors(Color.White),
        )
        assertEquals(
            lightPlayerContentColors(),
            PlayerBackgroundBlend.readableTextColors(Color.Black),
        )
    }

    @Test
    fun resolveLyricsContentColors_lightAndDarkUseFixedPalettes() {
        val auto = lightPlayerContentColors()
        assertEquals(
            lightPlayerContentColors(),
            resolvePlaybackContentColors(auto, PlaybackContentColorMode.LIGHT),
        )
        assertEquals(
            darkPlayerContentColors(),
            resolvePlaybackContentColors(auto, PlaybackContentColorMode.DARK),
        )
    }

    @Test
    fun removedReferenceModeStorageMigratesToStableDynamicMode() {
        assertEquals(
            PlaybackContentColorMode.DYNAMIC,
            PlaybackContentColorMode.fromStorage("dynamic_reference"),
        )
    }

    @Test
    fun resolveLyricsContentColors_dynamicUsesArtworkDerivedPalette() {
        val auto = lightPlayerContentColors().copy(
            dynamicColors = PlayerBackgroundBlend.dynamicTextColors(
                coverColor = androidx.compose.ui.graphics.Color(0xFF6E4CA8),
                surface = androidx.compose.ui.graphics.Color(0xFF191722),
                isDark = true,
            ),
        )

        assertEquals(
            checkNotNull(auto.dynamicColors),
            resolvePlaybackContentColors(auto, PlaybackContentColorMode.DYNAMIC),
        )
    }

}
