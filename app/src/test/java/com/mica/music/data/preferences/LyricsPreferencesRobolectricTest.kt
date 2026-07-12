package com.mica.music.data.preferences

import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.LyricsPageTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class LyricsPreferencesRobolectricTest {

    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun playerPageTextColorModeDefaultsToAutoAndRoundTrips() {
        assertEquals(PlaybackContentColorMode.AUTO, LyricsPreferences.playerPageTextColorMode(context))

        LyricsPreferences.setPlayerPageTextColorMode(context, PlaybackContentColorMode.LIGHT)
        assertEquals(PlaybackContentColorMode.LIGHT, LyricsPreferences.playerPageTextColorMode(context))
    }

    @Test
    fun lyricsPageTextColorModeDefaultsToAutoAndRoundTrips() {
        assertEquals(PlaybackContentColorMode.AUTO, LyricsPreferences.lyricsPageTextColorMode(context))

        LyricsPreferences.setLyricsPageTextColorMode(context, PlaybackContentColorMode.LIGHT)
        assertEquals(PlaybackContentColorMode.LIGHT, LyricsPreferences.lyricsPageTextColorMode(context))

        LyricsPreferences.setLyricsPageTextColorMode(context, PlaybackContentColorMode.DARK)
        assertEquals(PlaybackContentColorMode.DARK, LyricsPreferences.lyricsPageTextColorMode(context))
    }

    @Test
    fun lyricsPageThemeDefaultsToListAndRoundTrips() {
        assertEquals(LyricsPageTheme.LIST, LyricsPreferences.lyricsPageTheme(context))

        LyricsPreferences.setLyricsPageTheme(context, LyricsPageTheme.CLOUD)

        assertEquals(LyricsPageTheme.CLOUD, LyricsPreferences.lyricsPageTheme(context))
    }

    @Test
    fun lyricsPageTranslationFontSizeDefaultsToOriginalAndRoundTrips() {
        context.getSharedPreferences("mica_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("lyrics_page_font_size", "large")
            .commit()

        assertEquals(22, LyricsPreferences.lyricsPageFontSizeSp(context))
        assertEquals(22, LyricsPreferences.lyricsPageTranslationFontSizeSp(context))

        LyricsPreferences.setLyricsPageTranslationFontSizeSp(context, 14)

        assertEquals(22, LyricsPreferences.lyricsPageFontSizeSp(context))
        assertEquals(14, LyricsPreferences.lyricsPageTranslationFontSizeSp(context))
    }

    @Test
    fun lyricsPageLineSpacingDefaultsToTwentyFourAndRoundTrips() {
        assertEquals(24, LyricsPreferences.lyricsPageLineSpacingDp(context))

        LyricsPreferences.setLyricsPageLineSpacingDp(context, 36)

        assertEquals(36, LyricsPreferences.lyricsPageLineSpacingDp(context))
    }
}
