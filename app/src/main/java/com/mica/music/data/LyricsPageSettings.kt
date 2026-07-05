package com.mica.music.data

enum class LyricsPageAlignment(
    val storageValue: String,
    val settingsLabel: String,
) {
    CENTER("center", "居中"),
    START("start", "靠左"),
    END("end", "靠右"),
    ;

    companion object {
        fun fromStorage(value: String?): LyricsPageAlignment =
            entries.firstOrNull { it.storageValue == value } ?: CENTER
    }
}

/** 播放页控件/歌词前景色覆盖（自动 / 固定浅色 / 固定深色）。 */
enum class PlaybackContentColorMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    AUTO("auto", "自动"),
    LIGHT("light", "浅色（白）"),
    DARK("dark", "深色（黑）"),
    ;

    companion object {
        fun fromStorage(value: String?): PlaybackContentColorMode =
            entries.firstOrNull { it.storageValue == value } ?: AUTO
    }
}

typealias LyricsPageTextColorMode = PlaybackContentColorMode

enum class LyricsBilingualDisplayMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    ALL("all", "全部歌词"),
    ORIGINAL("original", "仅原歌词"),
    TRANSLATION("translation", "仅翻译歌词"),
    ;

    companion object {
        fun fromStorage(value: String?): LyricsBilingualDisplayMode =
            entries.firstOrNull { it.storageValue == value } ?: ALL
    }
}

const val MIN_LYRICS_PAGE_FONT_SIZE_SP = 10
const val MAX_LYRICS_PAGE_FONT_SIZE_SP = 48
const val DEFAULT_LYRICS_PAGE_FONT_SIZE_SP = 19
