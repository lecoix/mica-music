package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.data.DEFAULT_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.DEFAULT_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.DEFAULT_EXTERNAL_LYRICS_COLORS
import com.mica.music.data.DEFAULT_EXTERNAL_LYRICS_GRADIENT_ANGLE
import com.mica.music.data.DEFAULT_EXTERNAL_LYRICS_WIDTH_PERCENT
import com.mica.music.data.DEFAULT_EXTERNAL_LYRICS_GLOW_STRENGTH_PERCENT
import com.mica.music.data.DEFAULT_EXTERNAL_LYRICS_OPACITY_PERCENT
import com.mica.music.data.DEFAULT_EXTERNAL_LYRICS_SHADOW_STRENGTH_PERCENT
import com.mica.music.data.DEFAULT_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP
import com.mica.music.data.DEFAULT_STATUS_BAR_LYRICS_TOP_OFFSET_DP
import com.mica.music.data.ExternalLyricsColorMode
import com.mica.music.data.ExternalLyricsMode
import com.mica.music.data.ExternalLyricsStyle
import com.mica.music.data.ExternalLyricsVisibilityMode
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
import com.mica.music.data.MAX_EXTERNAL_LYRICS_COLORS
import com.mica.music.data.MAX_EXTERNAL_LYRICS_WIDTH_PERCENT
import com.mica.music.data.MAX_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP
import com.mica.music.data.MAX_STATUS_BAR_LYRICS_TOP_OFFSET_DP
import com.mica.music.data.MIN_EXTERNAL_LYRICS_WIDTH_PERCENT
import com.mica.music.data.MIN_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP
import com.mica.music.data.MIN_STATUS_BAR_LYRICS_TOP_OFFSET_DP
import com.mica.music.data.normalizeExternalLyricsColors
import com.mica.music.data.normalizeExternalLyricsEffectPercent

