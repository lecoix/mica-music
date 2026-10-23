package com.mica.music.lyrics

import com.mica.music.data.*
import org.junit.Assert.*
import org.junit.Test

class LyricsDisplayProjectionSemanticTest {
    private fun doc(format: LyricsFormat, text: String, translated: String? = null): LyricsSession {
        val parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, text)) +
            listOfNotNull(translated?.let { LyricTextPart(LyricTextRole.TRANSLATION, it) })
        return LyricsSession(LyricsDocument(
            format = format,
            lines = listOf(LyricLineNode("line", 1000, parts = parts)),
        ))
    }

    @Test fun actualStructuredTranslationVisibleWithVisualSplitOff() {
        val session = doc(LyricsFormat.TTML, "Hello / world", "你好世界")
        fun text(mode: LyricsBilingualDisplayMode, split: Boolean) =
            LyricsDisplayProjection.lyricLineText(session, 0, LyricsDisplayOptions(split, mode))
        assertEquals("你好世界", text(LyricsBilingualDisplayMode.TRANSLATION, false))
        assertEquals("Hello / world", text(LyricsBilingualDisplayMode.ORIGINAL, false))
        assertEquals("Hello / world 你好世界", text(LyricsBilingualDisplayMode.ALL, false))
        assertEquals("你好世界", text(LyricsBilingualDisplayMode.TRANSLATION, true))
    }

    @Test fun unstructuredLineOnlyUsesInferredVisualSplitWhenEnabled() {
        val session = doc(LyricsFormat.LRC, "Hello / 你好")
        fun text(split: Boolean) = LyricsDisplayProjection.lyricLineText(
            session, 0, LyricsDisplayOptions(split, LyricsBilingualDisplayMode.TRANSLATION))
        assertEquals("你好", text(true))
        assertEquals("Hello / 你好", text(false))
    }

    @Test fun originalOnlyStructuredLineNeverGuessesTranslation() {
        val session = doc(LyricsFormat.TTML, "歌词制作 : A/B")
        assertEquals("歌词制作 : A/B", LyricsDisplayProjection.lyricLineText(
            session, 0, LyricsDisplayOptions(true, LyricsBilingualDisplayMode.TRANSLATION)))
    }
}
