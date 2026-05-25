package com.mica.music.data

/** 播放页切歌擦除动画方向（与 [PlayerController.consumeTrackSkipDirection] 配合）。 */
enum class TrackSkipDirection {
    /** 下一曲：分界从右向左扫，右侧为新页面 */
    TO_NEXT,
    /** 上一曲：分界从左向右扫，左侧为新页面 */
    TO_PREVIOUS,
}
