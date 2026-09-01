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

/** 自定义标准主题里可以独立设置横向对齐的文字位置。 */
enum class PlayerLowerTextTarget(
    val storageValue: String,
    val settingsLabel: String,
) {
    TITLE("title", "歌名"),
    SUBTITLE("subtitle", "副标题"),
    LYRICS("lyrics", "紧凑歌词"),
    ;

    companion object {
        fun fromStorage(value: String): PlayerLowerTextTarget? =
            entries.firstOrNull { it.storageValue == value }
    }
}

enum class PlayerLowerTextAlign(
    val storageValue: String,
    val settingsLabel: String,
) {
    START("start", "靠左"),
    CENTER("center", "居中"),
    END("end", "靠右"),
    ;

    companion object {
        val Default = CENTER

        fun fromStorage(value: String): PlayerLowerTextAlign? =
            entries.firstOrNull { it.storageValue == value }
    }
}

/** 播放控制区的五个按钮，自定义标准主题下可逐个显隐。 */
enum class PlayerControlButton(
    val storageValue: String,
    val settingsLabel: String,
) {
    QUEUE_MODE("queue_mode", "播放模式"),
    PREVIOUS("previous", "上一首"),
    PLAY_PAUSE("play_pause", "播放/暂停"),
    NEXT("next", "下一首"),
    QUEUE("queue", "播放列表"),
    ;

    companion object {
        fun fromStorage(value: String): PlayerControlButton? =
            entries.firstOrNull { it.storageValue == value }
    }
}

data class PlayerLowerElementOffset(
    val xPermille: Int = 0,
    val yPermille: Int = 0,
) {
    fun normalized(): PlayerLowerElementOffset = copy(
        xPermille = xPermille.coerceIn(MIN_OFFSET_PERMILLE, MAX_OFFSET_PERMILLE),
        yPermille = yPermille.coerceIn(MIN_OFFSET_PERMILLE, MAX_OFFSET_PERMILLE),
    )

    companion object {
        const val MIN_OFFSET_PERMILLE = -1_000
        const val MAX_OFFSET_PERMILLE = 1_000
        val Zero = PlayerLowerElementOffset()
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
    val elementOffsets: Map<PlayerLowerComponent, PlayerLowerElementOffset> = emptyMap(),
    val freeformEnabled: Boolean = false,
    val textAligns: Map<PlayerLowerTextTarget, PlayerLowerTextAlign> = emptyMap(),
    val hiddenControls: Set<PlayerControlButton> = emptySet(),
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
            elementOffsets = elementOffsets
                .filterKeys(PlayerLowerComponent.entries::contains)
                .mapValues { (_, offset) -> offset.normalized() }
                .filterValues { it != PlayerLowerElementOffset.Zero },
            textAligns = textAligns
                .filterKeys(PlayerLowerTextTarget.entries::contains)
                .filterValues { it != PlayerLowerTextAlign.Default },
            hiddenControls = hiddenControls.intersect(PlayerControlButton.entries.toSet()),
        )
    }

    fun isVisible(component: PlayerLowerComponent): Boolean = component !in hidden

    fun scalePercentOf(component: PlayerLowerComponent): Int =
        scalePercents[component] ?: DEFAULT_SCALE_PERCENT

    fun withVisibility(component: PlayerLowerComponent, visible: Boolean): PlayerLowerLayoutConfig =
        copy(hidden = if (visible) hidden - component else hidden + component)

    fun textAlignOf(target: PlayerLowerTextTarget): PlayerLowerTextAlign =
        textAligns[target] ?: PlayerLowerTextAlign.Default

    fun withTextAlign(
        target: PlayerLowerTextTarget,
        align: PlayerLowerTextAlign,
    ): PlayerLowerLayoutConfig = copy(
        textAligns = if (align == PlayerLowerTextAlign.Default) {
            textAligns - target
        } else {
            textAligns + (target to align)
        },
    )

    fun isControlVisible(button: PlayerControlButton): Boolean = button !in hiddenControls

    fun withControlVisibility(
        button: PlayerControlButton,
        visible: Boolean,
    ): PlayerLowerLayoutConfig = copy(
        hiddenControls = if (visible) hiddenControls - button else hiddenControls + button,
    )

    fun withScalePercent(
        component: PlayerLowerComponent,
        percent: Int,
    ): PlayerLowerLayoutConfig = copy(
        scalePercents = scalePercents +
            (component to percent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT)),
    )

    fun withElementOffset(
        component: PlayerLowerComponent,
        offset: PlayerLowerElementOffset,
    ): PlayerLowerLayoutConfig {
        val normalizedOffset = offset.normalized()
        return copy(
            elementOffsets = if (normalizedOffset == PlayerLowerElementOffset.Zero) {
                elementOffsets - component
            } else {
                elementOffsets + (component to normalizedOffset)
            },
        )
    }

    fun offsetOf(component: PlayerLowerComponent): PlayerLowerElementOffset =
        elementOffsets[component] ?: PlayerLowerElementOffset.Zero

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
