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
import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
import com.mica.music.data.DEFAULT_LETTER_SEAL_OPACITY_PERCENT
import com.mica.music.data.DEFAULT_LETTER_SEAL_ROTATION_DEGREES
import com.mica.music.data.DEFAULT_LETTER_SEAL_SIZE_DP
import com.mica.music.data.LyricsSlot
import com.mica.music.data.MAX_LETTER_SEAL_OPACITY_PERCENT
import com.mica.music.data.MAX_LETTER_SEAL_ROTATION_DEGREES
import com.mica.music.data.MAX_LETTER_SEAL_SIZE_DP
import com.mica.music.data.MIN_LETTER_SEAL_OPACITY_PERCENT
import com.mica.music.data.MIN_LETTER_SEAL_ROTATION_DEGREES
import com.mica.music.data.MIN_LETTER_SEAL_SIZE_DP

/** 歌词页、通知歌词与播放页歌词文字相关偏好。 */
object LyricsPreferences {
    internal enum class NotificationLyricsChange {
        ENABLED,
        CAR_BLUETOOTH_ENABLED,
        DISPLAY,
        SOURCE,
    }
    private const val KEY_LYRIC_SPLIT_ENABLED = "lyric_split_enabled"
    private const val KEY_LYRICS_BILINGUAL_DISPLAY_MODE = "lyrics_bilingual_display_mode"
    private const val KEY_LYRIC_LINE_FILL_ENABLED = "lyric_line_fill_enabled"
    private const val KEY_PLAYER_PAGE_TEXT_COLOR = "player_page_text_color"
    private const val KEY_LYRICS_PAGE_TEXT_COLOR = "lyrics_page_text_color"
    private const val KEY_LYRICS_PAGE_ALIGNMENT = "lyrics_page_alignment"
    private const val KEY_LYRICS_PAGE_THEME = "lyrics_page_theme"
    private const val KEY_LETTER_SEAL_CUSTOM_IMAGE_PATH = "letter_seal_custom_image_path"
    private const val KEY_LETTER_SEAL_SIZE_DP = "letter_seal_size_dp"
    private const val KEY_LETTER_SEAL_OPACITY_PERCENT = "letter_seal_opacity_percent"
    private const val KEY_LETTER_SEAL_ROTATION_DEGREES = "letter_seal_rotation_degrees"
    private const val KEY_LYRICS_WORD_ANIMATION_PRESET = "lyrics_word_animation_preset"
    private const val KEY_LYRICS_PAGE_FONT_SIZE = "lyrics_page_font_size"
    private const val KEY_LYRICS_PAGE_TRANSLATION_FONT_SIZE = "lyrics_page_translation_font_size"
    private const val KEY_LYRICS_PAGE_LINE_SPACING = "lyrics_page_line_spacing"
    private const val KEY_LYRICS_PAGE_IMMERSIVE = "lyrics_page_immersive"
    private const val KEY_NOTIFICATION_LYRICS_ENABLED = "notification_lyrics_enabled"
    private const val KEY_CAR_BLUETOOTH_LYRICS_ENABLED = "car_bluetooth_lyrics_enabled"
    private const val KEY_INFO_ROW_LYRICS_ENABLED = "info_row_lyrics_enabled"
    private const val KEY_INFO_ROW_WORD_LYRICS_ENABLED = "info_row_word_lyrics_enabled"
    private const val KEY_LYRICS_SLOT_PRIORITY = "lyrics_slot_priority"

    fun lyricsSlotPriority(context: Context): List<LyricsSlot> {
        val slots = MicaSettingsStore.prefs(context)
            .getString(KEY_LYRICS_SLOT_PRIORITY, null)
            ?.split(',')
            ?.mapNotNull { value -> runCatching { LyricsSlot.valueOf(value) }.getOrNull() }
            .orEmpty()
        return slots.takeIf { it.size == LyricsSlot.entries.size && it.toSet() == LyricsSlot.entries.toSet() }
            ?: DEFAULT_LYRICS_SLOT_PRIORITY
    }

