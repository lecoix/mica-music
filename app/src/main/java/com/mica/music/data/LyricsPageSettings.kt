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

const val MIN_LYRICS_PAGE_FONT_SIZE_SP = 10
const val MAX_LYRICS_PAGE_FONT_SIZE_SP = 48
const val DEFAULT_LYRICS_PAGE_FONT_SIZE_SP = 19
