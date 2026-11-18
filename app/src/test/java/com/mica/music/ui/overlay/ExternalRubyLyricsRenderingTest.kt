package com.mica.music.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.*
import com.mica.music.data.*
import com.mica.music.media.*
import com.mica.music.ui.theme.MicaTheme
import java.io.File
import kotlin.math.abs
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
class ExternalRubyLyricsRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val ruby = listOf(LyricRuby(0, 1, "かぜ"), LyricRuby(2, 4, "きょう"))
    private val text = ExternalLyricsText("風が今日も", listOf(LyricCue(1000, "風が", 2000), LyricCue(2000, "今日も", 4000)), ruby)
    private val line = ExternalLyricsLine(0, 1000, 4000, original = text)
    private val plain = ExternalLyricsStyle(colorsArgb = listOf(0xFFFF0000.toInt()),
        unfilledColorArgb = 0xFF0000FF.toInt(), unfilledOpacityPercent = 100,
        shadowStrengthPercent = 0, glowStrengthPercent = 0)

    @Test fun filledAndUnfilledOpacityAndSeekApplyToBothBaseAndReading() {
        val position = mutableIntStateOf(1500)
        val style = mutableStateOf(plain)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MicaTheme(darkTheme = true) {
                    Box(Modifier.width(300.dp).background(Color.Black).testTag("pixels")) {
                        ExternalLyricsLineText(text, line, position.intValue, 28, style.value, false, TextAlign.Start)
                    }
                }
            }
        }
        fun pixels() = pixelsOf("pixels")
        val half = pixels()
        val readingEnd = (half.height / 3).coerceAtLeast(1)
        assertTrue(countRed(half, 0, readingEnd) > 5)
        assertTrue(countRed(half, readingEnd, half.height) > 5)
        assertTrue(countBlue(half) > 5)
        compose.runOnIdle { position.intValue = 1000 }
        assertEquals(0, countRed(pixels()))
        compose.runOnIdle { position.intValue = 1500; style.value = plain.copy(opacityPercent = 0) }
        assertEquals(0, countRed(pixels()))
        assertTrue(countBlue(pixels()) > 5)
        compose.runOnIdle { style.value = plain.copy(opacityPercent = 0, unfilledOpacityPercent = 0) }
        assertEquals(0, countBlue(pixels()))
        compose.runOnIdle { style.value = plain.copy(unfilledOpacityPercent = 0) }
        assertTrue(countRed(pixels()) > 5)
        assertEquals(0, countBlue(pixels()))
    }

    @Test fun oneMarqueeMovesBaseAndReadingByTheSameDistance() {
        compose.mainClock.autoAdvance = false
        // Keep the first annotated group outside the edge-fade mask during both captures.
        val scrolling = ExternalLyricsText("　　風が今日も " + "A".repeat(30), ruby = listOf(
            LyricRuby(2, 3, "かぜ"), LyricRuby(4, 6, "きょう")))
        val scrollingLine = ExternalLyricsLine(0, 1000, 4000, original = scrolling)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MicaTheme(darkTheme = true) {
                    Box(Modifier.width(210.dp).background(Color.Black).testTag("scroll")) {
                        ExternalLyricsLineText(scrolling, scrollingLine, 4000, 24, plain, true, TextAlign.Start)
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = pixelsOf("scroll")
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
        val after = pixelsOf("scroll")
        val split = before.height / 3
        val rubyShift = firstRedX(after, 0, split) - firstRedX(before, 0, split)
        val baseShift = firstRedX(after, split, after.height) - firstRedX(before, split, before.height)
        assertTrue("ruby=$rubyShift base=$baseShift", rubyShift < 0 && baseShift < 0)
        assertTrue("ruby=$rubyShift base=$baseShift", abs(rubyShift - baseShift) <= 1)
    }

    @Test fun captureDesktopStatusBarAndFallbackWithExistingEffects() {
        val gradient = plain.copy(colorMode = ExternalLyricsColorMode.GRADIENT,
            colorsArgb = listOf(0xFFFFCC66.toInt(), 0xFFFF66CC.toInt(), 0xFF66CCFF.toInt()),
            unfilledColorArgb = 0xFFDDDDDD.toInt(), unfilledOpacityPercent = 42,
            opacityPercent = 80, glowStrengthPercent = 50, shadowStrengthPercent = 75)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MicaTheme(darkTheme = true) {
                    Column(Modifier.width(360.dp).background(Color(0xFF141820)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("桌面 · 渐变 / 发光 / 阴影", color = Color.LightGray)
                        ExternalLyricsLineText(text, line, 1500, 30, gradient, false, TextAlign.Center)
                        Text("状态栏 · 小字号 / 居左", color = Color.LightGray)
                        ExternalLyricsLineText(text, line, 2500, 14, gradient.copy(glowStrengthPercent = 0), false, TextAlign.Start)
                        Text("状态栏 · 大字体 / 居右", color = Color.LightGray)
                        CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                            ExternalLyricsLineText(text, line, 4000, 22, gradient, false, TextAlign.End)
                        }
                        Text("未知对应 · 保留整行读音", color = Color.LightGray)
                        val fallback = line.copy(rows = listOf(
                            ExternalLyricsRow(LyricTextRole.READING, ExternalLyricsText("かぜがきょうも")),
                            ExternalLyricsRow(LyricTextRole.ORIGINAL, text.copy(ruby = emptyList())),
                        )).forExternalDisplay(LyricsBilingualDisplayMode.ALL)
                        fallback.rows.forEach {
                            ExternalLyricsLineText(it.text, fallback, 1500,
                                if (it.role == LyricTextRole.READING) 16 else 26, gradient, false, TextAlign.Center)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val output = File("build/reports/external-ruby-preview.png")
        output.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(output.absolutePath, RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    private fun countRed(pixels: PixelMap, from: Int = 0, to: Int = pixels.height): Int = countPixels(pixels, from, to) {
        it.red > 0.7f && it.green < 0.2f && it.blue < 0.2f
    }
    private fun countBlue(pixels: PixelMap): Int = countPixels(pixels, 0, pixels.height) {
        it.blue > 0.7f && it.red < 0.2f && it.green < 0.2f
    }
    private fun countPixels(pixels: PixelMap, from: Int, to: Int, test: (Color) -> Boolean): Int {
        var count = 0
        for (y in from until to) for (x in 0 until pixels.width) if (test(pixels[x, y])) count++
        return count
    }
    private fun firstRedX(pixels: PixelMap, from: Int, to: Int): Int {
        for (x in 0 until pixels.width) for (y in from until to) {
            val color = pixels[x, y]
            if (color.red > 0.7f && color.green < 0.2f && color.blue < 0.2f) return x
        }
        error("No filled pixels in region $from..$to")
    }

    private var captureIndex = 0
    private fun pixelsOf(tag: String): PixelMap {
        val output = File("build/reports/external-ruby/$tag-${captureIndex++}.png")
        output.parentFile?.mkdirs()
        compose.onNodeWithTag(tag).captureRoboImage(output.absolutePath, RoborazziOptions(taskType = RoborazziTaskType.Record))
        return android.graphics.BitmapFactory.decodeFile(output.absolutePath).asImageBitmap().toPixelMap()
    }
}
