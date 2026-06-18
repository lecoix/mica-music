package com.mica.music.ui.screens.player.view

import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoverFlowCarouselViewHostIndexTest {

    @Test
    fun externalHostIndexDuringGestureGuardResetsVisualCenterImmediately() {
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        val queue = fakeQueue(20)
        view.applyHostUpdate(queue, index = 10, stageActive = true)
        view.invokePrivate("dispatchPlayQueueIndex", 11)
        view.setPrivate("pendingPlayQueueIndex", 11)

        view.applyHostUpdate(queue, index = 12, stageActive = true)

        assertEquals(12, view.getPrivate<Int>("logicalCenter"))
        assertEquals(0f, view.getPrivate<Float>("stripFraction"), 0.0001f)
        assertNull(view.getPrivate<Int?>("pendingHostIndex"))
        assertNull(view.getPrivate<Int?>("pendingPlayQueueIndex"))
        assertNull(view.getPrivate<Int?>("awaitingCommittedPlayIndex"))
        assertNull(view.getPrivate<Int?>("lastDispatchedPlayIndex"))
    }

    @Test
    fun pendingGestureDispatchIsSkippedWhenHostAlreadyCommittedTarget() {
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        val queue = fakeQueue(20)
        var dispatched: Int? = null
        view.onPlayQueueIndex = { dispatched = it }
        view.applyHostUpdate(queue, index = 10, stageActive = true)
        view.setPrivate("logicalCenter", 11)
        view.setPrivate("pendingPlayQueueIndex", 11)
        view.setPrivate("pendingHostIndex", 11)

        view.invokePrivate("flushPendingPlayQueueIndex")

        assertNull(dispatched)
        assertNull(view.getPrivate<Int?>("pendingHostIndex"))
        assertNull(view.getPrivate<Int?>("pendingPlayQueueIndex"))
        assertNull(view.getPrivate<Int?>("awaitingCommittedPlayIndex"))
        assertNull(view.getPrivate<Int?>("lastDispatchedPlayIndex"))
    }

    @Test
    fun externalHostIndexSupersedesPendingGestureBeforeDispatch() {
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        val queue = fakeQueue(20)
        view.applyHostUpdate(queue, index = 10, stageActive = true)
        view.setPrivate("pendingPlayQueueIndex", 11)
        view.setPrivate("lastSupersededHostIndex", 10)

        view.applyHostUpdate(queue, index = 12, stageActive = true)

        assertEquals(12, view.getPrivate<Int>("logicalCenter"))
        assertEquals(0f, view.getPrivate<Float>("stripFraction"), 0.0001f)
        assertNull(view.getPrivate<Int?>("pendingPlayQueueIndex"))
        assertNull(view.getPrivate<Int?>("pendingHostIndex"))
        assertNull(view.getPrivate<Int?>("lastSupersededHostIndex"))
    }

    @Test
    fun oldHostEchoDoesNotSupersedePendingGestureBeforeDispatch() {
        val view = CoverFlowCarouselView(ApplicationProvider.getApplicationContext())
        val queue = fakeQueue(20)
        view.applyHostUpdate(queue, index = 10, stageActive = true)
        view.setPrivate("pendingPlayQueueIndex", 11)
        view.setPrivate("lastSupersededHostIndex", 10)

        view.applyHostUpdate(queue, index = 10, stageActive = true)

        assertEquals(10, view.getPrivate<Int>("logicalCenter"))
        assertEquals(11, view.getPrivate<Int?>("pendingPlayQueueIndex"))
        assertEquals(10, view.getPrivate<Int?>("lastSupersededHostIndex"))
    }

    private fun CoverFlowCarouselView.invokePrivate(name: String, vararg args: Any?) {
        val types = args.map { arg ->
            when (arg) {
                is Int -> Int::class.javaPrimitiveType
                else -> arg?.javaClass
            }
        }.toTypedArray()
        val method = javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }
        method.invoke(this, *args)
    }

    private fun CoverFlowCarouselView.setPrivate(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(this@setPrivate, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> CoverFlowCarouselView.getPrivate(name: String): T =
        javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(this@getPrivate) as T
        }

    private fun fakeQueue(size: Int): List<Song> =
        List(size) { index ->
            Song(
                id = "song-$index",
                title = "Title $index",
                artist = "Artist",
                album = "Album",
                durationSec = 180,
                metadata = TrackMetadata(
                    containerName = "FLAC",
                    sampleRateHz = 44_100,
                    bitsPerSample = 16,
                    bitrateKbps = 1_000,
                    channelCount = 2,
                    playbackMimeType = "audio/flac",
                ),
                albumArtUri = "content://fake/$index",
                coverColorArgb = 0xFF112233.toInt(),
                mediaUri = "content://media/$index",
            )
        }
}
