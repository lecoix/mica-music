package com.mica.music.ui.screens.player.view

import androidx.test.core.app.ApplicationProvider
import android.app.Activity
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
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

    private fun CoverFlowCarouselView.invokePrivate(name: String) {
        javaClass.getDeclaredMethod(name).apply {
            isAccessible = true
            invoke(this@invokePrivate)
        }
    }
}
