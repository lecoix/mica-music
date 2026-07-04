package com.mica.music.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.mica.music.data.scanner.ExcludedScanDirectories
import com.mica.music.data.scanner.ScanOptions
import com.mica.music.media.eq.EqBandConstants
import com.mica.music.ui.theme.CustomMicaBackground
import com.mica.music.ui.theme.MicaPreset

/**
 * 轻量偏好存储；设置页读写同名 key。
 */
object AppPreferences {

    private const val PREFS_NAME = "mica_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
    /** 旧版 key，迁移到 [KEY_HIDE_STATUS_BAR] */
    private const val KEY_IMMERSIVE_PLAYER_STATUS_BAR = "immersive_player_status_bar"
    private const val KEY_MIN_TRACK_DURATION_SEC = "min_track_duration_sec"
    private const val KEY_INCLUDE_NON_MUSIC_AUDIO = "include_non_music_audio"
    private const val KEY_DEEP_METADATA_PROBE = "deep_metadata_probe"
    private const val KEY_EXCLUDED_SCAN_DIRECTORIES = "excluded_scan_directories"
    private const val KEY_LIBRARY_TREE_URI = "library_tree_uri"
    private const val KEY_LIBRARY_FOLDER_LABEL = "library_folder_label"
    private const val KEY_LAST_SCAN_SOURCE = "last_scan_source"
    private const val KEY_SONG_SORT_FIELD = "song_sort_field"
    private const val KEY_SONG_SORT_DIRECTION = "song_sort_direction"
    private const val KEY_PLAYER_LOWER_BACKGROUND = "player_lower_background"
    private const val KEY_MINI_PLAYER_STYLE = "mini_player_style"
    private const val KEY_MINI_PLAYER_LYRICS_ENABLED = "mini_player_lyrics_enabled"
    private const val KEY_MINI_PLAYER_SWIPE_ENABLED = "mini_player_swipe_enabled"
    private const val KEY_MINI_PLAYER_LEFT_SWIPE_ACTION = "mini_player_left_swipe_action"
    private const val KEY_MINI_PLAYER_RIGHT_SWIPE_ACTION = "mini_player_right_swipe_action"
    private const val KEY_COVER_DISPLAY_MODE = "cover_display_mode"
    private const val KEY_PLAYER_COVER_FLOW_MODE = "player_cover_flow_mode"
    private const val KEY_PARTICLE_COVER_EROSION_SCALE = "particle_cover_erosion_scale"
    private const val KEY_PARTICLE_COVER_FEATHER_SCALE = "particle_cover_feather_scale"
    private const val KEY_PARTICLE_COVER_EDGE_DENSITY = "particle_cover_edge_density"
    private const val KEY_PARTICLE_COVER_EDGE_ALPHA = "particle_cover_edge_alpha"
    private const val KEY_PARTICLE_COVER_EDGE_TRAVEL = "particle_cover_edge_travel"
    private const val KEY_PARTICLE_COVER_TRANSITION_DENSITY = "particle_cover_transition_density"
    private const val KEY_APP_ACCENT_COLOR = "app_accent_color"
    private const val KEY_CUSTOM_ACCENT_COLOR = "custom_accent_color"
    private const val KEY_MICA_BACKGROUND_PRESET = "mica_background_preset"
    private const val KEY_CUSTOM_MICA_START = "custom_mica_start"
    private const val KEY_CUSTOM_MICA_END = "custom_mica_end"
    private const val KEY_CUSTOM_MICA_SINGLE_COLOR = "custom_mica_single_color"
    private const val KEY_COVER_EDGE_PROGRESS = "cover_edge_progress"
    private const val KEY_KEEP_SCREEN_ON_WHEN_PLAYING = "keep_screen_on_when_playing"
    private const val KEY_PLAYER_IMMERSIVE_LOWER = "player_immersive_lower"
    private const val KEY_STRIP_SONG_TITLE_PARENTHESES = "strip_song_title_parentheses"
    private const val KEY_LYRIC_SPLIT_ENABLED = "lyric_split_enabled"
    private const val KEY_LYRICS_BILINGUAL_DISPLAY_MODE = "lyrics_bilingual_display_mode"
    private const val KEY_LYRIC_LINE_FILL_ENABLED = "lyric_line_fill_enabled"
    private const val KEY_LYRICS_PAGE_ALIGNMENT = "lyrics_page_alignment"
    private const val KEY_LYRICS_PAGE_FONT_SIZE = "lyrics_page_font_size"
    private const val KEY_LYRICS_PAGE_TRANSLATION_FONT_SIZE = "lyrics_page_translation_font_size"
    private const val KEY_LYRICS_PAGE_IMMERSIVE = "lyrics_page_immersive"
    private const val KEY_NOTIFICATION_LYRICS_ENABLED = "notification_lyrics_enabled"
    private const val KEY_SPECTRUM_ENABLED = "spectrum_enabled"
    private const val KEY_EQUALIZER_ENABLED = "equalizer_enabled"
    private const val KEY_EQUALIZER_PRESET = "equalizer_preset"
    private const val KEY_EQUALIZER_BAND_LEVELS = "equalizer_band_levels"
    private const val KEY_EQUALIZER_GLOBAL_GAIN = "equalizer_global_gain"
    private const val KEY_SONG_LIST_INFO_SHOW_COUNT = "song_list_info_show_count"
    private const val KEY_SONG_LIST_INFO_SHOW_SIZE = "song_list_info_show_size"
    private const val KEY_SONG_LIST_INFO_SHOW_SORT = "song_list_info_show_sort"
    private const val KEY_SONG_LIST_INFO_SHOW_LAST_SCAN = "song_list_info_show_last_scan"
    private const val KEY_SONG_LIST_INFO_SHOW_CUSTOM = "song_list_info_show_custom"
    private const val KEY_SONG_LIST_INFO_CUSTOM_TEXT = "song_list_info_custom_text"
    private const val KEY_PLAYER_INFO_SHOW_FORMAT = "player_info_show_format"
    private const val KEY_PLAYER_INFO_SHOW_SAMPLE_RATE = "player_info_show_sample_rate"
    private const val KEY_PLAYER_INFO_SHOW_BITRATE = "player_info_show_bitrate"
    private const val KEY_PLAYER_INFO_SHOW_DURATION = "player_info_show_duration"
    private const val KEY_PLAYER_INFO_SHOW_CURRENT_TIME = "player_info_show_current_time"
    private const val KEY_PLAYER_INFO_SHOW_CUSTOM = "player_info_show_custom"
    private const val KEY_PLAYER_INFO_CUSTOM_TEXT = "player_info_custom_text"
    private const val KEY_LYRICS_PARSER_VERSION = "lyrics_parser_version"

