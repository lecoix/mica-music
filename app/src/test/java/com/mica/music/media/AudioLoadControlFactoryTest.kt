@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLoadControlFactoryTest {
    private val playerId = PlayerId("audio-load-control-test")
    private val mediaPeriodId = MediaPeriodId("audio-load-control-period")

    @Test
    fun playbackAndRebufferRequireEightHundredMilliseconds() {
        val loadControl = buildAudioLoadControl()

        assertFalse(loadControl.shouldStart(bufferedDurationMs = 799L))
        assertTrue(loadControl.shouldStart(bufferedDurationMs = 800L))
        assertFalse(loadControl.shouldStart(bufferedDurationMs = 799L, rebuffering = true))
        assertTrue(loadControl.shouldStart(bufferedDurationMs = 800L, rebuffering = true))
    }

    @Test
    fun audioLoadingUsesFifteenToThirtySecondTimeWindow() {
        val loadControl = buildAudioLoadControl()
        loadControl.onPrepared(playerId)

        assertTrue(loadControl.shouldContinueLoading(parameters(bufferedDurationMs = 14_999L)))
        assertFalse(loadControl.shouldContinueLoading(parameters(bufferedDurationMs = 30_000L)))

        loadControl.onReleased(playerId)
    }

    @Test
    fun byteTargetCannotStopLoadingBeforeMinimumDuration() {
        val loadControl = buildAudioLoadControl()
        loadControl.onPrepared(playerId)
        val allocator = loadControl.getAllocator(playerId)
        val allocations = buildList<Allocation> {
            while (allocator.totalBytesAllocated < 32 * 1024 * 1024) {
                add(allocator.allocate())
            }
        }
        assertTrue(loadControl.shouldContinueLoading(parameters(bufferedDurationMs = 14_999L)))

        allocations.forEach(allocator::release)
        loadControl.onReleased(playerId)
    }

    @Test
    fun shortRewindsKeepSixtySecondsWithoutKeyframeRounding() {
        val loadControl = buildAudioLoadControl()

        assertEquals(60_000L, loadControl.getBackBufferDurationUs(playerId) / 1_000L)
        assertFalse(loadControl.retainBackBufferFromKeyframe(playerId))
    }

    private fun LoadControl.shouldStart(
        bufferedDurationMs: Long,
        rebuffering: Boolean = false,
    ): Boolean = shouldStartPlayback(parameters(bufferedDurationMs, rebuffering))

    private fun parameters(
        bufferedDurationMs: Long,
        rebuffering: Boolean = false,
    ): LoadControl.Parameters = LoadControl.Parameters(
        playerId,
        timelineFor(mediaPeriodId),
        mediaPeriodId,
        0L,
        bufferedDurationMs * 1_000L,
        1f,
        true,
        rebuffering,
        C.TIME_UNSET,
        C.TIME_UNSET,
    )

    private fun timelineFor(mediaPeriodId: MediaPeriodId): Timeline = object : Timeline() {
        override fun getWindowCount(): Int = 1

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long,
        ): Window {
            check(windowIndex == 0)
            return window.set(
                WINDOW_UID,
                MediaItem.EMPTY,
                null,
                C.TIME_UNSET,
                C.TIME_UNSET,
                C.TIME_UNSET,
                true,
                false,
                null,
                0L,
                C.TIME_UNSET,
                0,
                0,
                0L,
            )
        }

        override fun getPeriodCount(): Int = 1

        override fun getPeriod(
            periodIndex: Int,
            period: Period,
            setIds: Boolean,
        ): Period {
            check(periodIndex == 0)
            val uid = mediaPeriodId.periodUid
            return period.set(uid.takeIf { setIds }, uid, 0, C.TIME_UNSET, 0L)
        }

        override fun getIndexOfPeriod(uid: Any): Int =
            if (uid == mediaPeriodId.periodUid) 0 else C.INDEX_UNSET

        override fun getUidOfPeriod(periodIndex: Int): Any {
            check(periodIndex == 0)
            return mediaPeriodId.periodUid
        }
    }

    private companion object {
        private val WINDOW_UID = Any()
    }
}
