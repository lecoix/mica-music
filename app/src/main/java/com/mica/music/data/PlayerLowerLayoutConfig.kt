package com.mica.music.data

enum class PlayerLowerComponent(
    val storageValue: String,
    val settingsLabel: String,
) {
    COVER("cover", "专辑封面"),
    INFO("info", "信息行"),
    TITLE("title", "歌曲标题"),
    LYRICS("lyrics", "紧凑歌词"),
    PROGRESS("progress", "进度条"),
    CONTROLS("controls", "播放控制"),
    ;

    companion object {
        fun fromStorage(value: String): PlayerLowerComponent? =
            entries.firstOrNull { it.storageValue == value }
    }
}

data class PlayerLowerLayoutConfig(
    val order: List<PlayerLowerComponent> = PlayerLowerComponent.entries,
    val hidden: Set<PlayerLowerComponent> = emptySet(),
    val scalePercents: Map<PlayerLowerComponent, Int> = emptyMap(),
    val spacingDp: Int = DEFAULT_SPACING_DP,
    val topPaddingDp: Int = DEFAULT_BOUNDARY_PADDING_DP,
    val bottomPaddingDp: Int = DEFAULT_BOUNDARY_PADDING_DP,
    val lyricsLineCount: Int = DEFAULT_LYRICS_LINE_COUNT,
) {
    fun normalized(): PlayerLowerLayoutConfig {
        val normalizedOrder = order.distinct() + PlayerLowerComponent.entries.filterNot(order::contains)
        return copy(
            order = normalizedOrder,
            hidden = hidden.intersect(PlayerLowerComponent.entries.toSet()),
            scalePercents = scalePercents
                .filterKeys(PlayerLowerComponent.entries::contains)
                .mapValues { (_, percent) -> percent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT) },
            spacingDp = spacingDp.coerceIn(MIN_SPACING_DP, MAX_SPACING_DP),
            topPaddingDp = topPaddingDp.coerceIn(MIN_BOUNDARY_PADDING_DP, MAX_BOUNDARY_PADDING_DP),
            bottomPaddingDp = bottomPaddingDp.coerceIn(MIN_BOUNDARY_PADDING_DP, MAX_BOUNDARY_PADDING_DP),
            lyricsLineCount = normalizeLyricsLineCount(lyricsLineCount),
        )
    }

    fun isVisible(component: PlayerLowerComponent): Boolean = component !in hidden

    fun scalePercentOf(component: PlayerLowerComponent): Int =
        scalePercents[component] ?: DEFAULT_SCALE_PERCENT

    fun withVisibility(component: PlayerLowerComponent, visible: Boolean): PlayerLowerLayoutConfig =
        copy(hidden = if (visible) hidden - component else hidden + component)

    fun withScalePercent(
        component: PlayerLowerComponent,
        percent: Int,
    ): PlayerLowerLayoutConfig = copy(
        scalePercents = scalePercents +
            (component to percent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT)),
    )

    fun move(component: PlayerLowerComponent, delta: Int): PlayerLowerLayoutConfig {
        val current = order.indexOf(component)
        if (current < 0) return normalized()
        val target = (current + delta).coerceIn(order.indices)
        if (target == current) return this
        val updated = order.toMutableList()
        updated.removeAt(current)
        updated.add(target, component)
        return copy(order = updated)
    }

    companion object {
        const val MIN_SPACING_DP = 0
        const val MAX_SPACING_DP = 24
        const val DEFAULT_SPACING_DP = 8
        const val MIN_SCALE_PERCENT = 50
        const val MAX_SCALE_PERCENT = 200
        const val DEFAULT_SCALE_PERCENT = 100
        const val MIN_BOUNDARY_PADDING_DP = 0
        const val MAX_BOUNDARY_PADDING_DP = 120
        const val DEFAULT_BOUNDARY_PADDING_DP = 16
        const val SINGLE_LYRICS_LINE_COUNT = 1
        const val THREE_LYRICS_LINE_COUNT = 3
        const val DEFAULT_LYRICS_LINE_COUNT = THREE_LYRICS_LINE_COUNT
        fun normalizeLyricsLineCount(value: Int): Int =
            if (value == SINGLE_LYRICS_LINE_COUNT) SINGLE_LYRICS_LINE_COUNT else THREE_LYRICS_LINE_COUNT
        val Default = PlayerLowerLayoutConfig()
    }
}
