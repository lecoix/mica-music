package com.mica.music.data.scanner

import com.mica.music.data.LyricLine
import com.mica.music.data.LyricCue
import com.mica.music.data.LyricsSync
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.toLegacyLyricLines
import java.io.File
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LyricsParsingTest {

    @Test
    fun externalTtmlKeepsFormatOriginAndStructuredRoles() {
        val document = LyricsSanitizer.parseFilteredDocument(
            """<tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><p begin="1s"><span>main</span><span ttm:role="x-translation">译文</span></p></body></tt>""",
            origin = LyricsOrigin.EXTERNAL,
        )

        assertEquals(LyricsFormat.TTML, document.format)
        assertEquals(LyricsOrigin.EXTERNAL, document.origin)
        assertEquals(listOf("main", "译文"), document.lines.single().parts.map { it.text })
    }

    @Test
    fun lrcSupportsMultipleTimestampsFractionsAndSorting() {
        val parsed = LrcParser.parse(
            """
            [00:02.50][00:03.500]Later
            [00:01.2]First
            """.trimIndent(),
        )
        assertEquals(listOf(1_200, 2_500, 3_500), parsed.map { it.timeMs })
        assertEquals(listOf("First", "Later", "Later"), parsed.map { it.text })
    }

    @Test
    fun lrcAppliesPositiveAndNegativeOffsets() {
        val delayed = LrcParser.parse("[offset:+250]\n[00:01.00]Later")
        val clamped = LrcParser.parse("[offset:-2000]\n[00:01.00]Start")

        assertEquals(1_250, delayed.single().timeMs)
        assertEquals(0, clamped.single().timeMs)
    }

    @Test
    fun enhancedLrcPreservesCueTextAndAppliesOffset() {
        val parsed = LrcParser.parse(
            "[offset:+100]\n[00:01.000]<00:01.000>你<00:01.250>好 <00:01.500>Mica",
        ).single()

        assertEquals(1_100, parsed.timeMs)
        assertEquals("你好 Mica", parsed.text)
        assertEquals(listOf(1_100, 1_350, 1_600), parsed.cues.map { it.timeMs })
        assertEquals(listOf("你", "好 ", "Mica"), parsed.cues.map { it.text })
    }

    @Test
    fun inlineBracketWordLyricsParsePerCharacterTimestamps() {
        val parsed = LrcParser.parse(
            """
            [00:00.000]黑[00:00.022]Girl [00:00.044]-
            [00:21.973]猜[00:22.229]不[00:22.477]透
            """.trimIndent(),
        )

        assertEquals(listOf(0, 21_973), parsed.map { it.timeMs })
        assertEquals("黑Girl -", parsed[0].text)
        assertEquals("猜不透", parsed[1].text)
        assertEquals(listOf("黑", "Girl ", "-"), parsed[0].cues.map { it.text })
        assertEquals(listOf(0, 22, 44), parsed[0].cues.map { it.timeMs })
        assertEquals(listOf("猜", "不", "透"), parsed[1].cues.map { it.text })
        assertEquals(0, LyricsSync.cueIndexForPosition(parsed[1], 21_900))
        assertEquals(1, LyricsSync.cueIndexForPosition(parsed[1], 22_100))
    }

    @Test
    fun inlineBracketStripsEmbeddedVersionMarkerFromEachLine() {
        val parsed = LrcParser.parse(
            """
            [00:21.973]v1: [00:22.229]猜[00:22.477]不
            [00:23.000]v1:[00:23.250]透
            [00:24.000]v1: [00:24.250]v1: 猜
            """.trimIndent(),
        )

        assertEquals(listOf("猜不", "透", "猜"), parsed.map { it.text })
        assertEquals(listOf("猜", "不"), parsed[0].cues.map { it.text })
        assertEquals("透", parsed[1].cues.single().text)
        assertEquals("猜", parsed[2].cues.single().text)
    }

    @Test
    fun enhancedLrcStripsEmbeddedVersionMarkerPrefix() {
        val parsed = LrcParser.parse(
            "[00:01.000]v1: <00:01.000>Hello <00:01.500>world",
        ).single()

        assertEquals("Hello world", parsed.text)
        assertEquals(listOf("Hello ", "world"), parsed.cues.map { it.text })
    }

    @Test
    fun inlineBracketWordLyricsApplyOffset() {
        val parsed = LrcParser.parse(
            "[offset:+100]\n[00:01.000]你[00:01.250]好",
        ).single()

        assertEquals(1_100, parsed.timeMs)
        assertEquals("你好", parsed.text)
        assertEquals(listOf(1_100, 1_350), parsed.cues.map { it.timeMs })
    }

    @Test
    fun kugouWordLyricsUseOffsetsRelativeToLine() {
        val parsed = LrcParser.parse(
            "[1000,2000]<0,400,0>Hello <400,600,0>world",
        ).single()

        assertEquals("Hello world", parsed.text)
        assertEquals(listOf(1_000, 1_400), parsed.cues.map { it.timeMs })
    }

    @Test
    fun sameTimestampWordTimedOriginalAndPlainTranslationMergeIntoBilingualLine() {
        val parsed = LrcParser.parse(
            """
            [00:25.560]<00:25.560>You <00:25.776>can <00:25.976>go
            [00:25.560]translation line
            [00:27.000]<00:27.000>Next
            """.trimIndent(),
        )

        assertEquals(2, parsed.size)
        assertEquals("You can go\ntranslation line", parsed[0].text)
        assertEquals(listOf("You ", "can ", "go"), parsed[0].cues.map { it.text })
        assertEquals(0, LyricsSync.indexForPosition(parsed, 25_560))
    }

    @Test
    fun lrcParsesCompatibilityDocumentWithStructuredBilingualParts() {
        val document = LrcParser.parseDocument(
            """
            [00:01.000]<00:01.000>original
            [00:01.000]translation
            """.trimIndent(),
        )

        assertEquals(com.mica.music.data.LyricsFormat.LRC, document.format)
        assertEquals(listOf("original", "translation"), document.lines.single().parts.map { it.text })
        assertEquals(
            listOf(
                com.mica.music.data.LyricTextRole.ORIGINAL,
                com.mica.music.data.LyricTextRole.TRANSLATION,
            ),
            document.lines.single().parts.map { it.role },
        )
    }

    @Test
    fun lrcSameTimestampTripleTrackMapsReadingThenTranslation() {
        val document = LrcParser.parseDocument(
            """
            [00:01.000]生まれた場所
            [00:01.000]u ma re ta ba sho
            [00:01.000]诞生的地方
            [00:02.000]次の行
            """.trimIndent(),
        )

        val line = document.lines.first()
        assertEquals(
            listOf(
                com.mica.music.data.LyricTextRole.READING,
                com.mica.music.data.LyricTextRole.ORIGINAL,
                com.mica.music.data.LyricTextRole.TRANSLATION,
            ),
            line.parts.map { it.role },
        )
        assertEquals(
            listOf("u ma re ta ba sho", "生まれた場所", "诞生的地方"),
            line.parts.map { it.text },
        )
        assertEquals("生まれた場所\n诞生的地方", document.toLegacyLyricLines().first().text)
        assertEquals(listOf("次の行"), document.lines[1].parts.map { it.text })
    }

    @Test
    fun lrcUntimestampedContinuationAttachesAsTranslation() {
        val document = LrcParser.parseDocument(
            """
            [00:01.000]你好椒盐音乐
            Hello Salt Player
            [00:02.000]下一句
            """.trimIndent(),
        )

        assertEquals(2, document.lines.size)
        assertEquals(
            listOf(
                com.mica.music.data.LyricTextRole.ORIGINAL,
                com.mica.music.data.LyricTextRole.TRANSLATION,
            ),
            document.lines[0].parts.map { it.role },
        )
        assertEquals(
            listOf("你好椒盐音乐", "Hello Salt Player"),
            document.lines[0].parts.map { it.text },
        )
    }

    @Test
    fun lrcUntimestampedTripleContinuationMapsReadingAndTranslation() {
        val document = LrcParser.parseDocument(
            """
            [00:01.000]生まれ
            u ma re
            诞生
            """.trimIndent(),
        )

        val line = document.lines.single()
        assertEquals(
            listOf(
                com.mica.music.data.LyricTextRole.READING,
                com.mica.music.data.LyricTextRole.ORIGINAL,
                com.mica.music.data.LyricTextRole.TRANSLATION,
            ),
            line.parts.map { it.role },
        )
        assertEquals(listOf("u ma re", "生まれ", "诞生"), line.parts.map { it.text })
    }

    @Test
    fun lrcBlankLineBreaksUntimestampedContinuation() {
        val document = LrcParser.parseDocument(
            """
            [00:01.000]main
            
            orphan translation
            """.trimIndent(),
        )

        assertEquals(2, document.lines.size)
        // Orphan untimed lines stay at 0ms and therefore sort before the timed main line.
        assertEquals(0, document.lines[0].startMs)
        assertEquals(listOf("orphan translation"), document.lines[0].parts.map { it.text })
        assertEquals(1_000, document.lines[1].startMs)
        assertEquals(listOf("main"), document.lines[1].parts.map { it.text })
    }

    @Test
    fun nearbyPlainLineDoesNotMergeWithWordTimedLine() {
        val parsed = LrcParser.parse(
            """
            [00:25.560]<00:25.560>Main
            [00:25.650]nearby harmony or ad lib
            """.trimIndent(),
        )

        assertEquals(listOf("Main", "nearby harmony or ad lib"), parsed.map { it.text })
    }

    @Test
    fun sameTimestampTwoWordTimedLinesDoNotMerge() {
        val parsed = LrcParser.parse(
            """
            [00:25.560]<00:25.560>Main
            [00:25.560]<00:25.560>Harmony
            """.trimIndent(),
        )

        assertEquals(listOf("Main", "Harmony"), parsed.map { it.text })
        assertTrue(parsed.all { it.cues.isNotEmpty() })
    }

    @Test
    fun ttmlParsesTimedSpansAndRejectsDoctype() {
        val parsed = LrcParser.parse(
            """
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
              <p begin="00:00:01.000"><span begin="1s">Hello </span><span begin="1.5s">world</span></p>
            </div></body></tt>
            """.trimIndent(),
        ).single()

        assertEquals(1_000, parsed.timeMs)
        assertEquals("Hello world", parsed.text)
        assertEquals(listOf(1_000, 1_500), parsed.cues.map { it.timeMs })

        val malicious = """<!DOCTYPE tt [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><tt><body><p begin="1s">&xxe;</p></body></tt>"""
        assertTrue(LrcParser.parse(malicious).isEmpty())
    }

    @Test
    fun ttmlPreservesParagraphEndAndDuration() {
        val parsed = LrcParser.parse(
            """
            <tt><body><div>
              <p begin="1s" end="2.5s">first</p>
              <p begin="3s" dur="1200ms">second</p>
            </div></body></tt>
            """.trimIndent(),
        )

        assertEquals(listOf(2_500, 4_200), parsed.map { it.endTimeMs })
    }

    @Test
    fun ttmlKeepsRoleMarkedTranslationAsSecondDisplayRow() {
        val parsed = LrcParser.parse(
            """
            <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
              <p begin="1s" end="2s"><span begin="1s">original</span><span ttm:role="x-translation">translation</span></p>
            </div></body></tt>
            """.trimIndent(),
        ).single()

        assertEquals("original\ntranslation", parsed.text)
        assertEquals(listOf("original"), parsed.cues.map { it.text })
    }

    @Test
    fun ttmlParsesStructuredDocumentWithoutRecoveringRolesFromText() {
        val document = TtmlLyricsParser.parseDocument(
            """
            <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
              <p begin="1s" end="2s"><span begin="1s">original</span><span ttm:role="x-translation">translation</span></p>
            </div></body></tt>
            """.trimIndent(),
        )

        val line = document.lines.single()
        assertEquals(com.mica.music.data.LyricsFormat.TTML, document.format)
        assertEquals(
            listOf(com.mica.music.data.LyricTextRole.ORIGINAL, com.mica.music.data.LyricTextRole.TRANSLATION),
            line.parts.map { it.role },
        )
        assertEquals(listOf("original", "translation"), line.parts.map { it.text })
        assertEquals(2_000, line.tokens.single().endMs)
    }

    @Test
    fun ttmlParsesXRomanizationAboveOriginalWithoutMergingIntoOriginal() {
        val document = TtmlLyricsParser.parseDocument(
            """
            <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
              <p begin="1s" end="2s">
                <span begin="1s">生まれ</span>
                <span ttm:role="x-romanization">u ma re</span>
                <span ttm:role="x-translation">诞生</span>
              </p>
            </div></body></tt>
            """.trimIndent(),
        )

        val line = document.lines.single()
        assertEquals(
            listOf(
                com.mica.music.data.LyricTextRole.READING,
                com.mica.music.data.LyricTextRole.ORIGINAL,
                com.mica.music.data.LyricTextRole.TRANSLATION,
            ),
            line.parts.map { it.role },
        )
        assertEquals(listOf("u ma re", "生まれ", "诞生"), line.parts.map { it.text })
        assertEquals("生まれ\n诞生", document.toLegacyLyricLines().single().text)
        assertEquals(listOf("生まれ"), document.toLegacyLyricLines().single().cues.map { it.text })
    }

    @Test
    fun ttmlPrefersItunesTransliterationsOverInlineRoman() {
        val document = TtmlLyricsParser.parseDocument(
            """
            <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-extensions">
              <head><metadata>
                <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                  <transliterations>
                    <transliteration xml:lang="ja-Latn">
                      <text for="L1">head roman</text>
                    </transliteration>
                  </transliterations>
                </iTunesMetadata>
              </metadata></head>
              <body><div>
                <p begin="1s" end="2s" itunes:key="L1">
                  <span begin="1s">原文</span>
                  <span ttm:role="x-roman">inline roman</span>
                </p>
              </div></body>
            </tt>
            """.trimIndent(),
        )

        val line = document.lines.single()
        assertEquals(com.mica.music.data.LyricTextRole.READING, line.parts.first().role)
        assertEquals("head roman", line.parts.first().text)
        assertEquals("原文", line.parts.first { it.role == com.mica.music.data.LyricTextRole.ORIGINAL }.text)
    }

    @Test
    fun ttmlAcceptsXRomanAlias() {
        val document = TtmlLyricsParser.parseDocument(
            """
            <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
              <p begin="1s"><span begin="1s">君</span><span ttm:role="x-roman">kimi</span></p>
            </div></body></tt>
            """.trimIndent(),
        )
        assertEquals(
            listOf("kimi", "君"),
            document.lines.single().parts.map { it.text },
        )
    }

    @Test
    fun rowsFromPartsOrdersReadingAboveOriginal() {
        val rows = com.mica.music.data.LyricDisplayRows.rowsFromParts(
            parts = listOf(
                com.mica.music.data.LyricTextPart(com.mica.music.data.LyricTextRole.READING, "romaji"),
                com.mica.music.data.LyricTextPart(com.mica.music.data.LyricTextRole.ORIGINAL, "原文"),
                com.mica.music.data.LyricTextPart(com.mica.music.data.LyricTextRole.TRANSLATION, "译文"),
            ),
        )!!
        assertEquals(
            listOf(
                com.mica.music.data.LyricTextRole.READING,
                com.mica.music.data.LyricTextRole.ORIGINAL,
                com.mica.music.data.LyricTextRole.TRANSLATION,
            ),
            rows.map { it.role },
        )
        assertEquals(listOf("romaji", "原文", "译文"), rows.map { it.text })
    }

    @Test
    fun ttmlParsesWhenAndroidXmlFactoryRejectsOptionalFeatures() {
        val parsed = TtmlLyricsParser.parseWithFactory(
            """<tt><body><p begin="1s"><span begin="1s">word</span></p></body></tt>""",
            AndroidLikeDocumentBuilderFactory(),
        )

        assertEquals("word", parsed.single().text)
        assertEquals(1_000, parsed.single().cues.single().timeMs)
    }

    @Test
    fun realCoralSeaTtmlFixtureKeepsItsWordTimelineWhenAvailable() {
        val fixture = listOf(File(".test-music"), File("../.test-music"))
            .flatMap { it.listFiles().orEmpty().asList() }
            .firstOrNull { it.isFile && it.extension.equals("ttml", ignoreCase = true) }
        assumeTrue("Local diagnostic fixture is not checked in", fixture != null)

        val parsed = LrcParser.parse(LyricsEncoding.decodeBytes(requireNotNull(fixture).readBytes()))
        val cues = parsed.flatMap { it.cues }

        assertEquals(69, parsed.size)
        assertEquals(371, cues.size)
        assertEquals(15_135, parsed.first().timeMs)
        assertEquals(15_135, parsed.first().cues.first().timeMs)
        assertTrue("Expected the real timeline to reach the final verse", cues.last().timeMs > 230_000)
    }

    @Test
    fun invalidCueOrderFallsBackToLineTiming() {
        val parsed = LrcParser.parse(
            "[00:01.000]<00:01.500>later <00:01.200>earlier",
        ).single()

        assertEquals("later earlier", parsed.text)
        assertTrue(parsed.cues.isEmpty())
    }

    @Test
    fun candidateSelectionPrefersWordTimingOverLineOnlyLyrics() {
        val lineOnly = listOf(LyricLine(1_000, "same text"))
        val wordTimed = listOf(
            LyricLine(1_000, "same text", listOf(LyricCue(1_000, "same "), LyricCue(1_500, "text"))),
        )

        assertEquals(wordTimed, LyricsSanitizer.pickBest(listOf(lineOnly, wordTimed)))
    }

    @Test
    fun plainLyricsRemainUntimedAndMetadataTagsAreIgnored() {
        val parsed = LrcParser.parse("[ar:Artist]\nLine one\nLine two")
        assertEquals(listOf("Line one", "Line two"), parsed.map { it.text })
        assertFalse(LyricsSync.hasTimedLyrics(parsed))
    }

    @Test
    fun lyricSyncAppliesLeadWithoutSelectingTimedLineTooEarly() {
        val lyrics = listOf(LyricLine(1_000, "one"), LyricLine(2_000, "two"))
        assertEquals(0, LyricsSync.indexForPosition(lyrics, 800))
        assertEquals(1, LyricsSync.indexForPosition(lyrics, 1_900))
        assertEquals(-1, LyricsSync.indexForPosition(emptyList(), 1_000))
    }

    @Test
    fun lyricSyncSelectsCueWithExistingLead() {
        val line = LyricLine(
            timeMs = 1_000,
            text = "one two",
            cues = listOf(LyricCue(1_000, "one "), LyricCue(1_500, "two")),
        )

        assertEquals(-1, LyricsSync.cueIndexForPosition(line, 800))
        assertEquals(0, LyricsSync.cueIndexForPosition(line, 850))
        assertEquals(1, LyricsSync.cueIndexForPosition(line, 1_350))
        assertEquals(1, LyricsSync.cueIndexForPosition(line, 9_000))
    }

    @Test
    fun encodingHandlesUtf8Utf16AndGbk() {
        val text = "[00:01.00]你好 Mica"
        assertTrue(LyricsEncoding.decodeBytes(text.toByteArray(StandardCharsets.UTF_8)).contains("Mica"))
        assertTrue(LyricsEncoding.decodeBytes(text.toByteArray(StandardCharsets.UTF_16)).contains("Mica"))
        assertTrue(LyricsEncoding.decodeBytes(text.toByteArray(charset("GBK"))).contains("Mica"))
    }

}

