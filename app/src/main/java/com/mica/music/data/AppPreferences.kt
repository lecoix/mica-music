package com.mica.music.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.mica.music.data.scanner.ScanOptions
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
    private const val KEY_ALAC_STREAM_PLAYBACK = "alac_stream_playback"
    private const val KEY_MIN_TRACK_DURATION_SEC = "min_track_duration_sec"
    private const val KEY_INCLUDE_NON_MUSIC_AUDIO = "include_non_music_audio"
    private const val KEY_DEEP_METADATA_PROBE = "deep_metadata_probe"
    private const val KEY_LIBRARY_TREE_URI = "library_tree_uri"
    private const val KEY_LIBRARY_FOLDER_LABEL = "library_folder_label"
    private const val KEY_LAST_SCAN_SOURCE = "last_scan_source"
    private const val KEY_SONG_SORT_FIELD = "song_sort_field"
    private const val KEY_SONG_SORT_DIRECTION = "song_sort_direction"
    private const val KEY_PLAYER_LOWER_BACKGROUND = "player_lower_background"
    private const val KEY_MINI_PLAYER_STYLE = "mini_player_style"
    private const val KEY_COVER_DISPLAY_MODE = "cover_display_mode"
    private const val KEY_PLAYER_COVER_FLOW_MODE = "player_cover_flow_mode"
    private const val KEY_APP_ACCENT_COLOR = "app_accent_color"
    private const val KEY_MICA_BACKGROUND_PRESET = "mica_background_preset"
    private const val KEY_COVER_EDGE_PROGRESS = "cover_edge_progress"
    private const val KEY_PLAYER_IMMERSIVE_LOWER = "player_immersive_lower"
    private const val KEY_LYRIC_SPLIT_ENABLED = "lyric_split_enabled"
    private const val KEY_SPECTRUM_ENABLED = "spectrum_enabled"
    private const val KEY_EQUALIZER_ENABLED = "equalizer_enabled"
    private const val KEY_EQUALIZER_PRESET = "equalizer_preset"
    private const val KEY_EQUALIZER_BAND_LEVELS = "equalizer_band_levels"

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

    /**
     * 已统一为 FFmpeg → AudioTrack；保留键兼容旧设置，始终视为开启。
     */
    fun alacStreamPlayback(context: Context): Boolean = true

    fun setAlacStreamPlayback(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALAC_STREAM_PLAYBACK, enabled).apply()
    }

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

    fun appAccentColor(context: Context): AppAccentColor =
        AppAccentColor.fromStorage(prefs(context).getString(KEY_APP_ACCENT_COLOR, null))

    fun setAppAccentColor(context: Context, accent: AppAccentColor) {
        prefs(context).edit().putString(KEY_APP_ACCENT_COLOR, accent.storageValue).apply()
    }

    fun micaBackgroundPreset(context: Context): MicaPreset =
        MicaPreset.fromStorage(prefs(context).getString(KEY_MICA_BACKGROUND_PRESET, null))

    fun setMicaBackgroundPreset(context: Context, preset: MicaPreset) {
        prefs(context).edit().putString(KEY_MICA_BACKGROUND_PRESET, preset.storageValue).apply()
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

    /** 播放页下半屏沉浸：仅居中歌名与歌手，点击切换播放/暂停，长按可退出。 */
    fun playerImmersiveLower(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PLAYER_IMMERSIVE_LOWER, false)

    fun setPlayerImmersiveLower(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PLAYER_IMMERSIVE_LOWER, enabled).apply()
    }

    /** 是否在播放页/歌词页将含细空格等的行拆成双语两行展示。 */
    fun lyricSplitEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LYRIC_SPLIT_ENABLED, true)

    fun setLyricSplitEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LYRIC_SPLIT_ENABLED, enabled).apply()
    }

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

    fun scanOptions(context: Context): ScanOptions = ScanOptions(
        minDurationMs = minTrackDurationSec(context).coerceAtLeast(0) * 1000L,
        includeNonMusicByMime = includeNonMusicAudio(context),
        deepMetadataProbe = deepMetadataProbe(context),
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
