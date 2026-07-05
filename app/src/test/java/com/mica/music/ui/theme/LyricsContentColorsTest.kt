package com.mica.music.ui.theme

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
}