private class AndroidLikeDocumentBuilderFactory : DocumentBuilderFactory() {
    override fun newDocumentBuilder(): DocumentBuilder = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = this@AndroidLikeDocumentBuilderFactory.isNamespaceAware
        isValidating = this@AndroidLikeDocumentBuilderFactory.isValidating
        isCoalescing = this@AndroidLikeDocumentBuilderFactory.isCoalescing
        isExpandEntityReferences = this@AndroidLikeDocumentBuilderFactory.isExpandEntityReferences
        isIgnoringComments = this@AndroidLikeDocumentBuilderFactory.isIgnoringComments
        isIgnoringElementContentWhitespace =
            this@AndroidLikeDocumentBuilderFactory.isIgnoringElementContentWhitespace
    }.newDocumentBuilder()

    override fun setAttribute(name: String?, value: Any?) {
        throw IllegalArgumentException(name)
    }

    override fun getAttribute(name: String?): Any = throw IllegalArgumentException(name)

    override fun setFeature(name: String?, value: Boolean) {
        when (name) {
            "http://xml.org/sax/features/namespaces" -> isNamespaceAware = value
            "http://xml.org/sax/features/validation" -> isValidating = value
            else -> throw ParserConfigurationException(name)
        }
    }

    override fun getFeature(name: String?): Boolean = when (name) {
        "http://xml.org/sax/features/namespaces" -> isNamespaceAware
        "http://xml.org/sax/features/validation" -> isValidating
        else -> throw ParserConfigurationException(name)
    }
}
