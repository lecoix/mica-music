package com.mica.music.flow

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.MicaApp
import com.mica.music.MicaMainActivity
import com.mica.music.data.*
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.data.preferences.UsageTutorialPreferences
import com.mica.music.testutil.ContractTestSupport.await
import com.mica.music.testutil.ContractTestSupport.connectMediaService
import com.mica.music.testutil.ContractTestSupport.createSilentWav
import com.mica.music.testutil.ContractTestSupport.onMain
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Queue/catalog and preferences are fixture setup; navigation, playback and projection are real. */
@RunWith(AndroidJUnit4::class)
class LyricsWordEndPlaybackFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val restore = mutableListOf<() -> Unit>()
    private var service: MediaController? = null
    private var wav: File? = null
    private lateinit var output: File
    private lateinit var fixtureId: String
    private val lineText = "我却不能忘"

    private lateinit var qaActivity: MicaMainActivity
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    @Before
    fun launchQaActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(
            "Device flow tests must use -Pmica.qaSideBySide=true; actual=${context.packageName}",
            context.packageName.endsWith(".qa"),
        )

        configureQa(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        val application = context.applicationContext as Application
        val resumedActivity = AtomicReference<MicaMainActivity?>()
        val resumedLatch = CountDownLatch(1)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity is MicaMainActivity) {
                    resumedActivity.set(activity)
                    resumedLatch.countDown()
                }
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        lifecycleCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)

        // ActivityScenario uses Instrumentation.startActivitySync(), which can hang on MIUI 13 /
        // Android 12 even though the same explicit QA activity starts normally from shell.
        // Launch through UiAutomation's shell identity while keeping the real activity and Compose UI.
        val component = "${context.packageName}/${MicaMainActivity::class.java.name}"
        val shellOutput = instrumentation.uiAutomation
            .executeShellCommand("am start -W -f 0x10008000 -n $component")
            .let { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    .bufferedReader()
                    .use { it.readText() }
            }
        assertTrue("QA MicaMainActivity shell launch failed: $shellOutput", shellOutput.contains("Status: ok"))
        assertTrue(
            "QA MicaMainActivity did not reach RESUMED; shell=$shellOutput",
            resumedLatch.await(10, TimeUnit.SECONDS),
        )
        qaActivity = checkNotNull(resumedActivity.get())
        compose.waitForIdle()
    }

    @After
    fun closeActivity() {
        cleanupQa()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (::qaActivity.isInitialized) {
            instrumentation.runOnMainSync {
                if (!qaActivity.isFinishing) qaActivity.finish()
            }
            instrumentation.waitForIdleSync()
        }
        lifecycleCallbacks?.let { callbacks ->
            val application = instrumentation.targetContext.applicationContext as Application
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
        lifecycleCallbacks = null
    }

    private fun configureQa(context: Context) {
        output = File(context.filesDir, "lyrics-end-flow").apply { mkdirs() }
        File(output, "evidence.txt").writeText(
            "Fixture queue/catalog configured by test; no library selection claim.\n" +
                "Generated 30s silent WAV. 我却:1000..4000, 不能忘:10000..12000; line end:22000, next line:29000.\n",
        )
        val oldTheme = LyricsPreferences.lyricsPageTheme(context)
        val oldPreset = LyricsPreferences.lyricsWordAnimationPreset(context)
        val oldOffset = LyricsPreferences.globalLyricsOffsetMs(context)
        val oldExternal = LyricsPreferences.externalLyricsMode(context)
        val oldVisibility = LyricsPreferences.externalLyricsVisibilityMode(context)
        val oldWords = LyricsPreferences.desktopLyricsWordByWordEnabled(context)
        val oldCover = PlaybackUiPreferences.playerCoverFlowMode(context)
        val oldBilingual = LyricsPreferences.lyricsBilingualDisplayMode(context)
        val tutorial = context.getSharedPreferences("mica_usage_tutorial", Context.MODE_PRIVATE)
        val oldCompleted = tutorial.all["completed"] as? Boolean
        val oldPage = tutorial.all["page"] as? Int
        restore += {
            LyricsPreferences.setLyricsPageTheme(context, oldTheme)
            LyricsPreferences.setLyricsWordAnimationPreset(context, oldPreset)
            LyricsPreferences.setGlobalLyricsOffsetMs(context, oldOffset)
            LyricsPreferences.setExternalLyricsMode(context, oldExternal)
            LyricsPreferences.setExternalLyricsVisibilityMode(context, oldVisibility)
            LyricsPreferences.setDesktopLyricsWordByWordEnabled(context, oldWords)
            PlaybackUiPreferences.setPlayerCoverFlowMode(context, oldCover)
            LyricsPreferences.setLyricsBilingualDisplayMode(context, oldBilingual)
            tutorial.edit().apply {
                if (oldCompleted == null) remove("completed") else putBoolean("completed", oldCompleted)
                if (oldPage == null) remove("page") else putInt("page", oldPage)
            }.apply()
            (context.applicationContext as MicaApp).externalLyricsOverlayControl.sync()
        }
        onMain {
            LyricsPreferences.setLyricsPageTheme(context, LyricsPageTheme.LIST)
            LyricsPreferences.setLyricsWordAnimationPreset(context, LyricsWordAnimationPreset.HARD_FILL)
            LyricsPreferences.setGlobalLyricsOffsetMs(context, 0)
            LyricsPreferences.setExternalLyricsMode(context, ExternalLyricsMode.OFF)
            LyricsPreferences.setExternalLyricsVisibilityMode(context, ExternalLyricsVisibilityMode.DEFAULT)
            LyricsPreferences.setDesktopLyricsWordByWordEnabled(context, true)
            PlaybackUiPreferences.setPlayerCoverFlowMode(context, PlayerCoverFlowMode.STANDARD)
            LyricsPreferences.setLyricsBilingualDisplayMode(context, LyricsBilingualDisplayMode.ORIGINAL)
            UsageTutorialPreferences.complete(context)
            (context.applicationContext as MicaApp).externalLyricsOverlayControl.sync()
        }
    }

    @Test
    fun mainPlayerAndDesktopConsumeRealPlaybackWithCanonicalWordEnds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MicaApp
        val player = app.playerController
        fixtureId = "external_lyrics_end_flow_${System.nanoTime()}"
        val file = createSilentWav(context.cacheDir, fixtureId, 30).also { wav = it }
        val document = LyricsDocument(lines = listOf(
            LyricLineNode("qa-current", 1000, 22000,
                listOf(LyricTextPart(LyricTextRole.ORIGINAL, lineText)),
                listOf(LyricToken("我却", 1000, 4000), LyricToken("不能忘", 10000, 12000))),
            LyricLineNode("qa-next", 29000, 30000,
                listOf(LyricTextPart(LyricTextRole.ORIGINAL, "下一句验收"))),
        ))
        val song = Song(
            id = fixtureId, title = "Lyrics End Flow QA", artist = "Generated QA", album = "Generated fixture",
            durationSec = 30, metadata = TrackMetadata("WAV", 8000, 16, 128, 1, "audio/wav"),
            albumArtUri = null, coverColorArgb = 0, mediaUri = Uri.fromFile(file).toString(),
            fileName = file.name, lyricsDocument = document, source = SongSource.TRANSIENT_EXTERNAL,
        )
        onMain {
            val registered = app.transientPlaybackCatalog.replace(song)
            player.setQueue(listOf(registered))
            player.connectIfNeeded()
        }
        await("QA player connection", 10000) { onMain { player.isConnected } }
        val observer = connectMediaService(context).also { service = it }
        onMain { player.playSong(0) }
        await("real Service accepts and plays fixture", 10000) {
            onMain { observer.currentMediaItem?.mediaId == fixtureId && observer.isPlaying }
        }
        val start = onMain { observer.currentPosition }
        await("Service playback position advances by 300ms", 10000) { onMain { observer.currentPosition >= start + 300 } }
        record("real Service position advanced: $start -> ${onMain { observer.currentPosition }}")
        pauseAt(observer, 4500)
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("展开播放器：${song.title}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("展开播放器：${song.title}").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText(lineText, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val compactClickable = compose.onAllNodes(
            hasClickAction().and(hasAnyDescendant(hasText(lineText))), useUnmergedTree = true,
        )
        val candidates = compactClickable.fetchSemanticsNodes().filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
        assertTrue("Production compact lyrics clickable ancestor missing", candidates.isNotEmpty())
        record("compact click candidates=" + candidates.map { "id=${it.id},bounds=${it.boundsInRoot},config=${it.config}" })
        // A large player ancestor can also contain this text. Invoke the smallest actual click action.
        val compact = candidates.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
        val compactTextBounds = compose.onAllNodesWithText(lineText, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        compose.onNode(SemanticsMatcher("compact lyric id=${compact.id}") { it.id == compact.id }, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val expandedLyrics = hasScrollAction().and(hasAnyDescendant(hasText(lineText)))
        val screenHeight = context.resources.displayMetrics.heightPixels
        try {
            compose.waitUntil(10000) {
                compose.onAllNodes(expandedLyrics, useUnmergedTree = true).fetchSemanticsNodes().any {
                    it.boundsInRoot.height > screenHeight * 0.35f
                }
            }
        } finally {
            record("navigation scroll containers=" + compose.onAllNodes(expandedLyrics, useUnmergedTree = true)
                .fetchSemanticsNodes().map { "id=${it.id},bounds=${it.boundsInRoot},config=${it.config}" })
            save(instrumentation.uiAutomation.takeScreenshot(), "main_navigation_checkpoint.png")
        }
        val expandedLine = hasText(lineText).and(hasAnyAncestor(expandedLyrics))
        val expandedTextBounds = compose.onAllNodes(expandedLine, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        assertTrue("Lyric text did not move out of compact lower panel: before=$compactTextBounds after=$expandedTextBounds",
            expandedTextBounds.top < compactTextBounds.top - screenHeight * 0.15f)
        record("confirmed expanded scrollable lyrics viewport; compact=$compactTextBounds expanded=$expandedTextBounds")
        for (target in listOf(4500L, 7500L, 12500L, 17500L)) {
            pauseAt(observer, target)
            compose.waitForIdle()
            val node = compose.onAllNodes(expandedLine, useUnmergedTree = true)[0]
            node.assertExists()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            // This is a screen crop at the actual text bounds, not an isolated compositing-layer proof.
            // The full-screen image remains the authority for fill, geometry and sibling overlays.
            save(node.captureToImage().asAndroidBitmap(), "main_text_bounds_$target.png")
            save(instrumentation.uiAutomation.takeScreenshot(), "main_screen_$target.png")
            record("main target=$target actual=${onMain { observer.currentPosition }} " +
                "uiPosition=${onMain { player.playbackProgressState.positionMs }} textBounds=$bounds")
        }
        assertTrue("QA overlay permission must be allowed before this test", Settings.canDrawOverlays(context))
        onMain {
            LyricsPreferences.setExternalLyricsMode(context, ExternalLyricsMode.DESKTOP)
            assertTrue("Production overlay start rejected", app.externalLyricsOverlayControl.sync())
        }
        val automation = instrumentation.uiAutomation
        val oldFlags = automation.serviceInfo.flags
        val oldAutoAdvance = compose.mainClock.autoAdvance
        try {
            // ContractTestSupport.await polls Android state but does not pump the Compose test clock.
            // The real overlay has collectAsState, AnimatedVisibility and a continuous frame ticker.
            compose.mainClock.autoAdvance = false
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            for (target in listOf(5000L, 7500L, 12500L, 17500L)) {
                onMain { observer.seekTo(target); observer.play() }
                await("real desktop projection with source word ends", 10000) {
                    val state = app.desktopLyricsOverlayStateStore.state.value.desktop
                    state.visible && state.line?.original?.text == lineText &&
                        state.line?.original?.cues?.map { it.endTimeMs } == listOf(4000, 12000) &&
                        state.positionMs >= target && state.positionMs < target + 3000
                }
                try {
                    await("actual desktop accessibility window displays fixture", 10000) {
                        compose.mainClock.advanceTimeBy(64)
                        compose.waitForIdle()
                        automation.windows.any { window ->
                            window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                                window.root?.packageName?.toString() == context.packageName &&
                                hasVisibleExactText(window.root, lineText)
                        }
                    }
                    // Accessibility nodes may exist while AnimatedVisibility is still at alpha 0.
                    // Finish its 150ms fade before recording pixels; subsequent seeks keep playing.
                    compose.mainClock.advanceTimeBy(176)
                    compose.waitForIdle()
                } finally {
                    record("desktop state/style target=$target: ${app.desktopLyricsOverlayStateStore.state.value}")
                    File(output, "desktop_windows_$target.txt").writeText(automation.windows.joinToString("\n") { window ->
                        val bounds = Rect().also { window.getBoundsInScreen(it) }
                        "id=${window.id},type=${window.type},title=${window.title},bounds=$bounds\n${describeAccessibilityTree(window.root)}"
                    })
                    save(automation.takeScreenshot(), "desktop_screen_$target.png")
                }
                val state = app.desktopLyricsOverlayStateStore.state.value.desktop
                val actual = onMain { observer.currentPosition }
                assertTrue("Desktop screenshot missed intended timing window: target=$target actual=$actual",
                    actual >= target && actual < target + 2000)
                record("desktop target=$target controller=$actual " +
                    "projection=${state.positionMs} lineEnd=${state.line?.endMs} " +
                    "cues=${state.line?.original?.cues} windows=${automation.windows.map { it.type }}")
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            compose.mainClock.autoAdvance = oldAutoAdvance
        }
    }

    private fun pauseAt(observer: MediaController, target: Long) {
        onMain { observer.pause(); observer.seekTo(target) }
        await("paused Service seek to $target", 10000) {
            onMain { !observer.isPlaying && abs(observer.currentPosition - target) <= 150 }
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MicaApp
        await("UI-facing playback position receives $target", 10000) {
            onMain { abs(app.playerController.playbackProgressState.positionMs - target) <= 150 }
        }
    }

    private fun save(bitmap: Bitmap?, name: String) {
        checkNotNull(bitmap) { "Screenshot unavailable: $name" }
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun describeAccessibilityTree(root: AccessibilityNodeInfo?): String = buildString {
        var visited = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 12 || visited++ >= 160) return
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            appendLine("${" ".repeat(depth)}class=${node.className} package=${node.packageName} text=${node.text} " +
                "description=${node.contentDescription} visible=${node.isVisibleToUser} bounds=$bounds")
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(root, 0)
    }

    private fun hasVisibleExactText(root: AccessibilityNodeInfo?, text: String): Boolean {
        var visited = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 12 || visited++ >= 160) return false
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (node.isVisibleToUser && node.text?.toString() == text && !bounds.isEmpty) return true
            return (0 until node.childCount).any { visit(node.getChild(it), depth + 1) }
        }
        return visit(root, 0)
    }

    private fun record(value: String) {
        File(output, "evidence.txt").appendText("$value\n")
        android.util.Log.i("LyricsEndFlow", value)
    }

    private fun cleanupQa() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MicaApp
        try {
            onMain {
                service?.pause()
                if (::fixtureId.isInitialized && app.playerController.playbackQueueState.queue.any { it.id == fixtureId }) {
                    app.playerController.setQueue(emptyList())
                }
                service?.let { it.clearMediaItems(); it.release() }
            }
            if (::fixtureId.isInitialized && app.transientPlaybackCatalog.songById(fixtureId) != null) {
                app.transientPlaybackCatalog.clear()
            }
        } finally {
            onMain { restore.asReversed().forEach { it() } }
            wav?.delete()
        }
    }
}
