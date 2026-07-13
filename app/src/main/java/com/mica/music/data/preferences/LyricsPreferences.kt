package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.data.DEFAULT_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.DEFAULT_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsPageTheme
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.data.MAX_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MAX_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.MIN_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MIN_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.PlaybackContentColorMode

/** 歌词页、通知歌词与播放页歌词文字相关偏好。 */
object LyricsPreferences {
    private const val KEY_LYRIC_SPLIT_ENABLED = "lyric_split_enabled"
    private const val KEY_LYRICS_BILINGUAL_DISPLAY_MODE = "lyrics_bilingual_display_mode"
    private const val KEY_LYRIC_LINE_FILL_ENABLED = "lyric_line_fill_enabled"
    private const val KEY_PLAYER_PAGE_TEXT_COLOR = "player_page_text_color"
    private const val KEY_LYRICS_PAGE_TEXT_COLOR = "lyrics_page_text_color"
    private const val KEY_LYRICS_PAGE_ALIGNMENT = "lyrics_page_alignment"
    private const val KEY_LYRICS_PAGE_THEME = "lyrics_page_theme"
    private const val KEY_LYRICS_WORD_ANIMATION_PRESET = "lyrics_word_animation_preset"
    private const val KEY_LYRICS_PAGE_FONT_SIZE = "lyrics_page_font_size"
    private const val KEY_LYRICS_PAGE_TRANSLATION_FONT_SIZE = "lyrics_page_translation_font_size"
    private const val KEY_LYRICS_PAGE_LINE_SPACING = "lyrics_page_line_spacing"
    private const val KEY_LYRICS_PAGE_IMMERSIVE = "lyrics_page_immersive"
    private const val KEY_NOTIFICATION_LYRICS_ENABLED = "notification_lyrics_enabled"

    fun lyricSplitEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_LYRIC_SPLIT_ENABLED, true)

    fun setLyricSplitEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_LYRIC_SPLIT_ENABLED, enabled)
            .apply()
    }

    fun lyricsBilingualDisplayMode(context: Context): LyricsBilingualDisplayMode =
        LyricsBilingualDisplayMode.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_LYRICS_BILINGUAL_DISPLAY_MODE, null),
        )

    fun setLyricsBilingualDisplayMode(context: Context, mode: LyricsBilingualDisplayMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_LYRICS_BILINGUAL_DISPLAY_MODE, mode.storageValue)
            .apply()
    }

    fun lyricLineFillEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_LYRIC_LINE_FILL_ENABLED, false)

    fun setLyricLineFillEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_LYRIC_LINE_FILL_ENABLED, enabled)
            .apply()
    }

    fun playerPageTextColorMode(context: Context): PlaybackContentColorMode =
        PlaybackContentColorMode.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_PLAYER_PAGE_TEXT_COLOR, null),
        )

    fun setPlayerPageTextColorMode(context: Context, mode: PlaybackContentColorMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_PLAYER_PAGE_TEXT_COLOR, mode.storageValue)
            .apply()
    }

    fun lyricsPageTextColorMode(context: Context): PlaybackContentColorMode =
        PlaybackContentColorMode.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_LYRICS_PAGE_TEXT_COLOR, null),
        )

    fun setLyricsPageTextColorMode(context: Context, mode: PlaybackContentColorMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_LYRICS_PAGE_TEXT_COLOR, mode.storageValue)
            .apply()
    }

    fun lyricsPageAlignment(context: Context): LyricsPageAlignment =
        LyricsPageAlignment.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_LYRICS_PAGE_ALIGNMENT, null),
        )

    fun setLyricsPageAlignment(context: Context, alignment: LyricsPageAlignment) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_LYRICS_PAGE_ALIGNMENT, alignment.storageValue)
            .apply()
    }

    fun lyricsPageTheme(context: Context): LyricsPageTheme =
        LyricsPageTheme.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_LYRICS_PAGE_THEME, null),
        )

    fun setLyricsPageTheme(context: Context, theme: LyricsPageTheme) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_LYRICS_PAGE_THEME, theme.storageValue)
            .apply()
    }

    fun lyricsWordAnimationPreset(context: Context): LyricsWordAnimationPreset =
        LyricsWordAnimationPreset.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_LYRICS_WORD_ANIMATION_PRESET, null),
        )

    fun setLyricsWordAnimationPreset(context: Context, preset: LyricsWordAnimationPreset) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_LYRICS_WORD_ANIMATION_PRESET, preset.storageValue)
            .apply()
    }

    fun lyricsPageFontSizeSp(context: Context): Int =
        readLyricsPageFontSizeSp(context, KEY_LYRICS_PAGE_FONT_SIZE, DEFAULT_LYRICS_PAGE_FONT_SIZE_SP)

    fun setLyricsPageFontSizeSp(context: Context, fontSizeSp: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(
                KEY_LYRICS_PAGE_FONT_SIZE,
                fontSizeSp.coerceIn(MIN_LYRICS_PAGE_FONT_SIZE_SP, MAX_LYRICS_PAGE_FONT_SIZE_SP),
            )
            .apply()
    }

    fun lyricsPageTranslationFontSizeSp(context: Context): Int =
        readLyricsPageFontSizeSp(
            context,
            KEY_LYRICS_PAGE_TRANSLATION_FONT_SIZE,
            lyricsPageFontSizeSp(context),
        )

    fun setLyricsPageTranslationFontSizeSp(context: Context, fontSizeSp: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(
                KEY_LYRICS_PAGE_TRANSLATION_FONT_SIZE,
                fontSizeSp.coerceIn(MIN_LYRICS_PAGE_FONT_SIZE_SP, MAX_LYRICS_PAGE_FONT_SIZE_SP),
            )
            .apply()
    }

    fun lyricsPageLineSpacingDp(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(
            KEY_LYRICS_PAGE_LINE_SPACING,
            DEFAULT_LYRICS_PAGE_LINE_SPACING_DP,
        ).coerceIn(MIN_LYRICS_PAGE_LINE_SPACING_DP, MAX_LYRICS_PAGE_LINE_SPACING_DP)

    fun setLyricsPageLineSpacingDp(context: Context, spacingDp: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(
                KEY_LYRICS_PAGE_LINE_SPACING,
                spacingDp.coerceIn(MIN_LYRICS_PAGE_LINE_SPACING_DP, MAX_LYRICS_PAGE_LINE_SPACING_DP),
            )
            .apply()
    }

    fun lyricsPageImmersive(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_LYRICS_PAGE_IMMERSIVE, false)

    fun setLyricsPageImmersive(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_LYRICS_PAGE_IMMERSIVE, enabled)
            .apply()
    }

    fun notificationLyricsEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_NOTIFICATION_LYRICS_ENABLED, true)

    fun setNotificationLyricsEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_NOTIFICATION_LYRICS_ENABLED, enabled)
            .apply()
    }

    private fun readLyricsPageFontSizeSp(context: Context, key: String, defaultValue: Int): Int =
        when (val stored = MicaSettingsStore.prefs(context).all[key]) {
            is Int -> stored
            is String -> when (stored) {
                "small" -> 17
                "large" -> 22
                "extra_large" -> 25
                else -> defaultValue
            }
            else -> defaultValue
        }.coerceIn(MIN_LYRICS_PAGE_FONT_SIZE_SP, MAX_LYRICS_PAGE_FONT_SIZE_SP)
}
