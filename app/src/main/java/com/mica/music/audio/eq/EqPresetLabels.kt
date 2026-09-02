package com.mica.music.audio.eq

/** Android 系统均衡器预设的界面显示名（标准 10 段索引 + 常见英文别名）。 */
object EqPresetLabels {

    private val byIndex = mapOf(
        0 to "标准",
        1 to "古典",
        2 to "舞曲",
        3 to "平直",
        4 to "民谣",
        5 to "重金属",
        6 to "嘻哈",
        7 to "爵士",
        8 to "流行",
        9 to "摇滚",
    )

    private val byName = mapOf(
        "normal" to "标准",
        "classical" to "古典",
        "dance" to "舞曲",
        "flat" to "平直",
        "folk" to "民谣",
        "heavy metal" to "重金属",
        "hip hop" to "嘻哈",
        "hiphop" to "嘻哈",
        "jazz" to "爵士",
        "pop" to "流行",
        "rock" to "摇滚",
    )

    fun displayName(index: Int, systemName: String): String =
        byIndex[index]
            ?: byName[systemName.trim().lowercase()]
            ?: systemName
}
