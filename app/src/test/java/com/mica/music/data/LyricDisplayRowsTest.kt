package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricDisplayRowsTest {

    @Test
    fun splitPartsAtIngestDoesNotPersistTextHeuristicSplits() {
        val samples = listOf(
            "AC/DC",
            "and/or",
            "01/02/2026",
            "original / translation",
            "left|right",
            "未熟 無ジョウ されど\u2009不成熟 无情（常） 但是",
        )

        samples.forEach { text ->
            val parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, text))
            assertEquals(parts, LyricDisplayRows.splitPartsAtIngest(parts))
        }
    }

    @Test
    fun splitPartsAtIngestLeavesExistingTranslationTrackUntouched() {
        val parts = listOf(
            LyricTextPart(LyricTextRole.READING, "a i wa"),
            LyricTextPart(LyricTextRole.ORIGINAL, "愛は"),
            LyricTextPart(LyricTextRole.TRANSLATION, "爱"),
        )

        assertEquals(parts, LyricDisplayRows.splitPartsAtIngest(parts))
    }

    @Test
    fun legacyLyricsConversionKeepsHeuristicSeparatorsInOriginalText() {
        val document = listOf(LyricLine(timeMs = 1_000, text = "AC/DC / live"))
            .toLyricsDocumentCompat(format = LyricsFormat.LRC)

        assertEquals(
            listOf(LyricTextPart(LyricTextRole.ORIGINAL, "AC/DC / live")),
            document.lines.single().parts,
        )
        assertEquals(null, LyricDisplayRows.rowsFromParts(document.lines.single().parts))
        assertEquals(
            listOf("AC/DC", "live"),
            LyricDisplayRows.splitForDisplay("AC/DC / live"),
        )
    }

    @Test
    fun rowsFromPartsMergesWhenSplitDisabled() {
        val rows = LyricDisplayRows.rowsFromParts(
            parts = listOf(
                LyricTextPart(LyricTextRole.ORIGINAL, "原文"),
                LyricTextPart(LyricTextRole.TRANSLATION, "译文"),
            ),
            splitEnabled = false,
        )!!

        assertEquals(listOf("原文\u2009译文"), rows.map { it.text })
    }

    @Test
    fun displayRowsSelectOnlyTheTokensOwnedByTheirSemanticRole() {
        val tokens = listOf(
            LyricToken("ni", 1_000, 1_500, LyricTextRole.READING),
            LyricToken("你", 1_000, 1_500, LyricTextRole.ORIGINAL),
            LyricToken("和声", 1_500, 2_000, LyricTextRole.EXTRA),
            LyricToken("you", 1_000, 2_000, LyricTextRole.TRANSLATION),
        )

        assertEquals(
            listOf("ni"),
            LyricDisplayRows.tokensForRole(tokens, LyricTextRole.READING).map { it.text },
        )
        assertEquals(
            listOf("你", "和声"),
            LyricDisplayRows.tokensForRole(tokens, LyricTextRole.ORIGINAL).map { it.text },
        )
        assertEquals(
            listOf("you"),
            LyricDisplayRows.tokensForRole(tokens, LyricTextRole.TRANSLATION).map { it.text },
        )
    }

    @Test
    fun splitRowsRetainRangesInOriginalText() {
        val rows = LyricDisplayRows.splitForDisplayRows("original / translation")

        assertEquals(listOf("original", "translation"), rows.map { it.text })
        assertEquals(listOf(0, 11), rows.map { it.start })
        assertEquals(listOf(8, 22), rows.map { it.endExclusive })
    }

    @Test
    fun bilingualDisplayModeSelectsOriginalOrTranslationRow() {
        val text = "original / translation"

        val original = LyricDisplayRows.rowsForBilingualDisplayMode(
            text = text,
            mode = LyricsBilingualDisplayMode.ORIGINAL,
        )
        val translation = LyricDisplayRows.rowsForBilingualDisplayMode(
            text = text,
            mode = LyricsBilingualDisplayMode.TRANSLATION,
        )

        assertEquals(listOf("original"), original.map { it.text })
        assertEquals(listOf(0), original.map { it.start })
        assertEquals(listOf("translation"), translation.map { it.text })
        assertEquals(listOf(11), translation.map { it.start })
    }

    @Test
    fun explicitNewlinesRemainMultipleOriginalRowsInEveryBilingualMode() {
        val text = "original\nsecond original\nthird original"
        LyricsBilingualDisplayMode.entries.forEach { mode ->
            val rows = LyricDisplayRows.rowsForBilingualDisplayMode(text, mode = mode)
            assertEquals(listOf("original", "second original", "third original"), rows.map { it.text })
            assertEquals(listOf(LyricTextRole.ORIGINAL, LyricTextRole.ORIGINAL, LyricTextRole.ORIGINAL),
                rows.map { it.role })
            assertEquals(listOf(false, false, false), rows.map { it.inferredSecondary })
            assertEquals(listOf(0, 9, 25), rows.map { it.start })
        }
    }

    @Test
    fun ordinarySlashAndCjkCreditLinesStayIntact() {
        val inputs = listOf(
            "作曲:小A/小B/小C", "作曲：小A / 小B / 小C", "编曲 : 玉井健二/南田健吾",
            "演唱：小A/小B", "Composed by：Ed Sheeran/Johnny McDaid",
            "Acoustic Guitar / Electric Guitar：甲", "录音棚 Studio：A / B / C",
            "A/B", "AC/DC", "A//B", "小A/小B/小C",
        )
        inputs.forEach { text ->
            val rows = LyricDisplayRows.rowsForBilingualDisplayMode(text)
            assertEquals(text, listOf(text), rows.map { it.text })
            assertEquals(listOf(LyricTextRole.ORIGINAL), rows.map { it.role })
        }
    }

    @Test
    fun legacySeparatorsPreserveVisualSubrowsWithoutCreatingSemanticTranslations() {
        for (text in listOf("A / B", "A // B", "A | B", "愛は\u2009爱", "愛は\u200a爱")) {
            val rows = LyricDisplayRows.rowsForBilingualDisplayMode(text)
            assertEquals(2, rows.size)
            assertEquals(listOf(LyricTextRole.ORIGINAL, LyricTextRole.ORIGINAL),
                rows.map { it.role })
            assertEquals(listOf(false, true), rows.map { it.inferredSecondary })
            assertEquals(listOf(false, true), rows.map { it.usesSecondaryStyle })
            val translationOnly = LyricDisplayRows.rowsForBilingualDisplayMode(
                text, mode = LyricsBilingualDisplayMode.TRANSLATION)
            assertEquals(LyricTextRole.ORIGINAL, translationOnly.single().role)
        }
    }

    @Test
    fun structuredRolesKeepOriginalTranslationAndReadingIndependent() {
        val rows = LyricDisplayRows.rowsFromParts(listOf(
            LyricTextPart(LyricTextRole.READING, "ni hao"),
            LyricTextPart(LyricTextRole.ORIGINAL, "你好/世界"),
            LyricTextPart(LyricTextRole.TRANSLATION, "hello / world"),
        ))!!
        assertEquals(listOf(LyricTextRole.READING, LyricTextRole.ORIGINAL, LyricTextRole.TRANSLATION),
            rows.map { it.role })
        assertEquals(listOf("ni hao", "你好/世界", "hello / world"), rows.map { it.text })
    }

    @Test
    fun independentAirLyricsFormatExampleKeepsDisplayOnlyTranslationAndTimedCredits() {
        // Independent MIT-licensed format contract:
        // https://github.com/AirLyrics/AirLyrics/blob/main/app/src/test/java/com/andsi/airlyrics/lyrics/parser/LrcParserTest.kt
        val realFormatExample = "星が降らない街 / 星星不落的街道"
        val credit = "作曲 : 宇佐美宏 / 哈哇哇"
        val rows = LyricDisplayRows.rowsForBilingualDisplayMode(realFormatExample)
        assertEquals(listOf("星が降らない街", "星星不落的街道"), rows.map { it.text })
        assertEquals(listOf(LyricTextRole.ORIGINAL, LyricTextRole.ORIGINAL), rows.map { it.role })
        assertEquals(listOf(false, true), rows.map { it.inferredSecondary })
        assertEquals(listOf(credit), LyricDisplayRows.splitForDisplay(credit))
    }

    @Test
    fun rtlTextCanBeSplitWithoutChangingItsText() {
        val text = "\u05e9\u05dc\u05d5\u05dd\ntranslation"

        assertEquals(listOf("\u05e9\u05dc\u05d5\u05dd", "translation"), LyricDisplayRows.splitForDisplay(text))
    }
}
