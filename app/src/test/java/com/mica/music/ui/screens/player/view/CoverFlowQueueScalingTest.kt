package com.mica.music.ui.screens.player.view

import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.ui.screens.player.CoverFlowMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断：播放队列长度是否影响封面流手势/切歌动画路径。
 * 见 CoverFlowCarouselView.sameVisualQueue 与固定轨窗 preloadWindow。
 */
class CoverFlowQueueScalingTest {

    @Test
    fun coverFlow_drawLaneWindow_isFixedRegardlessOfQueueSize() {
        val radius = CoverFlowMath.LaneWindowRadius
        assertEquals(3, radius)
        assertEquals(7, radius * 2 + 1)
    }

    @Test
    fun visualQueueEquality_scalesWithQueueSize_whenListInstanceChanges() {
        val small = fakeQueue(100)
        val large = fakeQueue(5_000)
        val smallNs = measureVisualQueueCompare(small, small.toList())
        val largeNs = measureVisualQueueCompare(large, large.toList())
        // 同内容新 List 实例：O(n) 逐首比对。大队列应明显更慢。
        assertTrue(
            "expected large-queue compare > 5x small-queue (small=${smallNs}ns large=${largeNs}ns)",
            largeNs > smallNs * 5,
        )
    }

    @Test
    fun visualQueueEquality_isConstantTime_whenSameListReference() {
        val queue = fakeQueue(5_000)
        val sameRefNs = measureVisualQueueCompare(queue, queue)
        assertTrue(
            "same reference should short-circuit in <<1ms (was ${sameRefNs}ns)",
            sameRefNs < 1_000_000,
        )
    }

    private fun measureVisualQueueCompare(current: List<Song>, incoming: List<Song>): Long {
        repeat(5) { visualQueueEquals(current, incoming) }
        val iterations = 20
        val start = System.nanoTime()
        repeat(iterations) {
            visualQueueEquals(current, incoming)
        }
        return (System.nanoTime() - start) / iterations
    }

  /** 镜像 CoverFlowCarouselView.sameVisualQueue，供 JVM 诊断。 */
    private fun visualQueueEquals(current: List<Song>, incoming: List<Song>): Boolean {
        if (current === incoming) return true
        if (current.size != incoming.size) return false
        return current.indices.all { index ->
            val old = current[index]
            val new = incoming[index]
            old.id == new.id &&
                old.albumArtUri == new.albumArtUri &&
                old.coverColorArgb == new.coverColorArgb
        }
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
