package com.mica.music.data.preferences

import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.LyricsPageTheme
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
import com.mica.music.data.DEFAULT_LETTER_SEAL_OPACITY_PERCENT
import com.mica.music.data.DEFAULT_LETTER_SEAL_ROTATION_DEGREES
import com.mica.music.data.DEFAULT_LETTER_SEAL_SIZE_DP
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
    fun letterSealSettingsDefaultRoundTripAndClamp() {
        assertEquals(null, LyricsPreferences.letterSealCustomImagePath(context))
        assertEquals(DEFAULT_LETTER_SEAL_SIZE_DP, LyricsPreferences.letterSealSizeDp(context))
        assertEquals(
            DEFAULT_LETTER_SEAL_OPACITY_PERCENT,
            LyricsPreferences.letterSealOpacityPercent(context),
        )
        assertEquals(
            DEFAULT_LETTER_SEAL_ROTATION_DEGREES,
            LyricsPreferences.letterSealRotationDegrees(context),
        )

        LyricsPreferences.setLetterSealCustomImagePath(context, "/tmp/seal.png")
        LyricsPreferences.setLetterSealSizeDp(context, 999)
        LyricsPreferences.setLetterSealOpacityPercent(context, -1)
        LyricsPreferences.setLetterSealRotationDegrees(context, 999)

        assertEquals("/tmp/seal.png", LyricsPreferences.letterSealCustomImagePath(context))
        assertEquals(56, LyricsPreferences.letterSealSizeDp(context))
        assertEquals(40, LyricsPreferences.letterSealOpacityPercent(context))
        assertEquals(5, LyricsPreferences.letterSealRotationDegrees(context))
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
    fun externalLyricsDisplayModesDefaultToSharedModeAndRemainIndependent() {
        LyricsPreferences.setLyricsBilingualDisplayMode(
            context,
            LyricsBilingualDisplayMode.TRANSLATION,
        )

        assertEquals(
            LyricsBilingualDisplayMode.TRANSLATION,
            LyricsPreferences.desktopLyricsBilingualDisplayMode(context),
        )
        assertEquals(
            LyricsBilingualDisplayMode.TRANSLATION,
            LyricsPreferences.statusBarLyricsBilingualDisplayMode(context),
        )

        LyricsPreferences.setDesktopLyricsBilingualDisplayMode(
            context,
            LyricsBilingualDisplayMode.ORIGINAL,
        )

        assertEquals(
            LyricsBilingualDisplayMode.ORIGINAL,
            LyricsPreferences.desktopLyricsBilingualDisplayMode(context),
        )
        assertEquals(
            LyricsBilingualDisplayMode.TRANSLATION,
            LyricsPreferences.statusBarLyricsBilingualDisplayMode(context),
        )
    }

    @Test
    fun externalLyricsWordByWordSettingsDefaultOnAndRemainIndependent() {
        assertTrue(LyricsPreferences.desktopLyricsWordByWordEnabled(context))
        assertTrue(LyricsPreferences.statusBarLyricsWordByWordEnabled(context))

        LyricsPreferences.setDesktopLyricsWordByWordEnabled(context, false)

        assertFalse(LyricsPreferences.desktopLyricsWordByWordEnabled(context))
        assertTrue(LyricsPreferences.statusBarLyricsWordByWordEnabled(context))

        LyricsPreferences.setStatusBarLyricsWordByWordEnabled(context, false)

        assertFalse(LyricsPreferences.statusBarLyricsWordByWordEnabled(context))
    }

    @Test
    fun desktopLyricsLockDefaultsToUnlockedAndRoundTrips() {
        assertFalse(LyricsPreferences.desktopLyricsLocked(context))

        LyricsPreferences.setDesktopLyricsLocked(context, true)
        assertTrue(LyricsPreferences.desktopLyricsLocked(context))

        LyricsPreferences.setDesktopLyricsLocked(context, false)
        assertFalse(LyricsPreferences.desktopLyricsLocked(context))
    }

    @Test
    fun statusBarLyricsTextAlignmentDefaultsToCenterAndRoundTrips() {
        assertEquals(
            LyricsPageAlignment.CENTER,
            LyricsPreferences.statusBarLyricsTextAlignment(context),
        )

        LyricsPreferences.setStatusBarLyricsTextAlignment(context, LyricsPageAlignment.START)

        assertEquals(
            LyricsPageAlignment.START,
            LyricsPreferences.statusBarLyricsTextAlignment(context),
        )
    }

    @Test
    fun externalLyricsEffectsDefaultRoundTripAndClamp() {
        assertEquals(100, LyricsPreferences.externalLyricsOpacityPercent(context))
        assertEquals(100, LyricsPreferences.externalLyricsShadowStrengthPercent(context))
        assertEquals(0, LyricsPreferences.externalLyricsGlowStrengthPercent(context))

        LyricsPreferences.setExternalLyricsOpacityPercent(context, -1)
        LyricsPreferences.setExternalLyricsShadowStrengthPercent(context, 62)
        LyricsPreferences.setExternalLyricsGlowStrengthPercent(context, 101)

        assertEquals(0, LyricsPreferences.externalLyricsOpacityPercent(context))
        assertEquals(62, LyricsPreferences.externalLyricsShadowStrengthPercent(context))
        assertEquals(100, LyricsPreferences.externalLyricsGlowStrengthPercent(context))
        assertEquals(0, LyricsPreferences.externalLyricsStyle(context).opacityPercent)
        assertEquals(62, LyricsPreferences.externalLyricsStyle(context).shadowStrengthPercent)
        assertEquals(100, LyricsPreferences.externalLyricsStyle(context).glowStrengthPercent)
    }

    @Test
    fun externalLyricsEffectChangesInvalidateTheLiveDisplay() {
        val changes = mutableListOf<LyricsPreferences.NotificationLyricsChange>()
        val unregister = LyricsPreferences.registerNotificationLyricsChangeListener(context, changes::add)
        try {
            LyricsPreferences.setExternalLyricsOpacityPercent(context, 80)
            LyricsPreferences.setExternalLyricsShadowStrengthPercent(context, 60)
            LyricsPreferences.setExternalLyricsGlowStrengthPercent(context, 40)
        } finally {
            unregister()
        }

        assertEquals(
            listOf(
                LyricsPreferences.NotificationLyricsChange.DISPLAY,
                LyricsPreferences.NotificationLyricsChange.DISPLAY,
                LyricsPreferences.NotificationLyricsChange.DISPLAY,
            ),
            changes,
        )
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
    fun notificationLyricsDefaultsOnAndRoundTrips() {
        assertTrue(LyricsPreferences.notificationLyricsEnabled(context))

        LyricsPreferences.setNotificationLyricsEnabled(context, false)
        assertFalse(LyricsPreferences.notificationLyricsEnabled(context))

        LyricsPreferences.setNotificationLyricsEnabled(context, true)
        assertTrue(LyricsPreferences.notificationLyricsEnabled(context))
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

    @Test
    fun globalLyricsOffsetDefaultsToZeroRoundTripsAndClamps() {
        assertEquals(0, LyricsPreferences.globalLyricsOffsetMs(context))

        LyricsPreferences.setGlobalLyricsOffsetMs(context, 500)
        assertEquals(500, LyricsPreferences.globalLyricsOffsetMs(context))

        LyricsPreferences.setGlobalLyricsOffsetMs(context, 99_000)
        assertEquals(5_000, LyricsPreferences.globalLyricsOffsetMs(context))
    }
}
