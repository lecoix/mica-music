package com.mica.music.media

/** 系统媒体控件（通知 / 锁屏 / 耳机）命令回调，由 [ServicePlaybackEngineCoordinator] 实现。 */
interface AlacSessionCommandHandler {
    fun onPlay()
    fun onPause()
    fun onSeekTo(positionMs: Long)
    fun onSelectMediaItem(index: Int, positionMs: Long)
    fun onSkipToNext()
    fun onSkipToPrevious()
}
