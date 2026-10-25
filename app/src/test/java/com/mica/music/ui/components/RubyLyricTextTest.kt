package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.*
import com.mica.music.data.*
import com.mica.music.ui.screens.letterDisplayPieces
import com.mica.music.ui.screens.splitLetterRubySegments
import com.mica.music.ui.screens.lyricsCloudDocumentSeed
import com.mica.music.ui.screens.LetterLyricsPrototype
import com.mica.music.ui.screens.LyricsCloudPanel
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class RubyLyricTextTest {
    @get:Rule val compose = createComposeRule()
    private val parts = listOf(
        LyricTextPart(LyricTextRole.READING, "かぜきょう"),
        LyricTextPart(LyricTextRole.ORIGINAL, "風が今日も", listOf(LyricRuby(0, 1, "かぜ"), LyricRuby(2, 4, "きょう"))),
        LyricTextPart(LyricTextRole.TRANSLATION, "今天风也在吹"),
    )

    @Test fun sourceGroupsStayIntactAcrossWrappingAndVerticalColumns() {
        val text = "風が今日も"
        val ruby = parts[1].ruby
        assertEquals(listOf("風", "が", "今日", "も"), rubyTextUnits(text, ruby).map { it.text })
        assertEquals(listOf("風が", "今日", "も"), splitLetterRubySegments(text, ruby, 2))
        val line = LyricLineNode("line", 1000, 3000, parts)
        val columns = letterDisplayPieces(line, LyricsBilingualDisplayMode.ALL, true, true)
        assertEquals(ruby, columns.first().ruby)
        assertEquals(2, columns.size)
        val rows = LyricDisplayRows.rowsFromPartsWithRuby(parts, emptyList())!!
        assertEquals(listOf(LyricTextRole.ORIGINAL, LyricTextRole.TRANSLATION), rows.map { it.role })
        assertTrue(LyricDisplayRows.rowsFromPartsWithRuby(parts, emptyList(), readingEnabled = false)!!.all { it.ruby.isEmpty() })
        assertEquals(listOf(LyricTextRole.TRANSLATION), LyricDisplayRows.rowsFromPartsWithRuby(parts, emptyList(), LyricsBilingualDisplayMode.TRANSLATION)!!.map { it.role })
        val unknown = parts.map { it.copy(ruby = emptyList()) }
        assertEquals(LyricTextRole.READING, LyricDisplayRows.rowsFromPartsWithRuby(unknown, emptyList())!!.first().role)
        assertNotEquals(lyricsCloudDocumentSeed(LyricsDocument(lines = listOf(line))),
            lyricsCloudDocumentSeed(LyricsDocument(lines = listOf(line.copy(parts = unknown)))))
    }

    @Test fun rubyLayoutKeepsBaseBaselineAndCentersWideReadingsAtLargeFontScale() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) {
                val measurer = rememberTextMeasurer()
                val style = TextStyle(fontSize = 24.sp, lineHeight = 32.sp)
                val natural = measureRubyText(parts[1].text, parts[1].ruby, style, measurer)
                val width = natural.units.take(2).sumOf { it.width }
                val geometry = measureRubyText(parts[1].text, parts[1].ruby, style, measurer, maxWidth = width)
                SideEffect {
                    assertTrue(geometry.units.size >= 4)
                    assertEquals(geometry.units[0].y, geometry.units[1].y)
                    assertTrue(geometry.units.first { it.unit.text == "今日" }.y > 0)
                    geometry.units.forEach {
                        assertTrue(it.x >= 0 && it.x + it.width <= width)
                        assertTrue(it.width >= it.base.size.width)
                        assertTrue(it.width >= (it.reading?.size?.width ?: 0))
                    }
                }
                RubyLyricText(parts[1].text, parts[1].ruby, style, Color.White,
                    modifier = Modifier.width(160.dp))
            }
        }
        compose.waitForIdle()
    }

    @Test fun captureAlignedWrappedAndFallbackRows() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                val style = TextStyle(fontSize = 28.sp, lineHeight = 38.sp)
                val colors = PlayerContentColors(Color.White, Color.LightGray, Color.Gray)
                Column(Modifier.width(360.dp).background(Color(0xFF141820)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("明确对应 · 原文与注音成组", color = Color.LightGray)
                    LyricLineBlock(null, true, colors, style, parts = parts, maxLines = 4)
                    Text("窄屏换行 · 今日不拆开", color = Color.LightGray)
                    Box(Modifier.width(130.dp)) {
                        LyricLineBlock(null, true, colors, style, parts = parts, maxLines = 8, textAlign = TextAlign.Start)
                    }
                    Text("无法确定 · 保留整行", color = Color.LightGray)
                    LyricLineBlock(null, true, colors, style, parts = parts.map { it.copy(ruby = emptyList()) }, maxLines = 4)
                }
            }
        }
        compose.waitForIdle()
        val output = File("build/reports/lyrics-ruby-preview.png")
        output.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(output.absolutePath, RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    @Test fun captureCloudAndVerticalSourceRuby() {
        val state = LyricsSession(LyricsDocument(lines = listOf(LyricLineNode("ruby", 1000, 4000, parts))))
            .snapshotAt(4000)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f), MicaMotion.LocalEnabled provides false) {
                MicaTheme(darkTheme = true) {
                    Column(Modifier.width(360.dp)) {
                        Box(Modifier.height(360.dp)) {
                            LetterLyricsPrototype(state, false, LyricsBilingualDisplayMode.ALL, null, 32, 80, 0)
                        }
                        Box(Modifier.height(240.dp).background(Color(0xFF141820))) {
                            LyricsCloudPanel(state, false, true, PlayerContentColors(Color.White, Color.LightGray, Color.Gray),
                                {}, LyricsBilingualDisplayMode.ALL)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val output = File("build/reports/lyrics-ruby-themes.png")
        output.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(output.absolutePath, RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
