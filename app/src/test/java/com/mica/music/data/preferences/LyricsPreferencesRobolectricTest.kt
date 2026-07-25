package com.mica.music.data.preferences

import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.LyricsPageTheme
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
import com.mica.music.data.LyricsSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun lyricsWordAnimationPresetDefaultsToCurrentLiftAndRoundTrips() {
        assertEquals(
            LyricsWordAnimationPreset.SYLLABLE_LIFT,
            LyricsPreferences.lyricsWordAnimationPreset(context),
        )

        LyricsPreferences.setLyricsWordAnimationPreset(context, LyricsWordAnimationPreset.WORD_STEP)

        assertEquals(
            LyricsWordAnimationPreset.WORD_STEP,
            LyricsPreferences.lyricsWordAnimationPreset(context),
        )
    }

    @Test
    fun lyricsWordAnimationPresetsMapToDistinctRenderingParameters() {
        val actual = LyricsWordAnimationPreset.entries.map {
            Triple(it.usesDiscreteCueFill, it.syllableLiftEnabled, it.wordFadeWidthEm)
        }

        assertEquals(
            listOf(
                Triple(false, false, 0f),
                Triple(true, true, 0f),
                Triple(false, false, 1f),
                Triple(true, false, 0f),
            ),
            actual,
        )
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

    @Test
    fun infoRowLyricsDefaultsOffAndRoundTrips() {
        assertFalse(LyricsPreferences.infoRowLyricsEnabled(context))

        LyricsPreferences.setInfoRowLyricsEnabled(context, true)

        assertTrue(LyricsPreferences.infoRowLyricsEnabled(context))
    }

    @Test
    fun infoRowWordLyricsDefaultsOffAndRoundTrips() {
        assertFalse(LyricsPreferences.infoRowWordLyricsEnabled(context))

        LyricsPreferences.setInfoRowWordLyricsEnabled(context, true)

        assertTrue(LyricsPreferences.infoRowWordLyricsEnabled(context))
    }

    @Test
    fun carBluetoothLyricsDefaultsOffAndRoundTrips() {
        assertFalse(LyricsPreferences.carBluetoothLyricsEnabled(context))

        LyricsPreferences.setCarBluetoothLyricsEnabled(context, true)

        assertTrue(LyricsPreferences.carBluetoothLyricsEnabled(context))
    }

    @Test
    fun lyricsSlotPriorityDefaultsRoundTripsAndRejectsDuplicates() {
        assertEquals(DEFAULT_LYRICS_SLOT_PRIORITY, LyricsPreferences.lyricsSlotPriority(context))
        val embeddedFirst = listOf(
            LyricsSlot.EMBEDDED,
            LyricsSlot.EXTERNAL_TTML,
            LyricsSlot.EXTERNAL_LRC,
        )

        LyricsPreferences.setLyricsSlotPriority(context, embeddedFirst)
        assertEquals(embeddedFirst, LyricsPreferences.lyricsSlotPriority(context))

        LyricsPreferences.setLyricsSlotPriority(
            context,
            listOf(LyricsSlot.EMBEDDED, LyricsSlot.EMBEDDED, LyricsSlot.EXTERNAL_LRC),
        )
        assertEquals(DEFAULT_LYRICS_SLOT_PRIORITY, LyricsPreferences.lyricsSlotPriority(context))
    }
}
