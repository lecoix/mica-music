package com.mica.music.media

import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline

/**
 * 仅供锁屏 / MediaSession 使用的静态播放列表时间轴（每首歌一个 window + period）。
 * ALAC 实际解码由 AudioTrack 完成，此处不触发 ExoPlayer 加载。
 */
internal class SessionMediaTimeline(
    private val items: List<MediaItem>,
    private val currentIndex: Int,
    private val currentDurationUs: Long,
    private val currentPositionUs: Long,
) : Timeline() {

    override fun getWindowCount(): Int = items.size

    override fun getPeriodCount(): Int = items.size

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long,
    ): Window {
        val durationUs = if (windowIndex == currentIndex) currentDurationUs else C.TIME_UNSET
        val positionUs = if (windowIndex == currentIndex) currentPositionUs else 0L
        window.set(
            windowIndex,
            items[windowIndex],
            /* manifest= */ null,
            /* presentationStartTimeMs= */ C.TIME_UNSET,
            /* windowStartTimeMs= */ C.TIME_UNSET,
            /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET,
            /* isSeekable= */ durationUs != C.TIME_UNSET,
            /* isDynamic= */ false,
            /* liveConfiguration= */ null,
            /* defaultPositionUs= */ 0,
            durationUs,
            windowIndex,
            windowIndex,
            positionUs,
        )
        window.isPlaceholder = durationUs == C.TIME_UNSET
        return window
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val durationUs = if (periodIndex == currentIndex) currentDurationUs else C.TIME_UNSET
        period.set(
            if (setIds) periodIndex else null,
            periodIndex,
            periodIndex,
            durationUs,
            /* positionInWindowUs= */ 0,
            AdPlaybackState.NONE,
            /* isPlaceholder= */ durationUs == C.TIME_UNSET,
        )
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int =
        (uid as? Int)?.takeIf { it in items.indices } ?: C.INDEX_UNSET

    override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
}
