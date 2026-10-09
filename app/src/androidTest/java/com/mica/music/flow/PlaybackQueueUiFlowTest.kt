package com.mica.music.flow

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.media3.session.MediaController
import com.mica.music.MicaApp
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.data.PlaybackQueueMode
import com.mica.music.testutil.ContractTestSupport.await
import com.mica.music.testutil.ContractTestSupport.onMain
import com.mica.music.testutil.ContractTestSupport.connectMediaService
import com.mica.music.testutil.ContractTestSupport.createSilentWav
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.MicaMainActivity
import com.mica.music.data.preferences.UsageTutorialPreferences
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Real MicaMainActivity flow smoke.
 *
 * This deliberately runs only against the side-by-side QA application id so it cannot
 * consume or rewrite the user's ordinary Mica preferences while navigating the real UI.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackQueueUiFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

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

        UsageTutorialPreferences.complete(context)
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

    @Test
    fun actualQueueDragAndRemoveChangeNaturalSuccessor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as MicaApp).playerController
        val files = (0..5).map { createSilentWav(context.cacheDir, "queue-ui-$it", 30) }
        val songs = files.mapIndexed { i, file -> Song(
            id = "queue-ui-$i", title = "Queue UI $i", artist = "Mica", album = "Queue UI", durationSec = 30,
            metadata = TrackMetadata("WAV", 8000, 16, 128, 1, "audio/wav"),
            albumArtUri = null, coverColorArgb = 0, mediaUri = Uri.fromFile(file).toString(), fileName = file.name,
        ) }
        var service: MediaController? = null
        try {
            onMain { app.setQueue(songs); app.connectIfNeeded() }
            await("UI player connects", 10000) { onMain { app.isConnected } }
            val observer = connectMediaService(context)
            service = observer
            onMain { app.playSong(0) }
            await("UI fixture plays", 10000) { onMain { observer.currentMediaItem?.mediaId == songs[0].id && observer.isPlaying } }
            onMain { observer.pause() }
            while (onMain { app.playbackSurfaceState.playbackQueueMode } != PlaybackQueueMode.SHUFFLE) {
                val old = onMain { app.playbackSurfaceState.playbackQueueMode }
                onMain { app.cyclePlaybackQueueMode() }
                await("UI mode changes", 10000) { onMain { app.playbackSurfaceState.playbackQueueMode != old } }
            }
            compose.onNodeWithContentDescription("展开播放器：${songs[0].title}").performClick()
            compose.onNodeWithContentDescription("播放列表").performClick()
            compose.waitForIdle()
            val before = onMain { app.playbackQueueState.queue.map { it.id } }
            val handles = compose.onAllNodesWithContentDescription("拖动排序", useUnmergedTree = true)
            val from = handles[2].fetchSemanticsNode().boundsInRoot
            val to = handles[1].fetchSemanticsNode().boundsInRoot
            android.util.Log.i("QueueUi", "before=$before from=$from to=$to orientation=${context.resources.configuration.orientation}")
            handles[2].performTouchInput {
                swipe(center, center + Offset(0f, to.center.y - from.center.y - to.height * 0.2f), 900)
            }
            compose.waitForIdle()
            compose.waitUntil(10000) { onMain { app.playbackQueueState.queue.map { it.id } != before } }
            val dragged = onMain { app.playbackQueueState.queue }
            assertEquals(before[2], dragged[1].id)
            // Real row remove button -> owner mutation -> Binder -> accepted native order.
            compose.onAllNodesWithContentDescription("从队列移除", useUnmergedTree = true)[1].performClick()
            await("UI remove commits", 10000) { onMain { app.playbackQueueState.queue.size == songs.size - 1 } }
            val next = onMain { app.playbackQueueState.let { it.queue[it.currentIndex+1].id } }
            await("UI service order accepted", 10000) {
                onMain { observer.nextMediaItemIndex.takeIf { it >= 0 }?.let { observer.getMediaItemAt(it).mediaId } == next }
            }
            onMain { observer.seekTo(observer.duration - 200L); observer.play() }
            await("UI actual natural successor", 10000) { onMain { observer.currentMediaItem?.mediaId == next } }
            await("UI current highlight receives callback", 10000) { onMain { app.playbackSurfaceState.currentSong?.id == next } }
            compose.onAllNodesWithText(onMain { app.playbackSurfaceState.currentSong!!.title })[0].assertExists()
        } finally {
            onMain { app.setQueue(emptyList()) }
            service?.let { onMain { it.pause(); it.clearMediaItems(); it.release() } }
            files.forEach { it.delete() }
        }
    }
}
