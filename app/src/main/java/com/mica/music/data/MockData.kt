package com.mica.music.data

/**
 * 静态展示数据 —— 现在仅用于 UI 骨架（tab 列表）。
 *
 * 曾经放过示例歌单和模拟统计，现在数据来源完全是 MediaStore，所以那些已经清掉。
 * 如果未来要做"无音乐时的演示模式"，可以在这里恢复一份内置 demo 列表。
 */
object MockData {
    val tabs = listOf("歌曲", "歌手", "专辑", "文件夹", "最近添加")
}
