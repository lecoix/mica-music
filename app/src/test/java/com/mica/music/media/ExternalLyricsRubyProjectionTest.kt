package com.mica.music.media

import com.mica.music.data.*
import com.mica.music.lyrics.LyricsDisplayOptions
import org.junit.Assert.*
import org.junit.Test

class ExternalLyricsRubyProjectionTest {
    private val ruby = listOf(LyricRuby(0, 1, "かぜ"), LyricRuby(2, 4, "きょう"))
    private val document = LyricsDocument(lines = listOf(LyricLineNode("line", 1000, 4000, listOf(
        LyricTextPart(LyricTextRole.READING, "かぜきょう"),
        LyricTextPart(LyricTextRole.ORIGINAL, "風が今日も", ruby),
        LyricTextPart(LyricTextRole.TRANSLATION, "今天风也在吹"),
    ), listOf(LyricToken("風が", 1000, 2000), LyricToken("今日も", 2000, 4000)))))

    private fun project(doc: LyricsDocument = document, split: Boolean = true, wordTimed: Boolean = true) =
        buildExternalLyricsLine(doc, doc.toLegacyLyricLines(), 0,
            LyricsDisplayOptions(split, LyricsBilingualDisplayMode.ALL, wordByWordEnabled = wordTimed))!!

    @Test fun projectionPreservesRangesAndFinalDisplayConsumesOnlyTheMatchedReadingRow() {
        val line = project()
        assertEquals(ruby, line.original!!.ruby)
        assertNotNull(line.reading)
        val displayed = line.forExternalDisplay(LyricsBilingualDisplayMode.ALL)
        assertEquals(listOf(LyricTextRole.ORIGINAL, LyricTextRole.TRANSLATION), displayed.rows.map { it.role })
        assertEquals(ruby, displayed.original!!.ruby)
        val originalOnly = project(wordTimed = false).forExternalDisplay(LyricsBilingualDisplayMode.ORIGINAL)
        assertTrue(originalOnly.original!!.ruby.isEmpty())
        assertNull(originalOnly.reading)
    }

    @Test fun readingToggleClearsInlineAnnotationsWithoutRepublishingTheLine() {
        val line = project()
        val off = line.forExternalDisplay(LyricsBilingualDisplayMode.ALL, readingEnabled = false)
        assertNull(off.reading)
        assertTrue(off.original!!.ruby.isEmpty())
        assertEquals(ruby, line.forExternalDisplay(LyricsBilingualDisplayMode.ALL).original!!.ruby)
        assertNull(line.forExternalDisplay(LyricsBilingualDisplayMode.TRANSLATION).original)
        for (ranges in listOf(ruby, emptyList())) for (split in listOf(true, false)) {
            val doc = document.copy(lines = document.lines.map { node -> node.copy(parts = node.parts.map {
                if (it.role == LyricTextRole.ORIGINAL) it.copy(ruby = ranges) else it
            }) })
            val projected = buildExternalLyricsLine(doc, doc.toLegacyLyricLines(), 0,
                LyricsDisplayOptions(split, LyricsBilingualDisplayMode.ORIGINAL))!!
            assertEquals(listOf(LyricTextRole.ORIGINAL), projected.rows.map { it.role })
            assertEquals("風が今日も", projected.original!!.text)
            assertTrue(projected.original!!.ruby.isEmpty())
            val inApp = LyricDisplayRows.rowsFromPartsWithRuby(doc.lines.single().parts, emptyList(),
                mode = LyricsBilingualDisplayMode.ORIGINAL, splitEnabled = split)!!
            assertEquals(listOf("風が今日も"), inApp.map { it.text })
            assertTrue(inApp.single().ruby.isEmpty())
            assertNull(project(doc, split).forExternalDisplay(LyricsBilingualDisplayMode.ORIGINAL).reading)
        }
        val automatic = buildExternalLyricsLine(document, document.toLegacyLyricLines(), 0,
            LyricsDisplayOptions(true, LyricsBilingualDisplayMode.ALL, hideTranslationWhenWordByWordEnabled = true))!!
        assertEquals(ruby, automatic.forExternalDisplay(LyricsBilingualDisplayMode.ALL).original!!.ruby)
    }

