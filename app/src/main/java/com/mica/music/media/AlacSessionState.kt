package com.mica.music.media

/** ALAC 流式播放时注入 MediaSession 的状态快照。 */
data class AlacSessionState(
    /** 用户意图：是否处于「应播放」状态（暂停时为 false，缓冲中仍为 true）。 */
    val playWhenReady: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
) {
    val isPlaying: Boolean
        get() = playWhenReady && !buffering
}

/** 系统媒体控件（通知 / 锁屏 / 耳机）在 ALAC 流式播放时的命令回调。 */
interface AlacSessionCommandHandler {
    fun onPlay()
    fun onPause()
    fun onSeekTo(positionMs: Long)
    fun onSkipToNext()
    fun onSkipToPrevious()
}
