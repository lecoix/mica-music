package com.mica.music.media

/**
 * ALAC 流式播放的单一时间源：App UI 与 MediaSession 均从此快照读取。
 *
 * [generation] 在切歌、停止、seek 时递增，用于丢弃引擎异步回调中的过期事件。
 */
class AlacPlaybackClock {

    var generation: Int = 0
        private set

    var positionMs: Long = 0
        private set

    var durationMs: Long = 0
        private set

    var playWhenReady: Boolean = false
        private set

    var isPlaying: Boolean = false
        private set

    var buffering: Boolean = false
        private set

    /** seek 后暂钉目标，过滤 PCM 重启前的偏低进度回报。 */
    private var seekAnchorMs: Long? = null

    fun isStale(observedGeneration: Int): Boolean = observedGeneration != generation

    fun bumpGeneration(): Int {
        generation++
        return generation
    }

    fun resetForNewTrack(durationMs: Long) {
        bumpGeneration()
        positionMs = 0
        seekAnchorMs = null
        this.durationMs = durationMs.coerceAtLeast(0)
        playWhenReady = true
        isPlaying = false
        buffering = true
    }

    /** 冷启动恢复进度：不递增 generation，仅钉住 UI/Session 直至 PCM 回报接近。 */
    fun pinInitialPosition(positionMs: Long) {
        val pinned = positionMs.coerceAtLeast(0)
        this.positionMs = pinned
        seekAnchorMs = pinned
    }

    /** seek 开始：钉住目标位并丢弃此前回调。 */
    fun beginSeek(targetMs: Long, playWhenReady: Boolean): Int {
        bumpGeneration()
        seekAnchorMs = targetMs.coerceAtLeast(0)
        positionMs = seekAnchorMs!!
        isPlaying = false
        buffering = true
        this.playWhenReady = playWhenReady
        return generation
    }

    fun applyPosition(observedGeneration: Int, rawMs: Long, maxMs: Long): Long? {
        if (isStale(observedGeneration)) return null
        val clamped = if (maxMs > 0) rawMs.coerceIn(0, maxMs) else rawMs.coerceAtLeast(0)
        val anchor = seekAnchorMs
        if (anchor != null) {
            // 丢弃与 seek 目标相差过大的陈旧回报（PCM 重启前旧进度）
            if (kotlin.math.abs(clamped - anchor) > 1_500) return null
            positionMs = anchor
            if (kotlin.math.abs(clamped - anchor) <= 500) {
                seekAnchorMs = null
                positionMs = clamped
            }
            return positionMs
        }
        positionMs = clamped
        return clamped
    }

    fun applyPrepared(observedGeneration: Int, durationSec: Int) {
        if (isStale(observedGeneration)) return
        if (durationSec > 0) {
            durationMs = maxOf(durationMs, durationSec * 1000L)
        }
        buffering = false
    }

    fun applyPlaying(observedGeneration: Int, playing: Boolean) {
        if (isStale(observedGeneration)) return
        isPlaying = playing
    }

    fun applyBuffering(observedGeneration: Int, buffering: Boolean) {
        if (isStale(observedGeneration)) return
        this.buffering = buffering
    }

    fun applyPlayWhenReady(playWhenReady: Boolean) {
        this.playWhenReady = playWhenReady
    }

    fun ensureDurationMs(ms: Long) {
        durationMs = maxOf(durationMs, ms.coerceAtLeast(0))
    }

    fun toSessionState(): AlacSessionState = AlacSessionState(
        playWhenReady = playWhenReady,
        buffering = buffering,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
    )
}