    /** [equalizerPresetIndex] 为自定义频段时的占位值 */
    const val EQ_PRESET_CUSTOM = -1

    fun themeMode(context: Context): AppThemeMode {
        val p = prefs(context)
        if (!p.contains(KEY_THEME_MODE)) return AppThemeMode.SYSTEM
        return AppThemeMode.fromStorage(p.getString(KEY_THEME_MODE, null))
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.storageValue).apply()
    }

    /** 全应用隐藏状态栏（含主页、设置、播放页）；从屏幕边缘下滑可临时显示 */
    fun hideStatusBar(context: Context): Boolean {
        val p = prefs(context)
        return when {
            p.contains(KEY_HIDE_STATUS_BAR) -> p.getBoolean(KEY_HIDE_STATUS_BAR, false)
            p.contains(KEY_IMMERSIVE_PLAYER_STATUS_BAR) ->
                p.getBoolean(KEY_IMMERSIVE_PLAYER_STATUS_BAR, true)
            else -> false
        }
    }

    fun setHideStatusBar(context: Context, hide: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HIDE_STATUS_BAR, hide)
            .apply()
    }

    @Deprecated("Use hideStatusBar", ReplaceWith("hideStatusBar(context)"))
    fun immersivePlayerStatusBar(context: Context): Boolean = hideStatusBar(context)

    @Deprecated("Software playback removed", ReplaceWith("Unit"))
    fun alacStreamPlayback(context: Context): Boolean = true

    @Deprecated("Software playback removed")
    fun setAlacStreamPlayback(context: Context, enabled: Boolean) = Unit

    /** 最短曲目时长（秒）；0 表示不限制。默认 60。 */
    fun minTrackDurationSec(context: Context): Int =
        prefs(context).getInt(KEY_MIN_TRACK_DURATION_SEC, 60)

    fun setMinTrackDurationSec(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_MIN_TRACK_DURATION_SEC, seconds.coerceAtLeast(0)).apply()
    }

    /** 是否纳入「非 IS_MUSIC 但 MIME 为 audio 类型」的文件（许多 m4a/ALAC 需要此项）。 */
    fun includeNonMusicAudio(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INCLUDE_NON_MUSIC_AUDIO, true)

    fun setIncludeNonMusicAudio(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCLUDE_NON_MUSIC_AUDIO, enabled).apply()
    }

    /** 深度扫描：MediaExtractor + Retriever 分析音质与封面（较慢）。 */
    fun deepMetadataProbe(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEEP_METADATA_PROBE, true)

    fun setDeepMetadataProbe(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEEP_METADATA_PROBE, enabled).apply()
    }

    fun excludedScanDirectories(context: Context): List<String> =
        prefs(context).getString(KEY_EXCLUDED_SCAN_DIRECTORIES, null)
            ?.lineSequence()
            ?.toList()
            ?.let(ExcludedScanDirectories::normalizeAll)
            ?: emptyList()

    fun setExcludedScanDirectories(context: Context, directories: List<String>) {
        val normalized = ExcludedScanDirectories.normalizeAll(directories)
        prefs(context).edit()
            .putString(KEY_EXCLUDED_SCAN_DIRECTORIES, normalized.joinToString("\n"))
            .apply()
    }

    fun libraryTreeUri(context: Context): Uri? =
        prefs(context).getString(KEY_LIBRARY_TREE_URI, null)?.toUri()

    fun libraryFolderLabel(context: Context): String? =
        prefs(context).getString(KEY_LIBRARY_FOLDER_LABEL, null)

    fun setLibraryFolder(context: Context, treeUri: Uri, label: String) {
        prefs(context).edit()
            .putString(KEY_LIBRARY_TREE_URI, treeUri.toString())
            .putString(KEY_LIBRARY_FOLDER_LABEL, label)
            .apply()
    }

    fun clearLibraryFolder(context: Context) {
        prefs(context).edit()
            .remove(KEY_LIBRARY_TREE_URI)
            .remove(KEY_LIBRARY_FOLDER_LABEL)
            .apply()
    }

    fun lastScanSource(context: Context): ScanSource =
        ScanSource.fromStorage(prefs(context).getString(KEY_LAST_SCAN_SOURCE, null))

    fun setLastScanSource(context: Context, source: ScanSource) {
        prefs(context).edit().putString(KEY_LAST_SCAN_SOURCE, source.storageValue).apply()
    }

    fun songSortField(context: Context): SongSortField =
        SongSortField.fromStorage(prefs(context).getString(KEY_SONG_SORT_FIELD, null))

    fun songSortDirection(context: Context): SortDirection =
        SortDirection.fromStorage(prefs(context).getString(KEY_SONG_SORT_DIRECTION, null))

    fun setSongSort(context: Context, field: SongSortField, direction: SortDirection) {
        prefs(context).edit()
            .putString(KEY_SONG_SORT_FIELD, field.storageValue)
            .putString(KEY_SONG_SORT_DIRECTION, direction.storageValue)
            .apply()
    }

    fun playerLowerBackground(context: Context): PlayerLowerBackgroundMode =
        PlayerLowerBackgroundMode.fromStorage(
            prefs(context).getString(KEY_PLAYER_LOWER_BACKGROUND, null),
        )

    fun setPlayerLowerBackground(context: Context, mode: PlayerLowerBackgroundMode) {
        prefs(context).edit().putString(KEY_PLAYER_LOWER_BACKGROUND, mode.storageValue).apply()
    }

    fun miniPlayerStyle(context: Context): MiniPlayerStyle =
        MiniPlayerStyle.fromStorage(prefs(context).getString(KEY_MINI_PLAYER_STYLE, null))

    fun setMiniPlayerStyle(context: Context, style: MiniPlayerStyle) {
        prefs(context).edit().putString(KEY_MINI_PLAYER_STYLE, style.storageValue).apply()
    }

    fun miniPlayerLyricsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MINI_PLAYER_LYRICS_ENABLED, true)

    fun setMiniPlayerLyricsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MINI_PLAYER_LYRICS_ENABLED, enabled).apply()
    }

    fun miniPlayerSwipeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MINI_PLAYER_SWIPE_ENABLED, false)

    fun setMiniPlayerSwipeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MINI_PLAYER_SWIPE_ENABLED, enabled).apply()
    }

    fun miniPlayerLeftSwipeAction(context: Context): MiniPlayerSwipeAction =
        MiniPlayerSwipeAction.fromStorage(
            prefs(context).getString(KEY_MINI_PLAYER_LEFT_SWIPE_ACTION, null),
            MiniPlayerSwipeAction.NEXT,
        )

    fun setMiniPlayerLeftSwipeAction(context: Context, action: MiniPlayerSwipeAction) {
        prefs(context).edit().putString(KEY_MINI_PLAYER_LEFT_SWIPE_ACTION, action.storageValue).apply()
    }

    fun miniPlayerRightSwipeAction(context: Context): MiniPlayerSwipeAction =
        MiniPlayerSwipeAction.fromStorage(
            prefs(context).getString(KEY_MINI_PLAYER_RIGHT_SWIPE_ACTION, null),
            MiniPlayerSwipeAction.PREVIOUS,
        )

    fun setMiniPlayerRightSwipeAction(context: Context, action: MiniPlayerSwipeAction) {
        prefs(context).edit().putString(KEY_MINI_PLAYER_RIGHT_SWIPE_ACTION, action.storageValue).apply()
    }

    fun coverDisplayMode(context: Context): CoverDisplayMode =
        CoverDisplayMode.fromStorage(prefs(context).getString(KEY_COVER_DISPLAY_MODE, null))

    fun setCoverDisplayMode(context: Context, mode: CoverDisplayMode) {
        prefs(context).edit().putString(KEY_COVER_DISPLAY_MODE, mode.storageValue).apply()
    }

    fun playerCoverFlowMode(context: Context): PlayerCoverFlowMode =
        PlayerCoverFlowMode.fromStorage(
            prefs(context).getString(KEY_PLAYER_COVER_FLOW_MODE, null),
        )

    fun setPlayerCoverFlowMode(context: Context, mode: PlayerCoverFlowMode) {
        prefs(context).edit().putString(KEY_PLAYER_COVER_FLOW_MODE, mode.storageValue).apply()
    }

    fun particleCoverTuning(context: Context): ParticleCoverTuning {
        val p = prefs(context)
        val defaults = ParticleCoverTuning()
        return ParticleCoverTuning(
            erosionScale = p.getFloat(KEY_PARTICLE_COVER_EROSION_SCALE, defaults.erosionScale),
            featherScale = p.getFloat(KEY_PARTICLE_COVER_FEATHER_SCALE, defaults.featherScale),
            edgeParticleDensity = p.getFloat(
                KEY_PARTICLE_COVER_EDGE_DENSITY,
                defaults.edgeParticleDensity,
            ),
            edgeParticleAlpha = p.getFloat(
                KEY_PARTICLE_COVER_EDGE_ALPHA,
                defaults.edgeParticleAlpha,
            ),
            edgeTravelScale = p.getFloat(KEY_PARTICLE_COVER_EDGE_TRAVEL, defaults.edgeTravelScale),
            transitionParticleDensity = p.getFloat(
                KEY_PARTICLE_COVER_TRANSITION_DENSITY,
                defaults.transitionParticleDensity,
            ),
        )
    }

    fun setParticleCoverTuning(context: Context, tuning: ParticleCoverTuning) {
        prefs(context).edit()
            .putFloat(KEY_PARTICLE_COVER_EROSION_SCALE, tuning.erosionScale)
            .putFloat(KEY_PARTICLE_COVER_FEATHER_SCALE, tuning.featherScale)
            .putFloat(KEY_PARTICLE_COVER_EDGE_DENSITY, tuning.edgeParticleDensity)
            .putFloat(KEY_PARTICLE_COVER_EDGE_ALPHA, tuning.edgeParticleAlpha)
            .putFloat(KEY_PARTICLE_COVER_EDGE_TRAVEL, tuning.edgeTravelScale)
            .putFloat(KEY_PARTICLE_COVER_TRANSITION_DENSITY, tuning.transitionParticleDensity)
            .apply()
    }

    fun appAccentColor(context: Context): AppAccentColor =
        AppAccentColor.fromStorage(prefs(context).getString(KEY_APP_ACCENT_COLOR, null))

    fun setAppAccentColor(context: Context, accent: AppAccentColor) {
        prefs(context).edit().putString(KEY_APP_ACCENT_COLOR, accent.storageValue).apply()
    }

    fun customAccentColorArgb(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_ACCENT_COLOR, DEFAULT_CUSTOM_ACCENT_COLOR_ARGB)

    fun setCustomAccentColorArgb(context: Context, colorArgb: Int) {
        prefs(context).edit().putInt(KEY_CUSTOM_ACCENT_COLOR, colorArgb).apply()
    }

    fun micaBackgroundPreset(context: Context): MicaPreset =
        MicaPreset.fromStorage(prefs(context).getString(KEY_MICA_BACKGROUND_PRESET, null))

    fun setMicaBackgroundPreset(context: Context, preset: MicaPreset) {
        prefs(context).edit().putString(KEY_MICA_BACKGROUND_PRESET, preset.storageValue).apply()
    }

    fun customMicaStartArgb(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_MICA_START, DEFAULT_CUSTOM_MICA_START_ARGB)

    fun setCustomMicaStartArgb(context: Context, colorArgb: Int) {
        prefs(context).edit().putInt(KEY_CUSTOM_MICA_START, colorArgb).apply()
    }

    fun customMicaEndArgb(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_MICA_END, DEFAULT_CUSTOM_MICA_END_ARGB)

    fun setCustomMicaEndArgb(context: Context, colorArgb: Int) {
        prefs(context).edit().putInt(KEY_CUSTOM_MICA_END, colorArgb).apply()
    }

    fun customMicaSingleColor(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CUSTOM_MICA_SINGLE_COLOR, false)

    fun setCustomMicaSingleColor(context: Context, singleColor: Boolean) {
        prefs(context).edit().putBoolean(KEY_CUSTOM_MICA_SINGLE_COLOR, singleColor).apply()
    }

    /**
     * 封面底边进度：进度条叠在专辑封面下缘，仅显示已播放段。
     * 仅在播放页背景为「主题色」或「封面模糊」时生效。
     */
    fun coverEdgeProgress(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COVER_EDGE_PROGRESS, false)

    fun setCoverEdgeProgress(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COVER_EDGE_PROGRESS, enabled).apply()
    }

    fun keepScreenOnWhenPlaying(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON_WHEN_PLAYING, false)

    fun setKeepScreenOnWhenPlaying(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON_WHEN_PLAYING, enabled).apply()
    }

    /** 播放页下半屏沉浸：仅居中歌名与歌手，点击切换播放/暂停，长按可退出。 */
    fun playerImmersiveLower(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PLAYER_IMMERSIVE_LOWER, false)

    fun setPlayerImmersiveLower(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PLAYER_IMMERSIVE_LOWER, enabled).apply()
    }

    /** 是否在播放页/歌词页将含细空格等的行拆成双语两行展示。 */
    fun stripSongTitleParentheses(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STRIP_SONG_TITLE_PARENTHESES, false)

    fun setStripSongTitleParentheses(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRIP_SONG_TITLE_PARENTHESES, enabled).apply()
    }

    fun lyricSplitEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LYRIC_SPLIT_ENABLED, true)

    fun setLyricSplitEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LYRIC_SPLIT_ENABLED, enabled).apply()
    }

    fun lyricsBilingualDisplayMode(context: Context): LyricsBilingualDisplayMode =
        LyricsBilingualDisplayMode.fromStorage(
            prefs(context).getString(KEY_LYRICS_BILINGUAL_DISPLAY_MODE, null),
        )

    fun setLyricsBilingualDisplayMode(context: Context, mode: LyricsBilingualDisplayMode) {
        prefs(context).edit().putString(KEY_LYRICS_BILINGUAL_DISPLAY_MODE, mode.storageValue).apply()
    }

    /** Whether line-timed lyrics without word cues use karaoke-style text fill. */
    fun lyricLineFillEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LYRIC_LINE_FILL_ENABLED, false)

    fun setLyricLineFillEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LYRIC_LINE_FILL_ENABLED, enabled).apply()
    }

    fun lyricsPageAlignment(context: Context): LyricsPageAlignment =
        LyricsPageAlignment.fromStorage(prefs(context).getString(KEY_LYRICS_PAGE_ALIGNMENT, null))

    fun setLyricsPageAlignment(context: Context, alignment: LyricsPageAlignment) {
        prefs(context).edit().putString(KEY_LYRICS_PAGE_ALIGNMENT, alignment.storageValue).apply()
    }

    fun lyricsPageFontSizeSp(context: Context): Int {
        return lyricsPageFontSizeSp(context, KEY_LYRICS_PAGE_FONT_SIZE, DEFAULT_LYRICS_PAGE_FONT_SIZE_SP)
    }

    fun setLyricsPageFontSizeSp(context: Context, fontSizeSp: Int) {
        prefs(context).edit()
            .putInt(
                KEY_LYRICS_PAGE_FONT_SIZE,
                fontSizeSp.coerceIn(MIN_LYRICS_PAGE_FONT_SIZE_SP, MAX_LYRICS_PAGE_FONT_SIZE_SP),
            )
            .apply()
    }

    fun lyricsPageTranslationFontSizeSp(context: Context): Int =
        lyricsPageFontSizeSp(context, KEY_LYRICS_PAGE_TRANSLATION_FONT_SIZE, lyricsPageFontSizeSp(context))

    fun setLyricsPageTranslationFontSizeSp(context: Context, fontSizeSp: Int) {
        prefs(context).edit()
            .putInt(
                KEY_LYRICS_PAGE_TRANSLATION_FONT_SIZE,
                fontSizeSp.coerceIn(MIN_LYRICS_PAGE_FONT_SIZE_SP, MAX_LYRICS_PAGE_FONT_SIZE_SP),
            )
            .apply()
    }

    fun lyricsPageImmersive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LYRICS_PAGE_IMMERSIVE, false)

    fun setLyricsPageImmersive(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LYRICS_PAGE_IMMERSIVE, enabled).apply()
    }

    fun notificationLyricsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION_LYRICS_ENABLED, true)

    fun setNotificationLyricsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_LYRICS_ENABLED, enabled).apply()
    }

    internal fun lyricsParserVersion(context: Context): Int =
        prefs(context).getInt(KEY_LYRICS_PARSER_VERSION, 0)

    internal fun setLyricsParserVersion(context: Context, version: Int) {
        prefs(context).edit().putInt(KEY_LYRICS_PARSER_VERSION, version.coerceAtLeast(0)).apply()
    }

    private fun lyricsPageFontSizeSp(context: Context, key: String, defaultValue: Int): Int =
        when (val stored = prefs(context).all[key]) {
            is Int -> stored
            is String -> when (stored) {
                "small" -> 17
                "large" -> 22
                "extra_large" -> 25
                else -> defaultValue
            }
            else -> defaultValue
        }.coerceIn(MIN_LYRICS_PAGE_FONT_SIZE_SP, MAX_LYRICS_PAGE_FONT_SIZE_SP)

    fun spectrumEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPECTRUM_ENABLED, false)

    fun setSpectrumEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPECTRUM_ENABLED, enabled).apply()
    }

    fun equalizerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EQUALIZER_ENABLED, false)

    fun setEqualizerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EQUALIZER_ENABLED, enabled).apply()
    }

    fun equalizerPresetIndex(context: Context): Int =
        prefs(context).getInt(KEY_EQUALIZER_PRESET, 0)

    fun setEqualizerPresetIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_EQUALIZER_PRESET, index).apply()
    }

    fun equalizerBandLevels(context: Context): List<Short> =
        prefs(context).getString(KEY_EQUALIZER_BAND_LEVELS, null)
            ?.split(',')
            ?.mapNotNull { it.toShortOrNull() }
            ?: emptyList()

    fun setEqualizerBandLevels(context: Context, levels: List<Short>) {
        prefs(context).edit()
            .putString(KEY_EQUALIZER_BAND_LEVELS, levels.joinToString(","))
            .apply()
    }

    fun equalizerGlobalGainMillibels(context: Context): Short =
        prefs(context)
            .getInt(KEY_EQUALIZER_GLOBAL_GAIN, EqBandConstants.DEFAULT_GLOBAL_GAIN_MILLIBELS.toInt())
            .coerceIn(
                EqBandConstants.MIN_GLOBAL_GAIN_MILLIBELS.toInt(),
                EqBandConstants.MAX_GLOBAL_GAIN_MILLIBELS.toInt(),
            )
            .toShort()

    fun setEqualizerGlobalGainMillibels(context: Context, gainMillibels: Short) {
        val clamped = gainMillibels.coerceIn(
            EqBandConstants.MIN_GLOBAL_GAIN_MILLIBELS,
            EqBandConstants.MAX_GLOBAL_GAIN_MILLIBELS,
        )
        prefs(context).edit()
            .putInt(KEY_EQUALIZER_GLOBAL_GAIN, clamped.toInt())
            .apply()
    }

    fun scanOptions(context: Context): ScanOptions = ScanOptions(
        minDurationMs = minTrackDurationSec(context).coerceAtLeast(0) * 1000L,
        includeNonMusicByMime = includeNonMusicAudio(context),
        deepMetadataProbe = deepMetadataProbe(context),
        excludedDirectories = excludedScanDirectories(context),
    )

    fun songListInfoVisibility(context: Context): SongListInfoVisibility {
        val p = prefs(context)
        return SongListInfoVisibility(
            showSongCount = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_COUNT, true),
            showLibrarySize = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_SIZE, true),
            showSortOrder = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_SORT, true),
            showLastScanTime = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_LAST_SCAN, true),
            showCustomText = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_CUSTOM, false),
            customText = p.getString(KEY_SONG_LIST_INFO_CUSTOM_TEXT, "") ?: "",
        )
    }

    fun setSongListInfoVisibility(context: Context, visibility: SongListInfoVisibility) {
        prefs(context).edit()
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_COUNT, visibility.showSongCount)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_SIZE, visibility.showLibrarySize)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_SORT, visibility.showSortOrder)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_LAST_SCAN, visibility.showLastScanTime)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_CUSTOM, visibility.showCustomText)
            .putString(KEY_SONG_LIST_INFO_CUSTOM_TEXT, visibility.customText)
            .apply()
    }

    fun playerInfoVisibility(context: Context): PlayerInfoVisibility {
        val p = prefs(context)
        return PlayerInfoVisibility(
            showFormat = p.getBoolean(KEY_PLAYER_INFO_SHOW_FORMAT, true),
            showSampleRate = p.getBoolean(KEY_PLAYER_INFO_SHOW_SAMPLE_RATE, true),
            showBitrate = p.getBoolean(KEY_PLAYER_INFO_SHOW_BITRATE, true),
            showCurrentTime = when {
                p.contains(KEY_PLAYER_INFO_SHOW_CURRENT_TIME) ->
                    p.getBoolean(KEY_PLAYER_INFO_SHOW_CURRENT_TIME, false)
                p.contains(KEY_PLAYER_INFO_SHOW_DURATION) ->
                    p.getBoolean(KEY_PLAYER_INFO_SHOW_DURATION, false)
                else -> false
            },
            showCustomText = p.getBoolean(KEY_PLAYER_INFO_SHOW_CUSTOM, false),
            customText = p.getString(KEY_PLAYER_INFO_CUSTOM_TEXT, "") ?: "",
        )
    }

    fun setPlayerInfoVisibility(context: Context, visibility: PlayerInfoVisibility) {
        prefs(context).edit()
            .putBoolean(KEY_PLAYER_INFO_SHOW_FORMAT, visibility.showFormat)
            .putBoolean(KEY_PLAYER_INFO_SHOW_SAMPLE_RATE, visibility.showSampleRate)
            .putBoolean(KEY_PLAYER_INFO_SHOW_BITRATE, visibility.showBitrate)
            .putBoolean(KEY_PLAYER_INFO_SHOW_CURRENT_TIME, visibility.showCurrentTime)
            .putBoolean(KEY_PLAYER_INFO_SHOW_CUSTOM, visibility.showCustomText)
            .putString(KEY_PLAYER_INFO_CUSTOM_TEXT, visibility.customText)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val DEFAULT_CUSTOM_ACCENT_COLOR_ARGB = 0xFF8B7AFF.toInt()
    private const val DEFAULT_CUSTOM_MICA_START_ARGB = CustomMicaBackground.DEFAULT_START_ARGB
    private const val DEFAULT_CUSTOM_MICA_END_ARGB = CustomMicaBackground.DEFAULT_END_ARGB
}
