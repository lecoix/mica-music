package com.mica.music.media

import com.mica.music.lyrics.LyricsDisplayOptions
import com.mica.music.data.LyricCue
import com.mica.music.ui.overlay.externalLyricsFillFraction
import com.mica.music.ui.overlay.statusBarLyricsWindowFlags
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
    fun finalExternalDisplayModeFiltersTheProjectedLine() {
        val line = ExternalLyricsLine(
            lineIndex = 0,
            startMs = 1_000,
            endMs = 2_000,
            original = ExternalLyricsText("original"),
            translation = ExternalLyricsText("translation"),
        )

        assertEquals("original", line.forExternalDisplay(LyricsBilingualDisplayMode.ORIGINAL).original?.text)
        assertNull(line.forExternalDisplay(LyricsBilingualDisplayMode.ORIGINAL).translation)
        assertNull(line.forExternalDisplay(LyricsBilingualDisplayMode.TRANSLATION).original)
        assertEquals(
            "translation",
            line.forExternalDisplay(LyricsBilingualDisplayMode.TRANSLATION).translation?.text,
        )
    }

    @Test
    fun finalTranslationModeFallsBackToTheAvailableSide() {
        val line = ExternalLyricsLine(
            lineIndex = 0,
            startMs = 1_000,
            endMs = 2_000,
            original = ExternalLyricsText("original"),
        )

        assertEquals(
            "original",
            line.forExternalDisplay(LyricsBilingualDisplayMode.TRANSLATION).original?.text,
        )
    }

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
            display = LyricsDisplayOptions(
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
            display = LyricsDisplayOptions(
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
            display = LyricsDisplayOptions(
                splitEnabled = true,
                bilingualMode = LyricsBilingualDisplayMode.ORIGINAL,
            ),
        )
        val translation = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = LyricsDisplayOptions(
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
            display = LyricsDisplayOptions(
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
            display = LyricsDisplayOptions(
                splitEnabled = false,
                bilingualMode = LyricsBilingualDisplayMode.TRANSLATION,
            ),
        )
        assertNull(translationLine?.original)
        assertEquals("translation", translationLine?.translation?.text)
    }

    @Test
    fun wordByWordProjectionCanBeDisabledForExternalLyrics() {
        val lyrics = listOf(
            LyricLine(
                timeMs = 1_000,
                text = "one two",
                cues = listOf(
                    LyricCue(1_000, "one"),
                    LyricCue(1_500, "two"),
                ),
                endTimeMs = 3_000,
            ),
        )

        val line = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = LyricsDisplayOptions(
                splitEnabled = true,
                bilingualMode = LyricsBilingualDisplayMode.ORIGINAL,
                wordByWordEnabled = false,
            ),
        )

        assertTrue(line?.original?.cues.isNullOrEmpty())
    }

    @Test
    fun wordByWordProjectionFallsBackToLineProgressWithoutWordTiming() {
        val lyrics = listOf(
            LyricLine(
                timeMs = 1_000,
                text = "whole line",
                cues = listOf(LyricCue(1_000, "whole line")),
                endTimeMs = 3_000,
            ),
        )

        val line = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = LyricsDisplayOptions(
                splitEnabled = true,
                bilingualMode = LyricsBilingualDisplayMode.ORIGINAL,
                wordByWordEnabled = true,
            ),
        )

        assertTrue(line?.original?.cues.isNullOrEmpty())
    }

    @Test
    fun wordByWordProjectionKeepsWordTimingWhenAvailableAndEnabled() {
        val lyrics = listOf(
            LyricLine(
                timeMs = 1_000,
                text = "one two",
                cues = listOf(
                    LyricCue(1_000, "one"),
                    LyricCue(1_500, "two"),
                ),
                endTimeMs = 3_000,
            ),
        )

        val line = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = LyricsDisplayOptions(
                splitEnabled = true,
                bilingualMode = LyricsBilingualDisplayMode.ORIGINAL,
                wordByWordEnabled = true,
            ),
        )

        assertEquals(listOf("one", "two"), line?.original?.cues?.map { it.text })
    }

    @Test
    fun wordByWordExternalDisplaySuppressesTranslation() {
        val lyrics = listOf(LyricLine(1_000, "original\ntranslation"))

        val line = buildExternalLyricsLine(
            document = lyrics.toLyricsDocumentCompat(),
            lyrics = lyrics,
            index = 0,
            display = LyricsDisplayOptions(
                splitEnabled = true,
                bilingualMode = LyricsBilingualDisplayMode.ALL,
                wordByWordEnabled = true,
                hideTranslationWhenWordByWordEnabled = true,
            ),
        )

        assertEquals("original", line?.original?.text)
        assertNull(line?.translation)
    }

    @Test
    fun lineFallbackDoesNotGraduallyRevealCharacters() {
        val text = ExternalLyricsText("plain lyric")
        val line = ExternalLyricsLine(
            lineIndex = 0,
            startMs = 1_000,
            endMs = 3_000,
            original = text,
        )

        assertEquals(0f, externalLyricsFillFraction(text, line, positionMs = 0), 0.001f)
        assertEquals(1f, externalLyricsFillFraction(text, line, positionMs = 1_500), 0.001f)
    }

    @Test
    fun overlayPositionTickerSkipsLineTimedSurface() {
        val store = DesktopLyricsOverlayStateStore()
        store.publish(
            line = ExternalLyricsLine(
                lineIndex = 0,
                startMs = 1_000,
                endMs = 3_000,
                original = ExternalLyricsText("plain lyric"),
            ),
            positionMs = 0,
            desktopEnabled = true,
            statusBarEnabled = false,
        )
        store.setPlaying(true)

        store.updatePosition(1_500)

        assertEquals(0, store.state.value.desktop.positionMs)
    }

    @Test
    fun overlayPositionTickerUpdatesCueTimedSurface() {
        val store = DesktopLyricsOverlayStateStore()
        store.publish(
            line = ExternalLyricsLine(
                lineIndex = 0,
                startMs = 1_000,
                endMs = 3_000,
                original = ExternalLyricsText(
                    text = "one two",
                    cues = listOf(LyricCue(1_000, "one"), LyricCue(1_500, "two")),
                ),
            ),
            positionMs = 0,
            desktopEnabled = true,
            statusBarEnabled = false,
        )
        store.setPlaying(true)

        store.updatePosition(1_500)

        assertEquals(1_500, store.state.value.desktop.positionMs)
    }

    @Test
    fun statusBarWindowCanLayoutInSystemBarArea() {
        val flags = statusBarLyricsWindowFlags()

        assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
    }
}
