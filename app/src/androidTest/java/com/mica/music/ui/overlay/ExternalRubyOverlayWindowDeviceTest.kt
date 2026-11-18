package com.mica.music.ui.overlay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.MicaApp
import com.mica.music.data.*
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.scanner.LyricsSanitizer
import com.mica.music.lyrics.LyricsDisplayOptions
import com.mica.music.media.buildExternalLyricsLine
import com.mica.music.ui.screens.B3LyricsVisualQaActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Service + WindowManager windows in an isolated QA package; no audio, files or user library. */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class ExternalRubyOverlayWindowDeviceTest {
    @Test fun sourceRubyIsVisibleInsideBothRealOverlayWindowsAndClearsOnPause() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Use the isolated QA application", context.packageName == "com.mica.music.qa")
        assumeTrue(Build.VERSION.SDK_INT >= 29 && Settings.canDrawOverlays(context))
        val app = context.applicationContext as MicaApp
        val previousMode = LyricsPreferences.externalLyricsMode(context)
        val previousReading = LyricsPreferences.lyricReadingEnabled(context)
        val previousDesktopSize = LyricsPreferences.desktopLyricsOriginalFontSizeSp(context)
        val previousStatusSize = LyricsPreferences.statusBarLyricsOriginalFontSizeSp(context)
        val previousOffset = LyricsPreferences.statusBarLyricsHorizontalOffsetDp(context)
        val evidence = File(context.getExternalFilesDir(null), "external-ruby-window").apply { mkdirs() }
        val resumed = java.util.concurrent.atomic.AtomicReference<B3LyricsVisualQaActivity>()
        val ready = CountDownLatch(1)
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is B3LyricsVisualQaActivity) { resumed.set(activity); ready.countDown() }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(observer)
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun windows(): List<View> {
            var roots: List<View> = emptyList()
            main { roots = WindowInspector.getGlobalWindowViews().filter {
                (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                    it.isAttachedToWindow && it.height > context.resources.displayMetrics.density * 10
            } }
            return roots
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(30)
            assertTrue(label, condition())
        }
        try {
            val component = "${context.packageName}/${B3LyricsVisualQaActivity::class.java.name}"
            val launch = instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n $component")
                .let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
            assertTrue(launch, launch.contains("Status: ok"))
            assertTrue("QA host did not resume", ready.await(10, TimeUnit.SECONDS))
            main { resumed.get().setContent { Box(Modifier.fillMaxSize().background(Color.Black)) } }
            val document = LyricsSanitizer.parseFilteredDocument("""<tt xmlns:tts="http://www.w3.org/ns/ttml#styling"><body><p begin="1s" end="10s"><span tts:ruby="container"><span tts:ruby="base">風</span><span tts:ruby="text">かぜ</span></span>が<span tts:ruby="container"><span tts:ruby="base">今日</span><span tts:ruby="text">きょう</span></span>も</p></body></tt>""")
            val line = buildExternalLyricsLine(document, document.toLegacyLyricLines(), 0,
                LyricsDisplayOptions(true, LyricsBilingualDisplayMode.ORIGINAL, readingEnabled = true))!!
            assertEquals(2, line.original!!.ruby.size)
            main {
                LyricsPreferences.setLyricReadingEnabled(context, true)
                LyricsPreferences.setDesktopLyricsOriginalFontSizeSp(context, 26)
                LyricsPreferences.setStatusBarLyricsOriginalFontSizeSp(context, 16)
                // Put the text away from the center camera cutout; preserve the user's position afterwards.
                LyricsPreferences.setStatusBarLyricsHorizontalOffsetDp(context, -60)
            }
            for (mode in listOf(ExternalLyricsMode.DESKTOP, ExternalLyricsMode.STATUS_BAR)) {
                main {
                    LyricsPreferences.setExternalLyricsMode(context, mode)
                    context.startService(Intent(context, DesktopLyricsOverlayService::class.java)
                        .setAction(DesktopLyricsOverlayService.ACTION_APPLY_SETTINGS))
                    app.desktopLyricsOverlayStateStore.publish(line, 2000, mode == ExternalLyricsMode.DESKTOP, mode == ExternalLyricsMode.STATUS_BAR)
                    app.desktopLyricsOverlayStateStore.setPlaying(true)
                }
                await("one visible $mode window") { windows().size == 1 }
                // Complete the real window fade; no test clock replaces the Service's frame clock.
                Thread.sleep(300)
                val bounds = Rect()
                val view = windows().single()
                main {
                    val xy = IntArray(2)
                    view.getLocationOnScreen(xy)
                    bounds.set(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
                }
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(evidence, "$mode-screen.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val center = bounds.centerX()
                val radius = (context.resources.displayMetrics.density * 65).toInt()
                val crop = Rect(maxOf(0, center - radius), maxOf(0, bounds.top),
                    minOf(screenshot.width, center + radius), minOf(screenshot.height, bounds.bottom))
                assertTrue("clipped window: $bounds", crop.height() >= context.resources.displayMetrics.density * 25)
                var above = 0
                var below = 0
                for (y in crop.top until crop.bottom) for (x in crop.left until crop.right) {
                    val pixel = screenshot.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) > 180 && android.graphics.Color.blue(pixel) > 180) {
                        if (y < crop.top + crop.height() * 0.4f) above++ else below++
                    }
                }
                assertTrue("missing reading/base pixels: $mode $above/$below bounds=$bounds", above > 10 && below > 20)
                File(evidence, "assertions.txt").appendText("PASS $mode bounds=$bounds readingPixels=$above basePixels=$below\n")
                main { app.desktopLyricsOverlayStateStore.setPlaying(false) }
                await("pause hides $mode") { windows().isEmpty() }
            }
        } finally {
            main {
                app.desktopLyricsOverlayStateStore.clear()
                context.stopService(Intent(context, DesktopLyricsOverlayService::class.java))
                LyricsPreferences.setExternalLyricsMode(context, previousMode)
                LyricsPreferences.setLyricReadingEnabled(context, previousReading)
                LyricsPreferences.setDesktopLyricsOriginalFontSizeSp(context, previousDesktopSize)
                LyricsPreferences.setStatusBarLyricsOriginalFontSizeSp(context, previousStatusSize)
                LyricsPreferences.setStatusBarLyricsHorizontalOffsetDp(context, previousOffset)
                resumed.get()?.finish()
            }
            app.unregisterActivityLifecycleCallbacks(observer)
        }
    }
}
