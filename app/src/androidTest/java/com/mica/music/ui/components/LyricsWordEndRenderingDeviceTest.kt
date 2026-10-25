package com.mica.music.ui.components

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSession
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.screens.B3LyricsVisualQaActivity
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

/** QA-only real component drawing; does not claim navigation, playback or desktop-window coverage. */
@RunWith(AndroidJUnit4::class)
class LyricsWordEndRenderingDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var activity: B3LyricsVisualQaActivity
    private lateinit var evidenceDir: File
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private val position = mutableIntStateOf(900)
    private val preset = mutableStateOf(LyricsWordAnimationPreset.HARD_FILL)
    private val whiteReference = mutableStateOf(false)

    @Before
    fun launchQaHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Requires -Pmica.qaSideBySide=true: ${context.packageName}", context.packageName.endsWith(".qa"))
        evidenceDir = File(context.filesDir, "lyrics-end-qa").apply { mkdirs() }
        File(evidenceDir, "assertions.txt").writeText(
            "Generated Chinese fixture; component rendering only.\n" +
                "我却:1000..1200, 不能忘:2000..2200, line end:5000, next line:20000.\n" +
                "Static black background, white filled, gray unfilled; decorative motion disabled.\n",
        )
        val resumed = AtomicReference<B3LyricsVisualQaActivity?>()
        val latch = CountDownLatch(1)
        val application = context.applicationContext as Application
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
        application.registerActivityLifecycleCallbacks(observer)
        // Same shell launch as MainActivitySettingsFlowTest: avoids MIUI startActivitySync hangs.
        val component = "${context.packageName}/${B3LyricsVisualQaActivity::class.java.name}"
        val output = instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n $component")
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
        assertTrue("QA host launch failed: $output", output.contains("Status: ok"))
        assertTrue("QA host did not resume: $output", latch.await(10, TimeUnit.SECONDS))
        activity = checkNotNull(resumed.get())

        val node = LyricLineNode(
            "word-end-device-fixture", 1000, 5000,
            listOf(LyricTextPart(LyricTextRole.ORIGINAL, "我却不能忘")),
            listOf(LyricToken("我却", 1000, 1200), LyricToken("不能忘", 2000, 2200)),
        )
        val line = LyricsSession(LyricsDocument(lines = listOf(node))).lyrics.single()
        instrumentation.runOnMainSync {
            // Replace this debug host's content in this test only; its source remains untouched.
            activity.setContent {
                MicaTheme(darkTheme = true) {
                    CompositionLocalProvider(MicaMotion.LocalEnabled provides false) {
                        val mode = preset.value
                        val unfilled = if (whiteReference.value) Color.White else Color(0xFF606060)
                        val style = TextStyle(fontSize = 32.sp, lineHeight = 48.sp)
                        Column {
                            Box(
                                Modifier.size(280.dp, 100.dp).background(Color.Black).testTag("classic"),
                                contentAlignment = Alignment.Center,
                            ) {
                                LyricLineBlock(
                                    text = line.text, isCurrent = true,
                                    colors = PlayerContentColors(Color.White, unfilled, unfilled),
                                    textStyle = style, lyricLine = line,
                                    parts = node.parts, tokens = node.tokens,
                                    positionMs = position.intValue, nextLineTimeMs = 20000, isPlaying = false,
                                    karaokeSyllableLift = mode.syllableLiftEnabled,
                                    karaokeDiscreteActiveCue = mode.usesDiscreteCueFill,
                                    karaokeWordFadeWidthEm = mode.wordFadeWidthEm,
                                )
                            }
                            Box(
                                Modifier.size(280.dp, 100.dp).background(Color.Black).testTag("narrow"),
                                contentAlignment = Alignment.Center,
                            ) {
                                NarrowBarSoftKaraokeLyric(
                                    line = line, positionMs = position.intValue, isPlaying = false,
                                    nextLineTimeMs = 20000, filledColor = Color.White,
                                    unfilledColor = unfilled, textStyle = style,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun closeQaHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
        callbacks?.let { (instrumentation.targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(it) }
        callbacks = null
    }

    @Test
    fun realChineseWordRenderingStopsAtSourceEndsAcrossFourPresets() {
        val times = listOf(900, 1000, 1100, 1200, 1600, 2100, 2200, 6000, 10000)
        for (mode in LyricsWordAnimationPreset.entries) {
            compose.runOnIdle { preset.value = mode; whiteReference.value = false }
            val images = times.associateWith { time ->
                compose.runOnIdle { position.intValue = time }
                compose.waitForIdle()
                listOf("classic", "narrow").associateWith { surface -> capture(surface, "${surface}_${mode.storageValue}_${time}") }
            }
            compose.runOnIdle { position.intValue = 900; whiteReference.value = true }
            compose.waitForIdle()
            for (surface in listOf("classic", "narrow")) {
                val reference = capture(surface, "${surface}_${mode.storageValue}_all_white_reference")
                fun image(time: Int) = images.getValue(time).getValue(surface)
                assertTrue("$surface $mode: first word continued during silence", image(1200).sameAs(image(1600)))
                assertTrue("$surface $mode: last word continued into line gap", image(2200).sameAs(image(6000)))
                assertTrue("$surface $mode: last word continued toward next line", image(2200).sameAs(image(10000)))
                assertFullBrightInterior("$surface $mode", reference, image(2200))
                assertTrue("$surface $mode: fixture never changed from unfilled", !image(900).sameAs(image(2200)))
                if (surface == "narrow" || mode != LyricsWordAnimationPreset.WORD_STEP) {
                    assertTrue("$surface $mode: missing visible word animation", !image(1100).sameAs(image(1200)))
                } else {
                    assertTrue("$surface $mode: step mode must light a word immediately", image(1000).sameAs(image(1200)))
                }
                val result = "PASS $surface ${mode.storageValue}: 1200=1600; 2200=6000=10000; fully bright interiors.\n"
                File(evidenceDir, "assertions.txt").appendText(result)
                Log.i("LyricsEndQa", result.trim())
            }
        }
    }

    private fun capture(surface: String, name: String): Bitmap {
        val bitmap = compose.onNodeWithTag(surface).captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        File(evidenceDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun assertFullBrightInterior(label: String, reference: Bitmap, actual: Bitmap) {
        assertTrue("$label: capture dimensions changed", reference.width == actual.width && reference.height == actual.height)
        val expectedPixels = IntArray(reference.width * reference.height)
        val actualPixels = IntArray(expectedPixels.size)
        reference.getPixels(expectedPixels, 0, reference.width, 0, 0, reference.width, reference.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        var interiors = 0
        var bright = 0
        for (index in expectedPixels.indices) {
            // Exclude anti-aliased glyph edges, which composite differently over gray and white.
            if (android.graphics.Color.red(expectedPixels[index]) >= 250) {
                interiors++
                if (android.graphics.Color.red(actualPixels[index]) >= 245) bright++
            }
        }
        assertTrue("$label: empty or clipped reference glyphs ($interiors)", interiors > 30)
        assertTrue("$label: last word is not fully bright: $bright/$interiors", bright.toDouble() / interiors >= 0.98)
    }
}
