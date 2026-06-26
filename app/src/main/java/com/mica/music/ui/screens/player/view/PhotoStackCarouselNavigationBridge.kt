package com.mica.music.ui.screens.player.view

/** 视图优先切歌桥接：按钮/手势先让拍立得自己跑完视觉，再回调宿主切歌。 */
class PhotoStackCarouselNavigationBridge {
    internal var view: PhotoStackTransitionView? = null

    fun skipToIndex(index: Int) {
        view?.skipToIndexVisualFirst(index)
    }
}
