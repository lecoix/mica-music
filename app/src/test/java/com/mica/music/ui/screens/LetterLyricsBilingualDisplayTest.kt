package com.mica.music.ui.screens

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsBilingualDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LetterLyricsBilingualDisplayTest {

    @Test
    fun thinSpaceLineSplitsIntoMainAndTranslationColumns() {
        val pieces = letterDisplayPieces(
            line = line(LyricTextRole.ORIGINAL to "未熟 無ジョウ されど\u2009不成熟 无情（常） 但是"),
            bilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
            readingEnabled = true,
            splitEnabled = true,
        )

        assertEquals(
            listOf(
                LetterDisplayKind.MAIN to "未熟 無ジョウ されど",
                LetterDisplayKind.TRANSLATION to "不成熟 无情（常） 但是",
            ),
            pieces.map { it.kind to it.text },
        )
        assertEquals(listOf(true, false), pieces.map { it.usePrimaryWordSchedule })
    }

    @Test
    fun thinSpaceLineStaysOneColumnWhenSplitDisabled() {
        val pieces = letterDisplayPieces(
            line = line(LyricTextRole.ORIGINAL to "原文\u2009译文"),
            bilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
            readingEnabled = true,
            splitEnabled = false,
        )

        assertEquals(
            listOf(LetterDisplayKind.MAIN to "原文\u2009译文"),
            pieces.map { it.kind to it.text },
        )
    }

    @Test
    fun structuredTranslationUsesASeparateColumnOnlyWhenSplitEnabled() {
        val line = line(
            LyricTextRole.ORIGINAL to "原文",
            LyricTextRole.TRANSLATION to "译文",
        )

        assertEquals(
            listOf(
                LetterDisplayKind.MAIN to "原文",
                LetterDisplayKind.TRANSLATION to "译文",
            ),
            letterDisplayPieces(
                line = line,
                bilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
                readingEnabled = true,
                splitEnabled = true,
            ).map { it.kind to it.text },
        )
        assertEquals(
            listOf(LetterDisplayKind.MAIN to "原文\u2009译文"),
            letterDisplayPieces(
                line = line,
                bilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
                readingEnabled = true,
                splitEnabled = false,
            ).map { it.kind to it.text },
        )
    }

    @Test
    fun translationOnlyModeKeepsMainColumnMetrics() {
        val pieces = letterDisplayPieces(
            line = line(
                LyricTextRole.READING to "genbun",
                LyricTextRole.ORIGINAL to "原文",
                LyricTextRole.TRANSLATION to "译文",
            ),
            bilingualDisplayMode = LyricsBilingualDisplayMode.TRANSLATION,
            readingEnabled = true,
            splitEnabled = true,
        )

        assertEquals(
            listOf(LetterDisplayKind.MAIN to "译文"),
            pieces.map { it.kind to it.text },
        )
        assertEquals(false, pieces.single().usePrimaryWordSchedule)
    }

    @Test
    fun readingStaysAheadOfOriginalAndExtraStaysWithOriginal() {
        val pieces = letterDisplayPieces(
            line = line(
                LyricTextRole.READING to "genbun",
                LyricTextRole.ORIGINAL to "原文",
                LyricTextRole.EXTRA to "补充",
                LyricTextRole.TRANSLATION to "译文",
            ),
            bilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
            readingEnabled = true,
            splitEnabled = true,
        )

        assertEquals(
            listOf(
                LetterDisplayKind.READING to "genbun",
                LetterDisplayKind.MAIN to "原文 补充",
                LetterDisplayKind.TRANSLATION to "译文",
            ),
            pieces.map { it.kind to it.text },
        )
    }

    private fun line(vararg parts: Pair<LyricTextRole, String>): LyricLineNode = LyricLineNode(
        id = "line",
        startMs = 0,
        parts = parts.map { (role, text) -> LyricTextPart(role, text) },
    )
}
