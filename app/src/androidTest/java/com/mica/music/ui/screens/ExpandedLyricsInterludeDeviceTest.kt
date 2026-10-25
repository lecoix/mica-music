package com.mica.music.ui.screens

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.data.LyricLine
import com.mica.music.data.renderStateAt
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production panel + captured pixels. Generated fixture, no user songs or player writes. */
@RunWith(AndroidJUnit4::class)
class ExpandedLyricsInterludeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: B3LyricsVisualQaActivity
    private lateinit var evidence: File
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private val position = mutableIntStateOf(10_000)
    private val revision = mutableLongStateOf(1L)
    private val anchor = mutableIntStateOf(25)
    private val font = mutableIntStateOf(34)
    private val frames = mutableListOf<ExpandedLyricsMotionTrace>()
    private val lyrics = listOf(
        LyricLine(1_000, "第一句，正常短歌词。", endTimeMs = 3_900),
        LyricLine(5_000, "第二句，逐行滚动。", endTimeMs = 8_800),
        LyricLine(9_000, "第三句，当前行加粗会改变测量高度。", endTimeMs = 12_000),
        LyricLine(13_000, "间奏前最后一句", endTimeMs = 17_000),
        LyricLine(29_000, "间奏之后，下方歌词应保持稳定", endTimeMs = 34_000),
        LyricLine(35_000, "这一句很长很长，用来验证歌词加粗和自动换行产生的行高变化，不能让离场歌词向下飞。", endTimeMs = 42_000),
        LyricLine(43_000, "第七句，继续滚动。", endTimeMs = 48_000),
        LyricLine(49_000, "第二次间奏前一句", endTimeMs = 55_000),
        LyricLine(68_000, "第二次间奏后一句歌词", endTimeMs = 73_000),
        LyricLine(74_000, "测试尾声", endTimeMs = 82_000),
    )

    @Before fun launchQaHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.mica.music.qa") { "Side-by-side QA package required" }
        evidence = File(context.getExternalFilesDir(null), "interlude-flash").apply { mkdirs() }
        File(evidence, "assertions.txt").writeText("Production ExpandedLyricsPanel, controlled frame clock. Red current text identifies actual pixels; other rows green.\n")
        val resumed = AtomicReference<B3LyricsVisualQaActivity?>()
        val latch = CountDownLatch(1)
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(value: Activity) {
                if (value is B3LyricsVisualQaActivity) { resumed.set(value); latch.countDown() }
            }
            override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityPaused(value: Activity) = Unit
            override fun onActivityStopped(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(value: Activity) = Unit
        }
        callbacks = observer
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(observer)
        val output = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n ${context.packageName}/${B3LyricsVisualQaActivity::class.java.name}",
        ).let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
        assertTrue(output, output.contains("Status: ok") && latch.await(10, TimeUnit.SECONDS))
        activity = checkNotNull(resumed.get())
        compose.mainClock.autoAdvance = false
        instrumentation.runOnMainSync {
            activity.setContent {
                MicaTheme(darkTheme = true) {
                    Column(Modifier.fillMaxSize().background(Color.Black)) {
                        Box(Modifier.fillMaxWidth().height(154.dp))
                        ExpandedLyricsPanel(
                            renderState = lyrics.renderStateAt(position.intValue).copy(positionRevision = revision.longValue),
                            isPlaying = true,
                            colors = PlayerContentColors(Color.Red, Color.Green, Color.Green),
                            onLineClick = {},
                            modifier = Modifier.weight(1f).fillMaxWidth().testTag("lyrics"),
                            lyricsFontSizeSp = font.intValue,
                            lyricsLineSpacingDp = 18,
                            lyricsCurrentLinePositionPercent = anchor.intValue,
                            debugMotionTrace = { frames.add(it) },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(900)
        compose.waitForIdle()
    }

    @After fun closeQaHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
        callbacks?.let { (instrumentation.targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(it) }
    }

    @Test fun actualUpperTextPixelsMoveContinuouslyAcrossTwoInterludesAndLargeFont() {
        verifyOpening(13_000, 17_000, 4, 25, 34)
        verifyOpening(49_000, 55_000, 8, 25, 34)
        verifyOpening(13_000, 17_000, 4, 60, 42)
    }

    private fun verifyOpening(start: Int, boundary: Int, nextIndex: Int, anchorPercent: Int, fontSp: Int) {
        val label = "${boundary}_${anchorPercent}_${fontSp}"
        compose.runOnIdle {
            anchor.intValue = anchorPercent; font.intValue = fontSp
            position.intValue = start; revision.longValue++
        }
        compose.mainClock.advanceTimeBy(900)
        compose.waitForIdle()
        for (ms in start + 100 until boundary step 100) {
            compose.runOnIdle { position.intValue = ms }
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
        val pixels = mutableListOf(captureRedCenter("${label}_before"))
        val before = frames.last()
        val lowerKey = before.items.first { it.key.startsWith("line-$nextIndex-") }.key
        val lowerY = before.items.first { it.key == lowerKey }.visualRootYPx
        compose.runOnIdle { frames.clear(); position.intValue = boundary }
        repeat(32) { index ->
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            pixels.add(captureRedCenter(if (index < 6 || index == 31) "${label}_frame$index" else null))
        }
        val steps = pixels.zipWithNext { a, b -> b - a }
        val density = activity.resources.displayMetrics.density
        File(evidence, "assertions.txt").appendText("$label redPixelY=$pixels steps=$steps\n")
        assertTrue("$label current text must rise", pixels.last() < pixels.first() - 12f * density)
        assertTrue("$label visible downward flash: $steps", steps.all { it <= 2f })
        assertTrue("$label visible endpoint jump: $steps", steps.all { it >= -6f * density })
        val lowerDrift = frames.mapNotNull { f -> f.items.firstOrNull { it.key == lowerKey }?.let {
            kotlin.math.abs(it.visualRootYPx - lowerY + f.followConsumedPx - before.followConsumedPx)
        } }.maxOrNull() ?: error("No next-line geometry")
        assertTrue("$label lower line moved $lowerDrift", lowerDrift < 8f)
        File(evidence, "assertions.txt").appendText("PASS $label lowerDrift=$lowerDrift\n")
    }

    private fun captureRedCenter(name: String?): Float {
        val bitmap = compose.onNodeWithTag("lyrics").captureToImage().asAndroidBitmap()
        if (name != null) File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val row = IntArray(bitmap.width)
        var count = 0L
        var yTotal = 0L
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            row.forEach { pixel ->
                val r = (pixel ushr 16) and 255
                val g = (pixel ushr 8) and 255
                val b = pixel and 255
                if (r > 100 && r > 2 * g && r > 2 * b) { count++; yTotal += y }
            }
        }
        assertTrue("Current text pixels missing", count > 100)
        return yTotal.toFloat() / count
    }
}
