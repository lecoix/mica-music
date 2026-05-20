package com.mica.music.data

/** 播放队列模式：顺序 → 列表循环 → 单曲循环 → 随机。 */
enum class PlaybackQueueMode {
    OFF,
    REPEAT_ALL,
    REPEAT_ONE,
    SHUFFLE,
    ;

    fun next(): PlaybackQueueMode = entries[(ordinal + 1) % entries.size]
}