/** 歌词页、通知歌词与播放页歌词文字相关偏好。 */
object LyricsPreferences {
    internal enum class NotificationLyricsChange {
        ENABLED,
        DESKTOP_ENABLED,
        STATUS_BAR_ENABLED,
        DISPLAY,
        SOURCE,
    }
    private const val KEY_LYRIC_SPLIT_ENABLED = "lyric_split_enabled"
    private const val KEY_LYRIC_READING_ENABLED = "lyric_reading_enabled"
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
    private const val KEY_DESKTOP_LYRICS_ENABLED = "desktop_lyrics_enabled"
    private const val KEY_DESKTOP_LYRICS_X = "desktop_lyrics_x"
    private const val KEY_DESKTOP_LYRICS_Y = "desktop_lyrics_y"
    private const val KEY_DESKTOP_LYRICS_LOCKED = "desktop_lyrics_locked"
    private const val KEY_DESKTOP_LYRICS_ORIGINAL_FONT_SIZE = "desktop_lyrics_original_font_size"
    private const val KEY_DESKTOP_LYRICS_TRANSLATION_FONT_SIZE = "desktop_lyrics_translation_font_size"
    private const val KEY_DESKTOP_LYRICS_BILINGUAL_DISPLAY_MODE = "desktop_lyrics_bilingual_display_mode"
    private const val KEY_DESKTOP_LYRICS_WORD_BY_WORD_ENABLED = "desktop_lyrics_word_by_word_enabled"
    private const val KEY_EXTERNAL_LYRICS_MODE = "external_lyrics_mode"
    private const val KEY_DESKTOP_LYRICS_WIDTH_PERCENT = "desktop_lyrics_width_percent"
    private const val KEY_STATUS_BAR_LYRICS_ENABLED = "status_bar_lyrics_enabled"
    private const val KEY_STATUS_BAR_LYRICS_TOP_OFFSET_DP = "status_bar_lyrics_top_offset_dp"
    private const val KEY_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP =
        "status_bar_lyrics_horizontal_offset_dp"
    private const val KEY_STATUS_BAR_LYRICS_ORIGINAL_FONT_SIZE = "status_bar_lyrics_original_font_size"
    private const val KEY_STATUS_BAR_LYRICS_TRANSLATION_FONT_SIZE = "status_bar_lyrics_translation_font_size"
    private const val KEY_STATUS_BAR_LYRICS_SPLIT_ENABLED = "status_bar_lyrics_split_enabled"
    private const val KEY_STATUS_BAR_LYRICS_BILINGUAL_DISPLAY_MODE = "status_bar_lyrics_bilingual_display_mode"
    private const val KEY_STATUS_BAR_LYRICS_WORD_BY_WORD_ENABLED = "status_bar_lyrics_word_by_word_enabled"
    private const val KEY_STATUS_BAR_LYRICS_TEXT_ALIGNMENT = "status_bar_lyrics_text_alignment"
    private const val KEY_STATUS_BAR_LYRICS_WIDTH_PERCENT = "status_bar_lyrics_width_percent"
    private const val KEY_EXTERNAL_LYRICS_VISIBILITY_MODE = "external_lyrics_visibility_mode"
    private const val KEY_EXTERNAL_LYRICS_COLOR_MODE = "external_lyrics_color_mode"
    private const val KEY_EXTERNAL_LYRICS_COLOR_COUNT = "external_lyrics_color_count"
    private const val KEY_EXTERNAL_LYRICS_GRADIENT_ANGLE = "external_lyrics_gradient_angle"
    private const val KEY_EXTERNAL_LYRICS_COLOR_PREFIX = "external_lyrics_color_"
    private const val KEY_EXTERNAL_LYRICS_OPACITY_PERCENT = "external_lyrics_opacity_percent"
    private const val KEY_EXTERNAL_LYRICS_SHADOW_STRENGTH_PERCENT =
        "external_lyrics_shadow_strength_percent"
    private const val KEY_EXTERNAL_LYRICS_GLOW_STRENGTH_PERCENT =
        "external_lyrics_glow_strength_percent"
    private const val KEY_INFO_ROW_LYRICS_ENABLED = "info_row_lyrics_enabled"
    private const val KEY_INFO_ROW_WORD_LYRICS_ENABLED = "info_row_word_lyrics_enabled"
    private const val KEY_LYRICS_SLOT_PRIORITY = "lyrics_slot_priority"
    private const val KEY_GLOBAL_LYRICS_OFFSET_MS = "global_lyrics_offset_ms"

    fun globalLyricsOffsetMs(context: Context): Int =
        MicaSettingsStore.prefs(context)
            .getInt(KEY_GLOBAL_LYRICS_OFFSET_MS, 0)
            .coerceIn(com.mica.music.data.MIN_LYRICS_OFFSET_MS, com.mica.music.data.MAX_LYRICS_OFFSET_MS)

    fun setGlobalLyricsOffsetMs(context: Context, offsetMs: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(
                KEY_GLOBAL_LYRICS_OFFSET_MS,
                offsetMs.coerceIn(
                    com.mica.music.data.MIN_LYRICS_OFFSET_MS,
                    com.mica.music.data.MAX_LYRICS_OFFSET_MS,
                ),
            )
            .apply()
    }

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

