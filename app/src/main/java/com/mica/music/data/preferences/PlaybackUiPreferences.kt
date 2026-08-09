package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.data.BrowseListInfoVisibility
import com.mica.music.data.CompactLyricsLineMode
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.HiResBadgeStyle
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.PlayerLowerComponent
import com.mica.music.data.PlayerLowerElementOffset
import com.mica.music.data.PlayerLowerLayoutConfig
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.SongTrailingInfo

/** 播放页、迷你栏、封面与列表信息相关 UI 偏好。 */
object PlaybackUiPreferences {
    private const val KEY_PLAYER_LOWER_BACKGROUND = "player_lower_background"
    internal const val KEY_MINI_PLAYER_STYLE = "mini_player_style"
    private const val KEY_MINI_PLAYER_LYRICS_ENABLED = "mini_player_lyrics_enabled"
    private const val KEY_MINI_PLAYER_WORD_LYRICS_ENABLED = "mini_player_word_lyrics_enabled"
    private const val KEY_MINI_PLAYER_SWIPE_ENABLED = "mini_player_swipe_enabled"
    private const val KEY_MINI_PLAYER_LEFT_SWIPE_ACTION = "mini_player_left_swipe_action"
    private const val KEY_MINI_PLAYER_RIGHT_SWIPE_ACTION = "mini_player_right_swipe_action"
    private const val KEY_COVER_DISPLAY_MODE = "cover_display_mode"
    internal const val KEY_PLAYER_COVER_FLOW_MODE = "player_cover_flow_mode"
    private const val KEY_VIDEO_ALBUM_COVER_ENABLED = "video_album_cover_enabled"
    private const val KEY_CUSTOM_STANDARD_COVER_TAP_PLAY_PAUSE = "custom_standard_cover_tap_play_pause"
    private const val KEY_CUSTOM_PLAYER_LOWER_ORDER = "custom_player_lower_order"
    private const val KEY_CUSTOM_PLAYER_LOWER_HIDDEN = "custom_player_lower_hidden"
    private const val KEY_CUSTOM_PLAYER_LOWER_SIZES = "custom_player_lower_sizes"
    private const val KEY_CUSTOM_PLAYER_LOWER_SPACING = "custom_player_lower_spacing"
    private const val KEY_CUSTOM_PLAYER_LOWER_TOP_PADDING = "custom_player_lower_top_padding"
    private const val KEY_CUSTOM_PLAYER_LOWER_BOTTOM_PADDING = "custom_player_lower_bottom_padding"
    private const val KEY_CUSTOM_PLAYER_LOWER_LYRICS_LINES = "custom_player_lower_lyrics_lines"
    private const val KEY_CUSTOM_PLAYER_LOWER_OFFSETS = "custom_player_lower_offsets"
    private const val KEY_CUSTOM_PLAYER_LOWER_FREEFORM = "custom_player_lower_freeform"
    private const val KEY_PARTICLE_COVER_EROSION_SCALE = "particle_cover_erosion_scale"
    private const val KEY_PARTICLE_COVER_FEATHER_SCALE = "particle_cover_feather_scale"
    private const val KEY_PARTICLE_COVER_EDGE_DENSITY = "particle_cover_edge_density"
    private const val KEY_PARTICLE_COVER_EDGE_ALPHA = "particle_cover_edge_alpha"
    private const val KEY_PARTICLE_COVER_EDGE_TRAVEL = "particle_cover_edge_travel"
    private const val KEY_PARTICLE_COVER_TRANSITION_DENSITY = "particle_cover_transition_density"
    private const val KEY_COVER_EDGE_PROGRESS = "cover_edge_progress"
    private const val KEY_KEEP_SCREEN_ON_WHEN_PLAYING = "keep_screen_on_when_playing"
    private const val KEY_PLAYER_IMMERSIVE_LOWER = "player_immersive_lower"
    private const val KEY_COMPACT_LYRICS_LINE_MODE = "compact_lyrics_line_mode"
    private const val KEY_STRIP_SONG_TITLE_PARENTHESES = "strip_song_title_parentheses"
    internal const val KEY_SPECTRUM_ENABLED = "spectrum_enabled"
    private const val KEY_SONG_LIST_INFO_SHOW_COUNT = "song_list_info_show_count"
    private const val KEY_SONG_LIST_INFO_SHOW_SONG_ARTIST = "song_list_info_show_song_artist"
    private const val KEY_SONG_LIST_INFO_SHOW_SONG_ALBUM = "song_list_info_show_song_album"
    private const val KEY_SONG_LIST_INFO_SHOW_SONG_PLAY_COUNT = "song_list_info_show_song_play_count"
    private const val KEY_SONG_LIST_INFO_SHOW_SONG_DURATION = "song_list_info_show_song_duration"
    private const val KEY_SONG_LIST_INFO_SHOW_SIZE = "song_list_info_show_size"
    private const val KEY_SONG_LIST_INFO_SHOW_SORT = "song_list_info_show_sort"
    private const val KEY_SONG_LIST_INFO_SHOW_LAST_SCAN = "song_list_info_show_last_scan"
    private const val KEY_SONG_LIST_INFO_SHOW_CUSTOM = "song_list_info_show_custom"
    private const val KEY_SONG_LIST_INFO_CUSTOM_TEXT = "song_list_info_custom_text"
    private const val KEY_SONG_LIST_TRAILING_INFO = "song_list_trailing_info"
    private const val KEY_ARTIST_INFO_SHOW_COUNT = "artist_info_show_count"
    private const val KEY_ARTIST_INFO_SHOW_SORT = "artist_info_show_sort"
    private const val KEY_ARTIST_INFO_SHOW_GRID = "artist_info_show_grid"
    private const val KEY_ARTIST_INFO_SHOW_LAST_SCAN = "artist_info_show_last_scan"
    private const val KEY_ARTIST_INFO_SHOW_CUSTOM = "artist_info_show_custom"
    private const val KEY_ARTIST_INFO_CUSTOM_TEXT = "artist_info_custom_text"
    private const val KEY_ALBUM_INFO_SHOW_COUNT = "album_info_show_count"
    private const val KEY_ALBUM_INFO_SHOW_SORT = "album_info_show_sort"
    private const val KEY_ALBUM_INFO_SHOW_GRID = "album_info_show_grid"
    private const val KEY_ALBUM_INFO_SHOW_LAST_SCAN = "album_info_show_last_scan"
    private const val KEY_ALBUM_INFO_SHOW_CUSTOM = "album_info_show_custom"
    private const val KEY_ALBUM_INFO_CUSTOM_TEXT = "album_info_custom_text"
    private const val KEY_PLAYER_INFO_SHOW_FORMAT = "player_info_show_format"
    private const val KEY_PLAYER_INFO_SHOW_SAMPLE_RATE = "player_info_show_sample_rate"
    private const val KEY_PLAYER_INFO_SHOW_BITRATE = "player_info_show_bitrate"
    private const val KEY_PLAYER_INFO_SHOW_DURATION = "player_info_show_duration"
    private const val KEY_PLAYER_INFO_SHOW_PLAYBACK_SPEED = "player_info_show_playback_speed"
    private const val KEY_PLAYER_INFO_SHOW_PLAYBACK_PITCH = "player_info_show_playback_pitch"
    private const val KEY_PLAYER_INFO_SHOW_CURRENT_TIME = "player_info_show_current_time"
    private const val KEY_PLAYER_INFO_SHOW_CUSTOM = "player_info_show_custom"
    private const val KEY_PLAYER_INFO_CUSTOM_TEXT = "player_info_custom_text"
    private const val KEY_HI_RES_BADGE_STYLE = "hi_res_badge_style"
    private const val KEY_HI_RES_BADGE_CUSTOM_IMAGE_PATH = "hi_res_badge_custom_image_path"
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

    fun miniPlayerWordLyricsEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_MINI_PLAYER_WORD_LYRICS_ENABLED, false)

    fun setMiniPlayerWordLyricsEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_MINI_PLAYER_WORD_LYRICS_ENABLED, enabled)
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

    fun videoAlbumCoverEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_VIDEO_ALBUM_COVER_ENABLED, false)

    fun setVideoAlbumCoverEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_VIDEO_ALBUM_COVER_ENABLED, enabled)
            .apply()
    }

    fun customStandardCoverTapPlayPause(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_CUSTOM_STANDARD_COVER_TAP_PLAY_PAUSE, false)

    fun setCustomStandardCoverTapPlayPause(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_CUSTOM_STANDARD_COVER_TAP_PLAY_PAUSE, enabled)
            .apply()
    }

    fun customPlayerLowerLayout(context: Context): PlayerLowerLayoutConfig {
        val prefs = MicaSettingsStore.prefs(context)
        val storedOrder = prefs.getString(KEY_CUSTOM_PLAYER_LOWER_ORDER, null)
            ?.split(',')
            ?.mapNotNull(PlayerLowerComponent::fromStorage)
            .orEmpty()
        val order = when {
            storedOrder.isEmpty() -> PlayerLowerLayoutConfig.Default.order
            PlayerLowerComponent.COVER !in storedOrder -> listOf(PlayerLowerComponent.COVER) + storedOrder
            else -> storedOrder
        }
        val hidden = prefs.getStringSet(KEY_CUSTOM_PLAYER_LOWER_HIDDEN, emptySet())
            .orEmpty()
            .mapNotNull(PlayerLowerComponent::fromStorage)
            .toSet()
        val scalePercents = prefs.getString(KEY_CUSTOM_PLAYER_LOWER_SIZES, null)
            ?.split(',')
            ?.mapNotNull { encoded ->
                val parts = encoded.split(':', limit = 2)
                val component = parts.getOrNull(0)?.let(PlayerLowerComponent::fromStorage)
                val percent = parts.getOrNull(1)?.let(::decodePlayerLowerScalePercent)
                if (component != null && percent != null) component to percent else null
            }
            ?.toMap()
            .orEmpty()
        val elementOffsets = prefs.getString(KEY_CUSTOM_PLAYER_LOWER_OFFSETS, null)
            ?.split(',')
            ?.mapNotNull { encoded ->
                val parts = encoded.split(':', limit = 3)
                val component = parts.getOrNull(0)?.let(PlayerLowerComponent::fromStorage)
                val x = parts.getOrNull(1)?.toIntOrNull()
                val y = parts.getOrNull(2)?.toIntOrNull()
                if (component != null && x != null && y != null) {
                    component to PlayerLowerElementOffset(x, y)
                } else {
                    null
                }
            }
            ?.toMap()
            .orEmpty()
        return PlayerLowerLayoutConfig(
            order = order,
            hidden = hidden,
            scalePercents = scalePercents,
            spacingDp = prefs.getInt(
                KEY_CUSTOM_PLAYER_LOWER_SPACING,
                PlayerLowerLayoutConfig.DEFAULT_SPACING_DP,
            ),
            topPaddingDp = prefs.getInt(
                KEY_CUSTOM_PLAYER_LOWER_TOP_PADDING,
                PlayerLowerLayoutConfig.DEFAULT_BOUNDARY_PADDING_DP,
            ),
            bottomPaddingDp = prefs.getInt(
                KEY_CUSTOM_PLAYER_LOWER_BOTTOM_PADDING,
                PlayerLowerLayoutConfig.DEFAULT_BOUNDARY_PADDING_DP,
            ),
            lyricsLineCount = prefs.getInt(
                KEY_CUSTOM_PLAYER_LOWER_LYRICS_LINES,
                PlayerLowerLayoutConfig.DEFAULT_LYRICS_LINE_COUNT,
            ),
            elementOffsets = elementOffsets,
            freeformEnabled = prefs.getBoolean(KEY_CUSTOM_PLAYER_LOWER_FREEFORM, false),
        ).normalized()
    }

    fun setCustomPlayerLowerLayout(context: Context, config: PlayerLowerLayoutConfig) {
        val normalized = config.normalized()
        MicaSettingsStore.prefs(context).edit()
            .putString(
                KEY_CUSTOM_PLAYER_LOWER_ORDER,
                normalized.order.joinToString(",") { it.storageValue },
            )
            .putStringSet(
                KEY_CUSTOM_PLAYER_LOWER_HIDDEN,
                normalized.hidden.mapTo(mutableSetOf()) { it.storageValue },
            )
            .putString(
                KEY_CUSTOM_PLAYER_LOWER_SIZES,
                normalized.scalePercents.entries.joinToString(",") { (component, percent) ->
                    "${component.storageValue}:$percent"
                },
            )
            .putInt(KEY_CUSTOM_PLAYER_LOWER_SPACING, normalized.spacingDp)
            .putInt(KEY_CUSTOM_PLAYER_LOWER_TOP_PADDING, normalized.topPaddingDp)
            .putInt(KEY_CUSTOM_PLAYER_LOWER_BOTTOM_PADDING, normalized.bottomPaddingDp)
            .putInt(KEY_CUSTOM_PLAYER_LOWER_LYRICS_LINES, normalized.lyricsLineCount)
            .putString(
                KEY_CUSTOM_PLAYER_LOWER_OFFSETS,
                normalized.elementOffsets.entries.joinToString(",") { (component, offset) ->
                    "${component.storageValue}:${offset.xPermille}:${offset.yPermille}"
                },
            )
            .putBoolean(KEY_CUSTOM_PLAYER_LOWER_FREEFORM, normalized.freeformEnabled)
            .apply()
    }

    private fun decodePlayerLowerScalePercent(value: String): Int? = when (value) {
        "small" -> 85
        "medium" -> 100
        "large" -> 115
        else -> value.toIntOrNull()
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

    fun compactLyricsLineMode(context: Context): CompactLyricsLineMode =
        CompactLyricsLineMode.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_COMPACT_LYRICS_LINE_MODE, null),
        )

    fun setCompactLyricsLineMode(context: Context, mode: CompactLyricsLineMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_COMPACT_LYRICS_LINE_MODE, mode.storageValue)
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

    /** 频谱 PCM tap 的派生资格，由媒体侧 owner 应用到 analyzer。 */
    fun spectrumTapEnabled(context: Context): Boolean =
        spectrumEnabled(context) ||
            miniPlayerStyle(context) == MiniPlayerStyle.AUDIOPHILE ||
            playerCoverFlowMode(context).usesPhotoStack

    fun songListInfoVisibility(context: Context): SongListInfoVisibility {
        val p = MicaSettingsStore.prefs(context)
        return SongListInfoVisibility(
            showSongArtist = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_SONG_ARTIST, true),
            showSongAlbum = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_SONG_ALBUM, true),
            showSongPlayCount = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_SONG_PLAY_COUNT, true),
            showSongDuration = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_SONG_DURATION, false),
            showSongCount = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_COUNT, true),
            showLibrarySize = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_SIZE, true),
            showSortOrder = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_SORT, true),
            showLastScanTime = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_LAST_SCAN, true),
            showCustomText = p.getBoolean(KEY_SONG_LIST_INFO_SHOW_CUSTOM, false),
            customText = p.getString(KEY_SONG_LIST_INFO_CUSTOM_TEXT, "") ?: "",
            trailingInfo = SongTrailingInfo.fromStorage(p.getInt(KEY_SONG_LIST_TRAILING_INFO, SongTrailingInfo.FORMAT.storageValue)),
        )
    }

    fun setSongListInfoVisibility(context: Context, visibility: SongListInfoVisibility) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_SONG_ARTIST, visibility.showSongArtist)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_SONG_ALBUM, visibility.showSongAlbum)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_SONG_PLAY_COUNT, visibility.showSongPlayCount)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_SONG_DURATION, visibility.showSongDuration)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_COUNT, visibility.showSongCount)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_SIZE, visibility.showLibrarySize)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_SORT, visibility.showSortOrder)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_LAST_SCAN, visibility.showLastScanTime)
            .putBoolean(KEY_SONG_LIST_INFO_SHOW_CUSTOM, visibility.showCustomText)
            .putString(KEY_SONG_LIST_INFO_CUSTOM_TEXT, visibility.customText)
            .putInt(KEY_SONG_LIST_TRAILING_INFO, visibility.trailingInfo.storageValue)
            .apply()
    }

    fun browseListInfoVisibility(context: Context): BrowseListInfoVisibility {
        val p = MicaSettingsStore.prefs(context)
        return BrowseListInfoVisibility(
            showArtistCount = p.getBoolean(KEY_ARTIST_INFO_SHOW_COUNT, true),
            showArtistSortOrder = p.getBoolean(KEY_ARTIST_INFO_SHOW_SORT, true),
            showArtistGridColumns = p.getBoolean(KEY_ARTIST_INFO_SHOW_GRID, true),
            showArtistLastScanTime = p.getBoolean(KEY_ARTIST_INFO_SHOW_LAST_SCAN, true),
            showArtistCustomText = p.getBoolean(KEY_ARTIST_INFO_SHOW_CUSTOM, false),
            artistCustomText = p.getString(KEY_ARTIST_INFO_CUSTOM_TEXT, "") ?: "",
            showAlbumCount = p.getBoolean(KEY_ALBUM_INFO_SHOW_COUNT, true),
            showAlbumSortOrder = p.getBoolean(KEY_ALBUM_INFO_SHOW_SORT, true),
            showAlbumGridColumns = p.getBoolean(KEY_ALBUM_INFO_SHOW_GRID, true),
            showAlbumLastScanTime = p.getBoolean(KEY_ALBUM_INFO_SHOW_LAST_SCAN, true),
            showAlbumCustomText = p.getBoolean(KEY_ALBUM_INFO_SHOW_CUSTOM, false),
            albumCustomText = p.getString(KEY_ALBUM_INFO_CUSTOM_TEXT, "") ?: "",
        )
    }

    fun setBrowseListInfoVisibility(context: Context, visibility: BrowseListInfoVisibility) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_ARTIST_INFO_SHOW_COUNT, visibility.showArtistCount)
            .putBoolean(KEY_ARTIST_INFO_SHOW_SORT, visibility.showArtistSortOrder)
            .putBoolean(KEY_ARTIST_INFO_SHOW_GRID, visibility.showArtistGridColumns)
            .putBoolean(KEY_ARTIST_INFO_SHOW_LAST_SCAN, visibility.showArtistLastScanTime)
            .putBoolean(KEY_ARTIST_INFO_SHOW_CUSTOM, visibility.showArtistCustomText)
            .putString(KEY_ARTIST_INFO_CUSTOM_TEXT, visibility.artistCustomText)
            .putBoolean(KEY_ALBUM_INFO_SHOW_COUNT, visibility.showAlbumCount)
            .putBoolean(KEY_ALBUM_INFO_SHOW_SORT, visibility.showAlbumSortOrder)
            .putBoolean(KEY_ALBUM_INFO_SHOW_GRID, visibility.showAlbumGridColumns)
            .putBoolean(KEY_ALBUM_INFO_SHOW_LAST_SCAN, visibility.showAlbumLastScanTime)
            .putBoolean(KEY_ALBUM_INFO_SHOW_CUSTOM, visibility.showAlbumCustomText)
            .putString(KEY_ALBUM_INFO_CUSTOM_TEXT, visibility.albumCustomText)
            .apply()
    }

    fun playerInfoVisibility(context: Context): PlayerInfoVisibility {
        val p = MicaSettingsStore.prefs(context)
        return PlayerInfoVisibility(
            showFormat = p.getBoolean(KEY_PLAYER_INFO_SHOW_FORMAT, true),
            showSampleRate = p.getBoolean(KEY_PLAYER_INFO_SHOW_SAMPLE_RATE, true),
            showBitrate = p.getBoolean(KEY_PLAYER_INFO_SHOW_BITRATE, true),
            showPlaybackSpeed = p.getBoolean(KEY_PLAYER_INFO_SHOW_PLAYBACK_SPEED, false),
            showPlaybackPitch = p.getBoolean(KEY_PLAYER_INFO_SHOW_PLAYBACK_PITCH, false),
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
            .putBoolean(KEY_PLAYER_INFO_SHOW_PLAYBACK_SPEED, visibility.showPlaybackSpeed)
            .putBoolean(KEY_PLAYER_INFO_SHOW_PLAYBACK_PITCH, visibility.showPlaybackPitch)
            .putBoolean(KEY_PLAYER_INFO_SHOW_CURRENT_TIME, visibility.showCurrentTime)
            .putBoolean(KEY_PLAYER_INFO_SHOW_CUSTOM, visibility.showCustomText)
            .putString(KEY_PLAYER_INFO_CUSTOM_TEXT, visibility.customText)
            .apply()
    }

    fun hiResBadgeStyle(context: Context): HiResBadgeStyle =
        HiResBadgeStyle.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_HI_RES_BADGE_STYLE, null),
        )

    fun setHiResBadgeStyle(context: Context, style: HiResBadgeStyle) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_HI_RES_BADGE_STYLE, style.storageValue)
            .apply()
    }

    fun hiResBadgeCustomImagePath(context: Context): String? =
        MicaSettingsStore.prefs(context)
            .getString(KEY_HI_RES_BADGE_CUSTOM_IMAGE_PATH, null)
            ?.takeIf { it.isNotBlank() }

    fun setHiResBadgeCustomImagePath(context: Context, path: String?) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_HI_RES_BADGE_CUSTOM_IMAGE_PATH, path)
            .apply()
    }
}
