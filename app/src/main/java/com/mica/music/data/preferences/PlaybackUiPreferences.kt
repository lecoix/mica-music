package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.SongListInfoVisibility

/** 播放页、迷你栏、封面与列表信息相关 UI 偏好。 */
object PlaybackUiPreferences {
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
    private const val KEY_COVER_EDGE_PROGRESS = "cover_edge_progress"
    private const val KEY_KEEP_SCREEN_ON_WHEN_PLAYING = "keep_screen_on_when_playing"
    private const val KEY_PLAYER_IMMERSIVE_LOWER = "player_immersive_lower"
    private const val KEY_STRIP_SONG_TITLE_PARENTHESES = "strip_song_title_parentheses"
    private const val KEY_SPECTRUM_ENABLED = "spectrum_enabled"
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
    private const val KEY_AUDIO_FOCUS_ENABLED = "audio_focus_enabled"

    fun playerLowerBackground(context: Context): PlayerLowerBackgroundMode =
        PlayerLowerBackgroundMode.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_PLAYER_LOWER_BACKGROUND, null),
        )

    fun setPlayerLowerBackground(context: Context, mode: PlayerLowerBackgroundMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_PLAYER_LOWER_BACKGROUND, mode.storageValue)
            .apply()
    }

    fun miniPlayerStyle(context: Context): MiniPlayerStyle =
        MiniPlayerStyle.fromStorage(MicaSettingsStore.prefs(context).getString(KEY_MINI_PLAYER_STYLE, null))

    fun setMiniPlayerStyle(context: Context, style: MiniPlayerStyle) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_MINI_PLAYER_STYLE, style.storageValue)
            .apply()
    }

    fun miniPlayerLyricsEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_MINI_PLAYER_LYRICS_ENABLED, true)

    fun setMiniPlayerLyricsEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_MINI_PLAYER_LYRICS_ENABLED, enabled)
            .apply()
    }

    fun miniPlayerSwipeEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_MINI_PLAYER_SWIPE_ENABLED, false)

    fun setMiniPlayerSwipeEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_MINI_PLAYER_SWIPE_ENABLED, enabled)
            .apply()
    }

    fun miniPlayerLeftSwipeAction(context: Context): MiniPlayerSwipeAction =
        MiniPlayerSwipeAction.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_MINI_PLAYER_LEFT_SWIPE_ACTION, null),
            MiniPlayerSwipeAction.NEXT,
        )

    fun setMiniPlayerLeftSwipeAction(context: Context, action: MiniPlayerSwipeAction) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_MINI_PLAYER_LEFT_SWIPE_ACTION, action.storageValue)
            .apply()
    }

    fun miniPlayerRightSwipeAction(context: Context): MiniPlayerSwipeAction =
        MiniPlayerSwipeAction.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_MINI_PLAYER_RIGHT_SWIPE_ACTION, null),
            MiniPlayerSwipeAction.PREVIOUS,
        )

    fun setMiniPlayerRightSwipeAction(context: Context, action: MiniPlayerSwipeAction) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_MINI_PLAYER_RIGHT_SWIPE_ACTION, action.storageValue)
            .apply()
    }

    fun coverDisplayMode(context: Context): CoverDisplayMode =
        CoverDisplayMode.fromStorage(MicaSettingsStore.prefs(context).getString(KEY_COVER_DISPLAY_MODE, null))

    fun setCoverDisplayMode(context: Context, mode: CoverDisplayMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_COVER_DISPLAY_MODE, mode.storageValue)
            .apply()
    }

    fun playerCoverFlowMode(context: Context): PlayerCoverFlowMode =
        PlayerCoverFlowMode.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_PLAYER_COVER_FLOW_MODE, null),
        )

    fun setPlayerCoverFlowMode(context: Context, mode: PlayerCoverFlowMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_PLAYER_COVER_FLOW_MODE, mode.storageValue)
            .apply()
    }

    fun particleCoverTuning(context: Context): ParticleCoverTuning {
        val p = MicaSettingsStore.prefs(context)
        val defaults = ParticleCoverTuning()
        return ParticleCoverTuning(
            erosionScale = p.getFloat(KEY_PARTICLE_COVER_EROSION_SCALE, defaults.erosionScale),
            featherScale = p.getFloat(KEY_PARTICLE_COVER_FEATHER_SCALE, defaults.featherScale),
            edgeParticleDensity = p.getFloat(KEY_PARTICLE_COVER_EDGE_DENSITY, defaults.edgeParticleDensity),
            edgeParticleAlpha = p.getFloat(KEY_PARTICLE_COVER_EDGE_ALPHA, defaults.edgeParticleAlpha),
            edgeTravelScale = p.getFloat(KEY_PARTICLE_COVER_EDGE_TRAVEL, defaults.edgeTravelScale),
            transitionParticleDensity = p.getFloat(
                KEY_PARTICLE_COVER_TRANSITION_DENSITY,
                defaults.transitionParticleDensity,
            ),
        )
    }

    fun setParticleCoverTuning(context: Context, tuning: ParticleCoverTuning) {
        MicaSettingsStore.prefs(context).edit()
            .putFloat(KEY_PARTICLE_COVER_EROSION_SCALE, tuning.erosionScale)
            .putFloat(KEY_PARTICLE_COVER_FEATHER_SCALE, tuning.featherScale)
            .putFloat(KEY_PARTICLE_COVER_EDGE_DENSITY, tuning.edgeParticleDensity)
            .putFloat(KEY_PARTICLE_COVER_EDGE_ALPHA, tuning.edgeParticleAlpha)
            .putFloat(KEY_PARTICLE_COVER_EDGE_TRAVEL, tuning.edgeTravelScale)
            .putFloat(KEY_PARTICLE_COVER_TRANSITION_DENSITY, tuning.transitionParticleDensity)
            .apply()
    }

    fun coverEdgeProgress(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_COVER_EDGE_PROGRESS, false)

    fun setCoverEdgeProgress(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_COVER_EDGE_PROGRESS, enabled)
            .apply()
    }

    fun keepScreenOnWhenPlaying(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_KEEP_SCREEN_ON_WHEN_PLAYING, false)

    fun setKeepScreenOnWhenPlaying(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_KEEP_SCREEN_ON_WHEN_PLAYING, enabled)
            .apply()
    }

    fun playerImmersiveLower(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_PLAYER_IMMERSIVE_LOWER, false)

    fun setPlayerImmersiveLower(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_PLAYER_IMMERSIVE_LOWER, enabled)
            .apply()
    }

    fun stripSongTitleParentheses(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_STRIP_SONG_TITLE_PARENTHESES, false)

    fun setStripSongTitleParentheses(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_STRIP_SONG_TITLE_PARENTHESES, enabled)
            .apply()
    }

    fun spectrumEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_SPECTRUM_ENABLED, false)

    fun setSpectrumEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_SPECTRUM_ENABLED, enabled)
            .apply()
    }

    fun audioFocusEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_AUDIO_FOCUS_ENABLED, true)

    fun setAudioFocusEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_AUDIO_FOCUS_ENABLED, enabled)
            .apply()
    }

    /** 与 [com.mica.music.media.MicaSpectrumAnalyzer] 及 [com.mica.music.data.AppUiSettings] 一致。 */
    fun spectrumTapEnabled(context: Context): Boolean =
        spectrumEnabled(context) ||
            miniPlayerStyle(context) == MiniPlayerStyle.AUDIOPHILE ||
            playerCoverFlowMode(context).usesPhotoStack

    fun songListInfoVisibility(context: Context): SongListInfoVisibility {
        val p = MicaSettingsStore.prefs(context)
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
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_COUNT, visibility.showSongCount)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_SIZE, visibility.showLibrarySize)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_SORT, visibility.showSortOrder)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_LAST_SCAN, visibility.showLastScanTime)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_CUSTOM, visibility.showCustomText)
            .putString(KEY_SONG_LIST_INFO_CUSTOM_TEXT, visibility.customText)
            .apply()
    }

    fun playerInfoVisibility(context: Context): PlayerInfoVisibility {
        val p = MicaSettingsStore.prefs(context)
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
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_PLAYER_INFO_SHOW_FORMAT, visibility.showFormat)
            .putBoolean(KEY_PLAYER_INFO_SHOW_SAMPLE_RATE, visibility.showSampleRate)
            .putBoolean(KEY_PLAYER_INFO_SHOW_BITRATE, visibility.showBitrate)
            .putBoolean(KEY_PLAYER_INFO_SHOW_CURRENT_TIME, visibility.showCurrentTime)
            .putBoolean(KEY_PLAYER_INFO_SHOW_CUSTOM, visibility.showCustomText)
            .putString(KEY_PLAYER_INFO_CUSTOM_TEXT, visibility.customText)
            .apply()
    }
}