    fun lyricReadingEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_LYRIC_READING_ENABLED, true)

    fun setLyricReadingEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_LYRIC_READING_ENABLED, enabled)
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

    fun desktopLyricsEnabled(context: Context): Boolean =
        externalLyricsMode(context) == ExternalLyricsMode.DESKTOP

    fun setDesktopLyricsEnabled(context: Context, enabled: Boolean) {
        val current = externalLyricsMode(context)
        setExternalLyricsMode(
            context,
            when {
                enabled -> ExternalLyricsMode.DESKTOP
                current == ExternalLyricsMode.DESKTOP -> ExternalLyricsMode.OFF
                else -> current
            },
        )
    }

    fun externalLyricsMode(context: Context): ExternalLyricsMode {
        val preferences = MicaSettingsStore.prefs(context)
        preferences.getString(KEY_EXTERNAL_LYRICS_MODE, null)?.let {
            return ExternalLyricsMode.fromStorage(it)
        }
        return when {
            preferences.getBoolean(KEY_DESKTOP_LYRICS_ENABLED, false) -> ExternalLyricsMode.DESKTOP
            preferences.getBoolean(KEY_STATUS_BAR_LYRICS_ENABLED, false) -> ExternalLyricsMode.STATUS_BAR
            else -> ExternalLyricsMode.OFF
        }
    }

    fun setExternalLyricsMode(context: Context, mode: ExternalLyricsMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_EXTERNAL_LYRICS_MODE, mode.storageValue)
            .putBoolean(KEY_DESKTOP_LYRICS_ENABLED, mode == ExternalLyricsMode.DESKTOP)
            .putBoolean(KEY_STATUS_BAR_LYRICS_ENABLED, mode == ExternalLyricsMode.STATUS_BAR)
            .apply()
    }

    /** Horizontal offset from the centered overlay position, in physical pixels. */
    fun desktopLyricsX(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_DESKTOP_LYRICS_X, 0)

    fun setDesktopLyricsX(context: Context, x: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_DESKTOP_LYRICS_X, x)
            .apply()
    }

    /** Top offset in physical pixels; -1 means use the first-run default. */
    fun desktopLyricsY(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_DESKTOP_LYRICS_Y, -1)

    fun setDesktopLyricsY(context: Context, y: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_DESKTOP_LYRICS_Y, y)
            .apply()
    }

    fun desktopLyricsLocked(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_DESKTOP_LYRICS_LOCKED, false)

    fun setDesktopLyricsLocked(context: Context, locked: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_DESKTOP_LYRICS_LOCKED, locked)
            .apply()
    }

    fun desktopLyricsOriginalFontSizeSp(context: Context): Int =
        readLyricsPageFontSizeSp(
            context,
            KEY_DESKTOP_LYRICS_ORIGINAL_FONT_SIZE,
            lyricsPageFontSizeSp(context),
        )

    fun setDesktopLyricsOriginalFontSizeSp(context: Context, fontSizeSp: Int) {
        putExternalLyricsFontSize(context, KEY_DESKTOP_LYRICS_ORIGINAL_FONT_SIZE, fontSizeSp)
    }

    fun desktopLyricsTranslationFontSizeSp(context: Context): Int =
        readLyricsPageFontSizeSp(
            context,
            KEY_DESKTOP_LYRICS_TRANSLATION_FONT_SIZE,
            lyricsPageTranslationFontSizeSp(context),
        )

    fun setDesktopLyricsTranslationFontSizeSp(context: Context, fontSizeSp: Int) {
        putExternalLyricsFontSize(context, KEY_DESKTOP_LYRICS_TRANSLATION_FONT_SIZE, fontSizeSp)
    }

    fun desktopLyricsBilingualDisplayMode(context: Context): LyricsBilingualDisplayMode =
        LyricsBilingualDisplayMode.fromStorage(
            MicaSettingsStore.prefs(context).getString(
                KEY_DESKTOP_LYRICS_BILINGUAL_DISPLAY_MODE,
                null,
            ) ?: lyricsBilingualDisplayMode(context).storageValue,
        )

    fun setDesktopLyricsBilingualDisplayMode(
        context: Context,
        mode: LyricsBilingualDisplayMode,
    ) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_DESKTOP_LYRICS_BILINGUAL_DISPLAY_MODE, mode.storageValue)
            .apply()
    }

    fun desktopLyricsWordByWordEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context)
            .getBoolean(KEY_DESKTOP_LYRICS_WORD_BY_WORD_ENABLED, true)

    fun setDesktopLyricsWordByWordEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_DESKTOP_LYRICS_WORD_BY_WORD_ENABLED, enabled)
            .apply()
    }

    fun statusBarLyricsEnabled(context: Context): Boolean =
        externalLyricsMode(context) == ExternalLyricsMode.STATUS_BAR

    fun setStatusBarLyricsEnabled(context: Context, enabled: Boolean) {
        val current = externalLyricsMode(context)
        setExternalLyricsMode(
            context,
            when {
                enabled -> ExternalLyricsMode.STATUS_BAR
                current == ExternalLyricsMode.STATUS_BAR -> ExternalLyricsMode.OFF
                else -> current
            },
        )
    }

    fun desktopLyricsWidthPercent(context: Context): Int =
        externalLyricsWidthPercent(context, KEY_DESKTOP_LYRICS_WIDTH_PERCENT)

    fun setDesktopLyricsWidthPercent(context: Context, percent: Int) {
        setExternalLyricsWidthPercent(context, KEY_DESKTOP_LYRICS_WIDTH_PERCENT, percent)
    }

    /** Additional top offset below the system status bar, in density-independent pixels. */
    fun statusBarLyricsTopOffsetDp(context: Context): Int =
        MicaSettingsStore.prefs(context)
            .getInt(KEY_STATUS_BAR_LYRICS_TOP_OFFSET_DP, DEFAULT_STATUS_BAR_LYRICS_TOP_OFFSET_DP)
            .coerceIn(MIN_STATUS_BAR_LYRICS_TOP_OFFSET_DP, MAX_STATUS_BAR_LYRICS_TOP_OFFSET_DP)

    fun setStatusBarLyricsTopOffsetDp(context: Context, offsetDp: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(
                KEY_STATUS_BAR_LYRICS_TOP_OFFSET_DP,
                offsetDp.coerceIn(MIN_STATUS_BAR_LYRICS_TOP_OFFSET_DP, MAX_STATUS_BAR_LYRICS_TOP_OFFSET_DP),
            )
            .apply()
    }

    /** Horizontal fine adjustment from the centered status-bar lyric position, in dp. */
    fun statusBarLyricsHorizontalOffsetDp(context: Context): Int =
        MicaSettingsStore.prefs(context)
            .getInt(
                KEY_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP,
                DEFAULT_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP,
            )
            .coerceIn(
                MIN_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP,
                MAX_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP,
            )

    fun setStatusBarLyricsHorizontalOffsetDp(context: Context, offsetDp: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(
                KEY_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP,
                offsetDp.coerceIn(
                    MIN_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP,
                    MAX_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP,
                ),
            )
            .apply()
    }

    fun statusBarLyricsOriginalFontSizeSp(context: Context): Int =
        readLyricsPageFontSizeSp(
            context,
            KEY_STATUS_BAR_LYRICS_ORIGINAL_FONT_SIZE,
            lyricsPageFontSizeSp(context),
        )

    fun setStatusBarLyricsOriginalFontSizeSp(context: Context, fontSizeSp: Int) {
        putExternalLyricsFontSize(context, KEY_STATUS_BAR_LYRICS_ORIGINAL_FONT_SIZE, fontSizeSp)
    }

    fun statusBarLyricsTranslationFontSizeSp(context: Context): Int =
        readLyricsPageFontSizeSp(
            context,
            KEY_STATUS_BAR_LYRICS_TRANSLATION_FONT_SIZE,
            lyricsPageTranslationFontSizeSp(context),
        )

    fun setStatusBarLyricsTranslationFontSizeSp(context: Context, fontSizeSp: Int) {
        putExternalLyricsFontSize(context, KEY_STATUS_BAR_LYRICS_TRANSLATION_FONT_SIZE, fontSizeSp)
    }

    fun statusBarLyricsSplitEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_STATUS_BAR_LYRICS_SPLIT_ENABLED, true)

    fun setStatusBarLyricsSplitEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_STATUS_BAR_LYRICS_SPLIT_ENABLED, enabled)
            .apply()
    }

    fun statusBarLyricsBilingualDisplayMode(context: Context): LyricsBilingualDisplayMode =
        LyricsBilingualDisplayMode.fromStorage(
            MicaSettingsStore.prefs(context).getString(
                KEY_STATUS_BAR_LYRICS_BILINGUAL_DISPLAY_MODE,
                null,
            ) ?: lyricsBilingualDisplayMode(context).storageValue,
        )

    fun setStatusBarLyricsBilingualDisplayMode(
        context: Context,
        mode: LyricsBilingualDisplayMode,
    ) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_STATUS_BAR_LYRICS_BILINGUAL_DISPLAY_MODE, mode.storageValue)
            .apply()
    }

    fun statusBarLyricsWordByWordEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context)
            .getBoolean(KEY_STATUS_BAR_LYRICS_WORD_BY_WORD_ENABLED, true)

    fun setStatusBarLyricsWordByWordEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_STATUS_BAR_LYRICS_WORD_BY_WORD_ENABLED, enabled)
            .apply()
    }

    fun statusBarLyricsTextAlignment(context: Context): LyricsPageAlignment =
        LyricsPageAlignment.fromStorage(
            MicaSettingsStore.prefs(context).getString(
                KEY_STATUS_BAR_LYRICS_TEXT_ALIGNMENT,
                LyricsPageAlignment.CENTER.storageValue,
            ),
        )

    fun setStatusBarLyricsTextAlignment(context: Context, alignment: LyricsPageAlignment) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_STATUS_BAR_LYRICS_TEXT_ALIGNMENT, alignment.storageValue)
            .apply()
    }

    fun statusBarLyricsWidthPercent(context: Context): Int =
        externalLyricsWidthPercent(context, KEY_STATUS_BAR_LYRICS_WIDTH_PERCENT)

    fun setStatusBarLyricsWidthPercent(context: Context, percent: Int) {
        setExternalLyricsWidthPercent(context, KEY_STATUS_BAR_LYRICS_WIDTH_PERCENT, percent)
    }

    fun externalLyricsVisibilityMode(context: Context): ExternalLyricsVisibilityMode =
        ExternalLyricsVisibilityMode.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_EXTERNAL_LYRICS_VISIBILITY_MODE, null),
        )

    fun setExternalLyricsVisibilityMode(context: Context, mode: ExternalLyricsVisibilityMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_EXTERNAL_LYRICS_VISIBILITY_MODE, mode.storageValue)
            .apply()
    }

    fun externalLyricsColorMode(context: Context): ExternalLyricsColorMode =
        ExternalLyricsColorMode.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_EXTERNAL_LYRICS_COLOR_MODE, null),
        )

    fun setExternalLyricsColorMode(context: Context, mode: ExternalLyricsColorMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_EXTERNAL_LYRICS_COLOR_MODE, mode.storageValue)
            .apply()
    }

    fun externalLyricsColorCount(context: Context): Int =
        MicaSettingsStore.prefs(context)
            .getInt(KEY_EXTERNAL_LYRICS_COLOR_COUNT, 1)
            .coerceIn(1, MAX_EXTERNAL_LYRICS_COLORS)

    fun setExternalLyricsColorCount(context: Context, count: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_EXTERNAL_LYRICS_COLOR_COUNT, count.coerceIn(1, MAX_EXTERNAL_LYRICS_COLORS))
            .apply()
    }

    fun externalLyricsGradientAngleDegrees(context: Context): Int =
        MicaSettingsStore.prefs(context)
            .getInt(KEY_EXTERNAL_LYRICS_GRADIENT_ANGLE, DEFAULT_EXTERNAL_LYRICS_GRADIENT_ANGLE)
            .coerceIn(0, 360)

    fun setExternalLyricsGradientAngleDegrees(context: Context, angleDegrees: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_EXTERNAL_LYRICS_GRADIENT_ANGLE, angleDegrees.coerceIn(0, 360))
            .apply()
    }

    fun externalLyricsColors(context: Context): List<Int> {
        val preferences = MicaSettingsStore.prefs(context)
        val count = externalLyricsColorCount(context)
        val colors = (0 until count).map { index ->
            preferences.all[externalColorKey(index)] as? Int
                ?: DEFAULT_EXTERNAL_LYRICS_COLORS[index]
        }
        return normalizeExternalLyricsColors(colors)
    }

    fun setExternalLyricsColors(context: Context, colors: List<Int>) {
        val normalized = normalizeExternalLyricsColors(colors)
        MicaSettingsStore.prefs(context).edit().apply {
            normalized.forEachIndexed { index, color -> putInt(externalColorKey(index), color) }
        }.apply()
    }

    fun externalLyricsOpacityPercent(context: Context): Int =
        externalLyricsEffectPercent(
            context,
            KEY_EXTERNAL_LYRICS_OPACITY_PERCENT,
            DEFAULT_EXTERNAL_LYRICS_OPACITY_PERCENT,
        )

    fun setExternalLyricsOpacityPercent(context: Context, percent: Int) {
        setExternalLyricsEffectPercent(context, KEY_EXTERNAL_LYRICS_OPACITY_PERCENT, percent)
    }

    fun externalLyricsShadowStrengthPercent(context: Context): Int =
        externalLyricsEffectPercent(
            context,
            KEY_EXTERNAL_LYRICS_SHADOW_STRENGTH_PERCENT,
            DEFAULT_EXTERNAL_LYRICS_SHADOW_STRENGTH_PERCENT,
        )

    fun setExternalLyricsShadowStrengthPercent(context: Context, percent: Int) {
        setExternalLyricsEffectPercent(context, KEY_EXTERNAL_LYRICS_SHADOW_STRENGTH_PERCENT, percent)
    }

    fun externalLyricsGlowStrengthPercent(context: Context): Int =
        externalLyricsEffectPercent(
            context,
            KEY_EXTERNAL_LYRICS_GLOW_STRENGTH_PERCENT,
            DEFAULT_EXTERNAL_LYRICS_GLOW_STRENGTH_PERCENT,
        )

    fun setExternalLyricsGlowStrengthPercent(context: Context, percent: Int) {
        setExternalLyricsEffectPercent(context, KEY_EXTERNAL_LYRICS_GLOW_STRENGTH_PERCENT, percent)
    }

    fun externalLyricsStyle(context: Context): ExternalLyricsStyle = ExternalLyricsStyle(
        visibilityMode = externalLyricsVisibilityMode(context),
        colorMode = externalLyricsColorMode(context),
        colorsArgb = externalLyricsColors(context),
        gradientAngleDegrees = externalLyricsGradientAngleDegrees(context),
        desktopOriginalFontSizeSp = desktopLyricsOriginalFontSizeSp(context),
        desktopTranslationFontSizeSp = desktopLyricsTranslationFontSizeSp(context),
        statusBarOriginalFontSizeSp = statusBarLyricsOriginalFontSizeSp(context),
        statusBarTranslationFontSizeSp = statusBarLyricsTranslationFontSizeSp(context),
        desktopBilingualDisplayMode = desktopLyricsBilingualDisplayMode(context),
        statusBarBilingualDisplayMode = statusBarLyricsBilingualDisplayMode(context),
        desktopWidthPercent = desktopLyricsWidthPercent(context),
        statusBarWidthPercent = statusBarLyricsWidthPercent(context),
        statusBarTextAlignment = statusBarLyricsTextAlignment(context),
        opacityPercent = externalLyricsOpacityPercent(context),
        shadowStrengthPercent = externalLyricsShadowStrengthPercent(context),
        glowStrengthPercent = externalLyricsGlowStrengthPercent(context),
    )

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
                KEY_DESKTOP_LYRICS_ENABLED -> NotificationLyricsChange.DESKTOP_ENABLED
                KEY_STATUS_BAR_LYRICS_ENABLED -> NotificationLyricsChange.STATUS_BAR_ENABLED
                KEY_EXTERNAL_LYRICS_MODE -> NotificationLyricsChange.DISPLAY
                KEY_DESKTOP_LYRICS_LOCKED -> NotificationLyricsChange.DISPLAY
                KEY_LYRIC_SPLIT_ENABLED,
                KEY_LYRICS_BILINGUAL_DISPLAY_MODE,
                KEY_DESKTOP_LYRICS_ORIGINAL_FONT_SIZE,
                KEY_DESKTOP_LYRICS_TRANSLATION_FONT_SIZE,
                KEY_DESKTOP_LYRICS_BILINGUAL_DISPLAY_MODE,
                KEY_DESKTOP_LYRICS_WORD_BY_WORD_ENABLED,
                KEY_DESKTOP_LYRICS_WIDTH_PERCENT,
                KEY_STATUS_BAR_LYRICS_ORIGINAL_FONT_SIZE,
                KEY_STATUS_BAR_LYRICS_TRANSLATION_FONT_SIZE,
                KEY_STATUS_BAR_LYRICS_SPLIT_ENABLED,
                KEY_STATUS_BAR_LYRICS_BILINGUAL_DISPLAY_MODE,
                KEY_STATUS_BAR_LYRICS_WORD_BY_WORD_ENABLED,
                KEY_STATUS_BAR_LYRICS_TEXT_ALIGNMENT,
                KEY_STATUS_BAR_LYRICS_WIDTH_PERCENT,
                KEY_EXTERNAL_LYRICS_VISIBILITY_MODE,
                KEY_EXTERNAL_LYRICS_COLOR_MODE,
                KEY_EXTERNAL_LYRICS_COLOR_COUNT,
                KEY_EXTERNAL_LYRICS_GRADIENT_ANGLE,
                KEY_EXTERNAL_LYRICS_OPACITY_PERCENT,
                KEY_EXTERNAL_LYRICS_SHADOW_STRENGTH_PERCENT,
                KEY_EXTERNAL_LYRICS_GLOW_STRENGTH_PERCENT,
                -> NotificationLyricsChange.DISPLAY
                KEY_LYRICS_SLOT_PRIORITY -> NotificationLyricsChange.SOURCE
                KEY_GLOBAL_LYRICS_OFFSET_MS -> NotificationLyricsChange.DISPLAY
                else -> if (key?.startsWith(KEY_EXTERNAL_LYRICS_COLOR_PREFIX) == true) {
                    NotificationLyricsChange.DISPLAY
                } else {
                    null
                }
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

    private fun putExternalLyricsFontSize(context: Context, key: String, fontSizeSp: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(key, fontSizeSp.coerceIn(MIN_LYRICS_PAGE_FONT_SIZE_SP, MAX_LYRICS_PAGE_FONT_SIZE_SP))
            .apply()
    }

    private fun externalLyricsWidthPercent(context: Context, key: String): Int =
        MicaSettingsStore.prefs(context)
            .getInt(key, DEFAULT_EXTERNAL_LYRICS_WIDTH_PERCENT)
            .coerceIn(MIN_EXTERNAL_LYRICS_WIDTH_PERCENT, MAX_EXTERNAL_LYRICS_WIDTH_PERCENT)

    private fun setExternalLyricsWidthPercent(context: Context, key: String, percent: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(
                key,
                percent.coerceIn(MIN_EXTERNAL_LYRICS_WIDTH_PERCENT, MAX_EXTERNAL_LYRICS_WIDTH_PERCENT),
            )
            .apply()
    }

    private fun externalLyricsEffectPercent(context: Context, key: String, defaultValue: Int): Int =
        normalizeExternalLyricsEffectPercent(
            MicaSettingsStore.prefs(context).getInt(key, defaultValue),
        )

    private fun setExternalLyricsEffectPercent(context: Context, key: String, percent: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(key, normalizeExternalLyricsEffectPercent(percent))
            .apply()
    }

    private fun externalColorKey(index: Int): String = "$KEY_EXTERNAL_LYRICS_COLOR_PREFIX$index"
}
