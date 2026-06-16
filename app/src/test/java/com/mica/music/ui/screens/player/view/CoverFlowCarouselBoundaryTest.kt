package com.mica.music.ui.screens.player.view

import androidx.test.core.app.ApplicationProvider
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CoverFlowCarouselBoundaryTest {
    @Test
    fun unchangedBoundaryRequestSettlesDraggedStripBackToCenter() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        activity.setContentView(view)
        view.updateQueue(SongFixtures.queue(3))
        view.resetToIndex(2)
        view.setPrivateFloat("stripFraction", 0.5f)
        var nextCalls = 0
        view.onNext = { nextCalls++ }

        view.invokePrivate("handleDragEnd")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

        assertEquals(1, nextCalls)
        assertEquals(0f, view.privateFloat("stripFraction"), 0.0001f)
    }

    @Test
    fun repeatedDraws_doNotReportUnchangedCenterAspectRatio() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        activity.setContentView(view)
        view.layout(0, 0, 400, 600)
        view.setCoverSizePx(300f, 300f)
        view.updateQueue(SongFixtures.queue(3))
        view.resetToIndex(1)
        var aspectCallbacks = 0
        view.onCenterAspectRatio = { aspectCallbacks++ }

        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        view.draw(canvas)

        assertEquals(1, aspectCallbacks)
    }

    @Test
    fun committedDrag_defersQueuePlayUntilVisualAnimationEnds() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        activity.setContentView(view)
        view.updateQueue(SongFixtures.queue(3))
        view.resetToIndex(1)
        view.setPrivateFloat("stripFraction", 0.5f)
        var playedIndex: Int? = null
        view.onPlayQueueIndex = { playedIndex = it }

        view.invokePrivate("handleDragEnd")

        assertEquals(null, playedIndex)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(2, playedIndex)
    }

    @Test
    fun nearThresholdCoverFlowDragCommitsInsteadOfSettlingBack() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        activity.setContentView(view)
        view.updateQueue(SongFixtures.queue(3))
        view.resetToIndex(1)
        view.setPrivateFloat("stripFraction", 0.245f)
        var playedIndex: Int? = null
        view.onPlayQueueIndex = { playedIndex = it }

        view.invokePrivate("handleDragEnd")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

        assertEquals(2, playedIndex)
        assertEquals(2, view.privateInt("logicalCenter"))
    }

    @Test
    fun committedDrag_ignoresOldHostIndexUntilCommittedIndexArrives() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        activity.setContentView(view)
        val queue = SongFixtures.queue(4)
        view.updateQueue(queue)
        view.resetToIndex(1)
        view.setPrivateFloat("stripFraction", 0.5f)
        var playedIndex: Int? = null
        view.onPlayQueueIndex = { playedIndex = it }

        view.invokePrivate("handleDragEnd")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(2, playedIndex)

        view.applyHostUpdate(queue, index = 1, stageActive = true)
        assertEquals(2, view.privateInt("logicalCenter"))

        view.applyHostUpdate(queue, index = 2, stageActive = true)
        assertEquals(2, view.privateInt("logicalCenter"))
    }

    @Test
    fun newerDragKeepsWinningWhenPreviousHostConfirmationArrivesLate() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        activity.setContentView(view)
        val queue = SongFixtures.queue(5)
        view.updateQueue(queue)
        view.resetToIndex(1)
        val played = mutableListOf<Int>()
        view.onPlayQueueIndex = { played += it }

        view.setPrivateFloat("stripFraction", 0.5f)
        view.invokePrivate("handleDragEnd")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(listOf(2), played)

        view.setPrivateFloat("stripFraction", 0.5f)
        view.invokePrivate("handleDragEnd")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(listOf(2, 3), played)

        view.applyHostUpdate(queue, index = 2, stageActive = true)
        assertEquals(3, view.privateInt("logicalCenter"))

        view.applyHostUpdate(queue, index = 3, stageActive = true)
        assertEquals(3, view.privateInt("logicalCenter"))
    }

    @Test
    fun rapidSecondDragSupersedesRunningVisualCommit() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        activity.setContentView(view)
        view.updateQueue(SongFixtures.queue(5))
        view.resetToIndex(1)
        val played = mutableListOf<Int>()
        view.onPlayQueueIndex = { played += it }

        view.setPrivateFloat("stripFraction", 0.5f)
        view.invokePrivate("handleDragEnd")

        view.setPrivateFloat("stripFraction", 1.45f)
        view.invokePrivate("handleDragEnd")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

        assertEquals(listOf(3), played)
        assertEquals(3, view.privateInt("logicalCenter"))
    }

    @Test
    fun cancelledDragDoesNotFlushStaleHostIndexFromPreviousSong() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        activity.setContentView(view)
        val queue = SongFixtures.queue(4)
        view.updateQueue(queue)
        view.resetToIndex(1)
        view.onPlayQueueIndex = { }

        view.setPrivateFloat("stripFraction", 0.5f)
        view.invokePrivate("handleDragEnd")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(2, view.privateInt("logicalCenter"))
        view.applyHostUpdate(queue, index = 2, stageActive = true)

        view.setPrivateFloat("stripFraction", 0.18f)
        view.invokePrivate("handleDragEnd")
        view.applyHostUpdate(queue, index = 1, stageActive = true)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

        assertEquals(2, view.privateInt("logicalCenter"))
    }

    @Test
    fun confirmedPlayStillIgnoresStaleHostIndexInsideGuardWindow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        activity.setContentView(view)
        val queue = SongFixtures.queue(6)
        view.updateQueue(queue)
        view.resetToIndex(2)
        view.onPlayQueueIndex = { }

        view.setPrivateFloat("stripFraction", 0.5f)
        view.invokePrivate("handleDragEnd")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(3, view.privateInt("logicalCenter"))

        view.applyHostUpdate(queue, index = 3, stageActive = true)
        view.applyHostUpdate(queue, index = 0, stageActive = true)

        assertEquals(3, view.privateInt("logicalCenter"))
    }

    @Test
    fun multiStepDragCommit_animatesInsteadOfSnapping() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        activity.setContentView(view)
        view.updateQueue(SongFixtures.queue(5))
        view.resetToIndex(1)
        view.setMotionEnabled(true)
        var playedIndex: Int? = null
        view.onPlayQueueIndex = { playedIndex = it }

        view.setPrivateFloat("stripFraction", 1.45f)
        view.invokePrivate("handleDragEnd")

        assertEquals(null, playedIndex)
        assertNotNull(view.privateAnimator("trackAnimator"))
        assertTrue(view.privateInt("logicalCenter") in 1..3)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(3, playedIndex)
        assertEquals(3, view.privateInt("logicalCenter"))
    }

    private fun CoverFlowCarouselView.privateAnimator(name: String): Any? =
        javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(this@privateAnimator)
        }

    private fun CoverFlowCarouselView.setPrivateFloat(name: String, value: Float) {
        javaClass.getDeclaredField(name).apply {
            isAccessible = true
            setFloat(this@setPrivateFloat, value)
        }
    }

    private fun CoverFlowCarouselView.privateFloat(name: String): Float =
        javaClass.getDeclaredField(name).run {
            isAccessible = true
            getFloat(this@privateFloat)
        }

    private fun CoverFlowCarouselView.privateInt(name: String): Int =
        javaClass.getDeclaredField(name).run {
            isAccessible = true
            getInt(this@privateInt)
        }

    private fun CoverFlowCarouselView.invokePrivate(name: String) {
        javaClass.getDeclaredMethod(name).apply {
            isAccessible = true
            invoke(this@invokePrivate)
        }
    }
}
