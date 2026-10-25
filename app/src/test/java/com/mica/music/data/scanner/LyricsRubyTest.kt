package com.mica.music.data.scanner

import com.mica.music.data.*
import com.mica.music.data.local.LyricsDocumentCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LyricsRubyTest {
    private fun parse(body: String, head: String = ""): LyricLineNode = LyricsSanitizer.parseFilteredDocument(
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"><head>$head</head><body><p begin="1s" end="4s" itunes:key="L1">$body</p></body></tt>""",
    ).lines.single()

    @Test fun explicitRubyKeepsOneToManyAndGroupedReadingsWithoutTiming() {
        val line = parse("""<span tts:ruby="container"><span tts:ruby="base">風</span><span tts:ruby="text">かぜ</span></span>が<span tts:ruby="container"><span tts:ruby="base">今日</span><span tts:ruby="textContainer"><span tts:ruby="text">きょう</span></span></span>も""")
        val original = line.parts.first { it.role == LyricTextRole.ORIGINAL }
        assertEquals("風が今日も", original.text)
        assertEquals(listOf(LyricRuby(0, 1, "かぜ"), LyricRuby(2, 4, "きょう")), original.ruby)
        assertEquals(original.ruby, lyricRubyAlignment(line.parts, line.tokens))
        assertEquals("かぜきょう", line.parts.first { it.role == LyricTextRole.READING }.text)
        val document = LyricsDocument(lines = listOf(line))
        assertEquals(document, LyricsDocumentCodec.decode(LyricsDocumentCodec.encode(document)))
        assertEquals("風が今日も", document.toLegacyLyricLines().single().text)
    }

    @Test fun nestedTimedRubyUsesBaseOnlyAndPreservesSourceTimings() {
        val line = parse("""<span begin="1s" end="4s"><span tts:ruby="container"><span tts:ruby="base">詮</span><span tts:ruby="textContainer"><span tts:ruby="text" begin="1s" end="1.4s">せ</span><span tts:ruby="text" begin="1.4s" end="2s">ん</span></span></span></span>""")
        assertEquals("詮", line.parts.first { it.role == LyricTextRole.ORIGINAL }.text)
        assertEquals(listOf(LyricRuby(0, 1, "せん")), lyricRubyAlignment(line.parts, line.tokens))
        assertEquals(listOf("せ", "ん"), line.tokens.filter { it.partRole == LyricTextRole.READING }.map { it.text })
        assertEquals(listOf(1400, 2000), line.tokens.filter { it.partRole == LyricTextRole.READING }.map { it.endMs })
        assertEquals(2000, line.tokens.single { it.partRole == LyricTextRole.ORIGINAL }.endMs)
    }

    @Test fun timedSidecarAlignsOnlyWhenIntervalsAreExactAndUnique() {
        val head = """<iTunesMetadata><transliterations><transliteration><text for="L1"><span begin="1s" end="2s">かぜ</span><span begin="2.5s" end="3s">か</span></text></transliteration></transliterations></iTunesMetadata>"""
        val line = parse("""<span begin="1s" end="2s">風</span><span begin="2s" end="2.5s">が</span><span begin="2.5s" end="3s">変</span>わっても""", head)
        assertEquals(listOf(LyricRuby(0, 1, "かぜ"), LyricRuby(2, 3, "か")), lyricRubyAlignment(line.parts, line.tokens))
        val unbound = line.parts.map { it.copy(ruby = emptyList()) }
        assertTrue(lyricRubyAlignment(unbound, line.tokens.map { if (it.partRole == LyricTextRole.READING) it.copy(endMs = it.endMs!! - 1) else it }, true).isEmpty())
        assertTrue(lyricRubyAlignment(unbound, line.tokens + line.tokens.first { it.partRole == LyricTextRole.ORIGINAL }, true).isEmpty())
    }

    @Test fun wholeLineReadingAndEqualCountsNeverTriggerGuessedAlignment() {
        val parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "今日"), LyricTextPart(LyricTextRole.READING, "きょう"))
        assertTrue(lyricRubyAlignment(parts, emptyList()).isEmpty())
        val tokens = listOf(LyricToken("今", 1000, 2000), LyricToken("日", 2000, 3000), LyricToken("きょ", 1000, 2100, LyricTextRole.READING), LyricToken("う", 2100, 3000, LyricTextRole.READING))
        assertTrue(lyricRubyAlignment(parts, tokens).isEmpty())
    }

    @Test fun ambiguousRubyRetainsCleanOriginalAndWholeReading() {
        val line = parse("""<span tts:ruby="container"><span tts:ruby="base">風</span><span tts:ruby="base">空</span><span tts:ruby="text">かぜ</span><span tts:ruby="text">そら</span></span>""")
        assertEquals("風空", line.parts.first { it.role == LyricTextRole.ORIGINAL }.text)
        assertEquals("かぜそら", line.parts.first { it.role == LyricTextRole.READING }.text)
        assertTrue(lyricRubyAlignment(line.parts, line.tokens).isEmpty())
    }

    @Test fun inferredEndsAndLegacyPayloadsCannotCreateAssociations() {
        val head = """<iTunesMetadata><transliterations><transliteration><text for="L1"><span begin="1s">かぜ</span><span begin="2s">そら</span></text></transliteration></transliterations></iTunesMetadata>"""
        val line = parse("""<span begin="1s">風</span><span begin="2s">空</span>""", head)
        assertTrue(line.tokens.all { it.endMs != null })
        assertTrue(lyricRubyAlignment(line.parts, line.tokens).isEmpty())
        val legacy = """{"version":2,"lines":[{"startMs":1000,"parts":[{"role":"ORIGINAL","text":"風"},{"role":"READING","text":"かぜ"}]}]}"""
        assertTrue(LyricsDocumentCodec.decode(legacy).lines.single().parts.all { it.ruby.isEmpty() })
        val invalid = legacy.replace("\"text\":\"風\"", "\"text\":\"風\",\"ruby\":[{\"start\":0,\"end\":999,\"text\":\"かぜ\"}]")
        val decoded = LyricsDocumentCodec.decode(invalid).lines.single()
        assertEquals("風", decoded.parts.first().text)
        assertTrue(lyricRubyAlignment(decoded.parts, decoded.tokens).isEmpty())
    }
}
