package com.mica.music.media

import com.mica.music.data.LyricCue
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.toLyricsDocumentCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLyricsProjectionTest {

    @Test
    fun statusBarSplitDisabledCollapsesBilingualTextIntoOneProgressSurface() {
        val lyrics = listOf(
            LyricLine(
                timeMs = 1_000,
                text = "original\ntranslation",
                cues = listOf(
                    LyricCue(1_000, "original"),
                    LyricCue(1_500, ""),
                ),
                endTimeMs = 3_000,
            ),
        )

        val line = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = NotificationLyrics.DisplayOptions(
                splitEnabled = false,
                bilingualMode = LyricsBilingualDisplayMode.ALL,
            ),
        )

        assertNotNull(line)
        assertEquals("original translation", line?.original?.text)
        assertNull(line?.translation)
        assertTrue(line?.original?.cues.isNullOrEmpty())
    }

    @Test
    fun statusBarSplitEnabledKeepsIndependentRows() {
        val lyrics = listOf(LyricLine(1_000, "original\ntranslation"))

        val line = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = NotificationLyrics.DisplayOptions(
                splitEnabled = true,
                bilingualMode = LyricsBilingualDisplayMode.ALL,
            ),
        )

        assertEquals("original", line?.original?.text)
        assertEquals("translation", line?.translation?.text)
    }

    @Test
    fun externalDisplayModeCanSelectOriginalOrTranslationWhenSplit() {
        val lyrics = listOf(LyricLine(1_000, "original\ntranslation"))

        val original = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = NotificationLyrics.DisplayOptions(
                splitEnabled = true,
                bilingualMode = LyricsBilingualDisplayMode.ORIGINAL,
            ),
        )
        val translation = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = NotificationLyrics.DisplayOptions(
                splitEnabled = true,
                bilingualMode = LyricsBilingualDisplayMode.TRANSLATION,
            ),
        )

        assertEquals("original", original?.original?.text)
        assertNull(original?.translation)
        assertNull(translation?.original)
        assertEquals("translation", translation?.translation?.text)
    }

    @Test
    fun externalDisplayModeCanSelectEachPartWhenNotSplit() {
        val lyrics = listOf(LyricLine(1_000, "original\ntranslation"))

        fun text(mode: LyricsBilingualDisplayMode): String? = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = NotificationLyrics.DisplayOptions(
                splitEnabled = false,
                bilingualMode = mode,
            ),
        )?.let { it.original?.text ?: it.translation?.text }

        assertEquals("original", text(LyricsBilingualDisplayMode.ORIGINAL))
        assertEquals("translation", text(LyricsBilingualDisplayMode.TRANSLATION))
        assertEquals("original translation", text(LyricsBilingualDisplayMode.ALL))

        val translationLine = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = NotificationLyrics.DisplayOptions(
                splitEnabled = false,
                bilingualMode = LyricsBilingualDisplayMode.TRANSLATION,
            ),
        )
        assertNull(translationLine?.original)
        assertEquals("translation", translationLine?.translation?.text)
    }

    @Test
    fun statusBarWindowCanLayoutInSystemBarArea() {
        val flags = statusBarLyricsWindowFlags()

        assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
    }
}
