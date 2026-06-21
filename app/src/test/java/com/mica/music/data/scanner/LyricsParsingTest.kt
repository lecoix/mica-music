package com.mica.music.data.scanner

import com.mica.music.data.LyricLine
import com.mica.music.data.LyricCue
import com.mica.music.data.LyricsSync
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