    @Test fun unknownOrPartialCorrespondenceKeepsTheWholeReadingRow() {
        for (ranges in listOf(emptyList(), ruby.take(1), listOf(LyricRuby(0, 99, "かぜきょう")))) {
            val unknown = document.copy(lines = document.lines.map { line -> line.copy(parts = line.parts.map {
                if (it.role == LyricTextRole.ORIGINAL) it.copy(ruby = ranges) else it
            }) })
            val displayed = project(unknown).forExternalDisplay(LyricsBilingualDisplayMode.ALL)
            assertEquals("かぜきょう", displayed.reading!!.text)
            assertTrue(displayed.original!!.ruby.isEmpty())
        }
        // The external projection trims whitespace. Source offsets must never be reused after that edit.
        val padded = document.copy(lines = document.lines.map { line -> line.copy(parts = line.parts.map {
            if (it.role == LyricTextRole.ORIGINAL) it.copy(text = " ${it.text}",
                ruby = it.ruby.map { range -> range.copy(start = range.start + 1, endExclusive = range.endExclusive + 1) }) else it
        }) })
        val displayed = project(padded).forExternalDisplay(LyricsBilingualDisplayMode.ALL)
        assertEquals("かぜきょう", displayed.reading!!.text)
        assertTrue(displayed.original!!.ruby.isEmpty())
        val multiline = document.copy(lines = document.lines.map { line -> line.copy(parts = line.parts.map {
            if (it.role == LyricTextRole.TRANSLATION) it.copy(text = "今天\n风也在吹") else it
        }) })
        val collapsed = project(multiline, split = false).forExternalDisplay(LyricsBilingualDisplayMode.ALL)
        assertEquals("かぜきょう", collapsed.reading!!.text)
        assertTrue(collapsed.original!!.ruby.isEmpty())
    }

    @Test fun collapsedBilingualTextRetainsOnlyOriginalPrefixRanges() {
        val line = project(split = false).forExternalDisplay(LyricsBilingualDisplayMode.ALL)
        assertEquals("風が今日も 今天风也在吹", line.original!!.text)
        assertEquals(ruby, line.original!!.ruby)
        assertNull(line.reading)
    }

    @Test fun sameTextWithNewReadingReachesBothCurrentLineSnapshots() {
        val store = DesktopLyricsOverlayStateStore()
        val old = project()
        store.publish(old, 1500, true, false)
        val newReading = listOf(LyricRuby(0, 1, "ふう"), LyricRuby(2, 4, "こんじつ"))
        val fresh = old.copy(rows = old.rows.map { row -> when (row.role) {
            LyricTextRole.ORIGINAL -> row.copy(text = row.text.copy(ruby = newReading))
            LyricTextRole.READING -> row.copy(text = row.text.copy(text = "ふうこんじつ"))
            else -> row
        } })
        store.publish(fresh, 1500, false, true)
        assertEquals(newReading, store.state.value.desktop.line!!.original!!.ruby)
        assertEquals(newReading, store.state.value.statusBar.line!!.original!!.ruby)
        store.clear()
        assertNull(store.state.value.desktop.line)
        assertNull(store.state.value.statusBar.line)
    }

    @Test fun timedWholeReadingKeepsPositionAnchorsWhenOriginalHasNoCues() {
        val store = DesktopLyricsOverlayStateStore()
        val readingOnlyTiming = project().copy(rows = listOf(
            ExternalLyricsRow(LyricTextRole.READING, ExternalLyricsText("かぜ", listOf(LyricCue(1000, "かぜ", 2000)))),
            ExternalLyricsRow(LyricTextRole.ORIGINAL, ExternalLyricsText("風")),
        ))
        store.publish(readingOnlyTiming, 1100, true, false)
        store.setPlaying(true)
        assertTrue(store.needsPositionUpdates())
        store.updatePosition(1500)
        assertEquals(1500, store.state.value.desktop.positionMs)
    }
}