    fun setLyricsSlotPriority(context: Context, priority: List<LyricsSlot>) {
        val normalized = priority.takeIf {
            it.size == LyricsSlot.entries.size && it.toSet() == LyricsSlot.entries.toSet()
        } ?: DEFAULT_LYRICS_SLOT_PRIORITY
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_LYRICS_SLOT_PRIORITY, normalized.joinToString(",", transform = LyricsSlot::name))
            .apply()
    }

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

    fun letterSealCustomImagePath(context: Context): String? =
        MicaSettingsStore.prefs(context).getString(KEY_LETTER_SEAL_CUSTOM_IMAGE_PATH, null)

    fun setLetterSealCustomImagePath(context: Context, path: String?) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_LETTER_SEAL_CUSTOM_IMAGE_PATH, path)
            .apply()
    }

    fun letterSealSizeDp(context: Context): Int =
        MicaSettingsStore.prefs(context)
            .getInt(KEY_LETTER_SEAL_SIZE_DP, DEFAULT_LETTER_SEAL_SIZE_DP)
            .coerceIn(MIN_LETTER_SEAL_SIZE_DP, MAX_LETTER_SEAL_SIZE_DP)

    fun setLetterSealSizeDp(context: Context, sizeDp: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(
                KEY_LETTER_SEAL_SIZE_DP,
                sizeDp.coerceIn(MIN_LETTER_SEAL_SIZE_DP, MAX_LETTER_SEAL_SIZE_DP),
            )
            .apply()
    }

    fun letterSealOpacityPercent(context: Context): Int =
        MicaSettingsStore.prefs(context)
            .getInt(KEY_LETTER_SEAL_OPACITY_PERCENT, DEFAULT_LETTER_SEAL_OPACITY_PERCENT)
            .coerceIn(MIN_LETTER_SEAL_OPACITY_PERCENT, MAX_LETTER_SEAL_OPACITY_PERCENT)

    fun setLetterSealOpacityPercent(context: Context, opacityPercent: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(
                KEY_LETTER_SEAL_OPACITY_PERCENT,
                opacityPercent.coerceIn(
                    MIN_LETTER_SEAL_OPACITY_PERCENT,
                    MAX_LETTER_SEAL_OPACITY_PERCENT,
                ),
            )
            .apply()
    }

    fun letterSealRotationDegrees(context: Context): Int =
        MicaSettingsStore.prefs(context)
            .getInt(KEY_LETTER_SEAL_ROTATION_DEGREES, DEFAULT_LETTER_SEAL_ROTATION_DEGREES)
            .coerceIn(MIN_LETTER_SEAL_ROTATION_DEGREES, MAX_LETTER_SEAL_ROTATION_DEGREES)

    fun setLetterSealRotationDegrees(context: Context, rotationDegrees: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(
                KEY_LETTER_SEAL_ROTATION_DEGREES,
                rotationDegrees.coerceIn(
                    MIN_LETTER_SEAL_ROTATION_DEGREES,
                    MAX_LETTER_SEAL_ROTATION_DEGREES,
                ),
            )
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

    fun carBluetoothLyricsEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_CAR_BLUETOOTH_LYRICS_ENABLED, false)

    fun setCarBluetoothLyricsEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_CAR_BLUETOOTH_LYRICS_ENABLED, enabled)
            .apply()
    }

    fun infoRowLyricsEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_INFO_ROW_LYRICS_ENABLED, false)

    fun setInfoRowLyricsEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_INFO_ROW_LYRICS_ENABLED, enabled)
            .apply()
    }

    fun infoRowWordLyricsEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_INFO_ROW_WORD_LYRICS_ENABLED, false)

    fun setInfoRowWordLyricsEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_INFO_ROW_WORD_LYRICS_ENABLED, enabled)
            .apply()
    }

    internal fun registerNotificationLyricsChangeListener(
        context: Context,
        onChange: (NotificationLyricsChange) -> Unit,
    ): () -> Unit {
        val preferences = MicaSettingsStore.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val change = when (key) {
                KEY_NOTIFICATION_LYRICS_ENABLED -> NotificationLyricsChange.ENABLED
                KEY_CAR_BLUETOOTH_LYRICS_ENABLED -> NotificationLyricsChange.CAR_BLUETOOTH_ENABLED
                KEY_LYRIC_SPLIT_ENABLED,
                KEY_LYRICS_BILINGUAL_DISPLAY_MODE,
                -> NotificationLyricsChange.DISPLAY
                KEY_LYRICS_SLOT_PRIORITY -> NotificationLyricsChange.SOURCE
                else -> null
            }
            change?.let(onChange)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
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
