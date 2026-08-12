package com.mica.music.ui.screens.player.view

/**
 * 供播放页按钮切歌调用：在 CoverFlow 动画结束后再 [dispatchPlayQueueIndex]，
 * 与滑动手势 [CoverFlowCarouselView.playQueueIndexAfterVisualCommit] 路径一致。
 */
class CoverFlowCarouselNavigationBridge {
    internal var view: CoverFlowCarouselView? = null

    fun skipToIndex(index: Int) {
        view?.skipToIndexVisualFirst(index)
    }

    fun beginDrag(): Boolean = view?.beginExternalDrag() == true

    fun dragBy(deltaPx: Float) {
        view?.dragExternalBy(deltaPx)
    }

    fun endDrag() {
        view?.endExternalDrag()
    }

    fun cancelDrag() {
        view?.cancelExternalDrag()
    }
}
