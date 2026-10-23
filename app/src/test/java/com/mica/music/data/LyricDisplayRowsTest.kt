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
    fun structuredRowsNeverMergeWhenSplitDisabled() {
        val parts = listOf(
            LyricTextPart(LyricTextRole.ORIGINAL, "原文"),
            LyricTextPart(LyricTextRole.TRANSLATION, "译文"),
        )
        val all = LyricDisplayRows.rowsFromParts(parts, splitEnabled = false)!!
        assertEquals(listOf("原文", "译文"), all.map { it.text })
        assertEquals(listOf(LyricTextRole.ORIGINAL, LyricTextRole.TRANSLATION),
            all.map { it.role })
        // Explicit semantic mode remains meaningful at the low-level parts API.
        val translated = LyricDisplayRows.rowsFromParts(
            parts, mode = LyricsBilingualDisplayMode.TRANSLATION, splitEnabled = false)!!
        assertEquals(listOf("译文"), translated.map { it.text })
        assertEquals(listOf(LyricTextRole.TRANSLATION), translated.map { it.role })
    }

    @Test
    fun settingsOffIgnoresDormantPreviousBilingualChoiceButPreservesStructuredTracks() {
        for (format in listOf(LyricsFormat.LRC, LyricsFormat.TTML, LyricsFormat.SYLT)) {
            val node = LyricLineNode("dual", 0, parts=listOf(
                LyricTextPart(LyricTextRole.ORIGINAL, "Hello / world"),
                LyricTextPart(LyricTextRole.TRANSLATION, "你好世界"),
            ))
            val document=LyricsDocument(format=format,lines=listOf(node))
            val rows=LyricDisplayRows.rowsForDocumentLine(
                document,0,"Hello / world",enabled=false,
                mode=LyricsBilingualDisplayMode.TRANSLATION)
            assertEquals(listOf("Hello / world","你好世界"),rows.map{it.text})
            assertEquals(listOf(LyricTextRole.ORIGINAL,LyricTextRole.TRANSLATION),
                rows.map{it.role})
        }
        val pureText=LyricsDocument(format=LyricsFormat.LRC,lines=listOf(
            LyricLineNode("plain",0,parts=listOf(
                LyricTextPart(LyricTextRole.ORIGINAL,"Hello / 你好")))))
        assertEquals(listOf("Hello / 你好"),
            LyricDisplayRows.rowsForDocumentLine(pureText,0,"Hello / 你好",
                enabled=false,mode=LyricsBilingualDisplayMode.TRANSLATION).map{it.text})
        assertEquals(listOf("你好"),
            LyricDisplayRows.rowsForDocumentLine(pureText,0,"Hello / 你好",
                enabled=true,mode=LyricsBilingualDisplayMode.TRANSLATION).map{it.text})
    }

    @Test
    fun trulyWordTimedLrcOriginalOnlyDoesNotUseTextSlashSplitter() {
        val txt="Hello / 你好"
        val doc=LyricsDocument(format=LyricsFormat.LRC,lines=listOf(
            LyricLineNode("timed",1000,parts=listOf(
                LyricTextPart(LyricTextRole.ORIGINAL,txt)),
                tokens=listOf(
                    LyricToken("Hello ",1000,1250),
                    LyricToken("/",1300,1400),
                    LyricToken("你好",1450,1600)
                ))))
        assertEquals(listOf(txt),
            LyricDisplayRows.rowsForDocumentLine(doc,0,txt,
                enabled=true,mode=LyricsBilingualDisplayMode.TRANSLATION).map{it.text})
        assertEquals(listOf(LyricTextRole.ORIGINAL),
            LyricDisplayRows.rowsForDocumentLine(doc,0,txt).map{it.role})
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
            "詞 : 陳耀森 / KW 朱敏希", "編 : 蘇道哲 / Nic Tsui",
            "編：徐浩 / 黃兆銘",
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
    fun originalOnlyStructuredTtmlDoesNotInventSecondaryTranslationOnSlashOrThinSpace() {
        val text = "你好 / hello\u2009more"
        for (format in listOf(LyricsFormat.TTML, LyricsFormat.SYLT)) {
            val document = LyricsDocument(
                format = format,
                lines = listOf(LyricLineNode(
                    id = "one",
                    startMs = 1_000,
                    parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, text)),
                )),
            )
            for (mode in LyricsBilingualDisplayMode.entries) {
                val rows = LyricDisplayRows.rowsForDocumentLine(document, 0, text, mode = mode)
                assertEquals(listOf(text), rows.map { it.text })
                assertEquals(listOf(LyricTextRole.ORIGINAL), rows.map { it.role })
                assertEquals(listOf(false), rows.map { it.inferredSecondary })
            }
        }
    }

    @Test
    fun originalOnlyLegacyLrcKeepsVisualSecondaryWhileStructuredTranslationStaysSemantic() {
        val text = "星が降らない街 / 星星不落的街道"
        val lrc = LyricsDocument(
            format = LyricsFormat.LRC,
            lines = listOf(LyricLineNode(
                id = "original", startMs = 0,
                parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, text)),
            )),
        )
        val inferred = LyricDisplayRows.rowsForDocumentLine(lrc, 0, text)
        assertEquals(listOf("星が降らない街", "星星不落的街道"), inferred.map { it.text })
        assertEquals(listOf(LyricTextRole.ORIGINAL, LyricTextRole.ORIGINAL),
            inferred.map { it.role })
        assertEquals(listOf(false, true), inferred.map { it.inferredSecondary })
        val structured = LyricsDocument(format = LyricsFormat.TTML, lines = listOf(
            LyricLineNode(id = "two", startMs = 0, parts = listOf(
                LyricTextPart(LyricTextRole.ORIGINAL, "星が降らない街"),
                LyricTextPart(LyricTextRole.TRANSLATION, "星星不落的街道"),
            )),
        ))
        assertEquals(
            listOf(LyricTextRole.ORIGINAL, LyricTextRole.TRANSLATION),
            LyricDisplayRows.rowsForDocumentLine(structured, 0, text).map { it.role },
        )
    }

    @Test
    fun rtlTextCanBeSplitWithoutChangingItsText() {
        val text = "\u05e9\u05dc\u05d5\u05dd\ntranslation"

        assertEquals(listOf("\u05e9\u05dc\u05d5\u05dd", "translation"), LyricDisplayRows.splitForDisplay(text))
    }
}
