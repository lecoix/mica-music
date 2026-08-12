package com.mica.music.ui.screens.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.CompactLyricsLineMode

/** 播放页主场景（互斥优先级：Lyrics > Immersive > Normal）。 */
enum class PlayerPageScene {
    Normal,
    Lyrics,
    Immersive,
}

@Immutable
data class PlayerLowerPanelSpacing(
    val afterCover: Dp,
    val afterInfo: Dp,
    val afterSubtitle: Dp,
    val beforePlaybackChrome: Dp,
    val afterProgress: Dp,
    val afterControls: Dp,
    val lyricLineSlots: Int,
)

@Immutable
data class CoverFrame(
    val width: Dp,
    val height: Dp,
    val startPadding: Dp,
    val topPadding: Dp,
    val blockHeight: Dp,
    val particleInfoTopPadding: Dp,
    val letterboxAlpha: Float,
    val zoneStop: Float,
)

@Immutable
data class ParticleCoverFrame(
    val enabled: Boolean,
    val normalLayerVisible: Boolean,
    val lyricsBackgroundVisible: Boolean,
    val hostBaseSize: Dp,
)

@Immutable
data class PhotoStackFrame(
    val enabled: Boolean,
    val normalLayerVisible: Boolean,
    val immersiveProgress: Float,
    val slotWidth: Dp,
    val slotHeight: Dp,
    val cardTopInset: Dp,
    val cardWidth: Dp,
    val cardHeight: Dp,
    val artworkInsetTop: Dp,
    val artworkInsetHorizontal: Dp,
    val artworkBottomBand: Dp,
    val waveformHeight: Dp,
)

@Immutable
data class LowerPanelFrame(
    val spacing: PlayerLowerPanelSpacing,
    val chromeHeight: Dp,
    val controlsBottomPadding: Dp,
    val photoStackTitleBlockHeight: Dp,
    val photoStackTitleToControlsGap: Dp,
    val titleSlideDown: Dp,
    val showMetadata: Boolean,
    val metaAlpha: Float,
    val compactContentAlpha: Float,
    val lyricsChromeFade: Float,
    val lyricsLayoutFocus: Float,
    val immersiveProgress: Float,
    val showStandardProgress: Boolean,
    val coverEdgeOnPlaySurface: Boolean,
    val showChromeProgressInTransition: Boolean,
    val chromeProgressAlpha: Float,
    val spectrumOverlayAlpha: Float,
    val lyricLineSlots: Int,
    val hideInfoAndLyrics: Boolean,
)

@Immutable
data class PlayerPageFrame(
    val scene: PlayerPageScene,
    val lyricsProgress: Float,
    val immersiveProgress: Float,
    val coverFlowProgress: Float,
    val coverFlowStageActive: Boolean,
    val gesturesEnabled: Boolean,
    val spectrumEnabled: Boolean,
    val cover: CoverFrame,
    val particleCover: ParticleCoverFrame,
    val photoStack: PhotoStackFrame,
    val lower: LowerPanelFrame,
)

@Immutable
data class PlayerPageLayoutInput(
    val panelHeight: Dp,
    val screenHeight: Dp,
    val screenWidth: Dp,
    val statusBarTop: Dp,
    val lyricsExpanded: Boolean,
    val lyricsProgress: Float,
    val lyricsChromeFade: Float,
    val immersiveLower: Boolean,
    val immersiveProgress: Float,
    val coverFlowProgress: Float,
    val coverFlowModeEnabled: Boolean,
    val useCoverEdgeProgress: Boolean,
    val particleCoverMode: Boolean,
    val photoStackMode: Boolean,
    val fitOriginal: Boolean,
    val coverAspectRatio: Float,
    val spectrumSettingEnabled: Boolean,
    val spectrumDeferred: Boolean,
    val coverSwitching: Boolean,
    val compactLyricsLineMode: CompactLyricsLineMode = CompactLyricsLineMode.AUTO,
)

internal const val ImmersiveProgressEpsilon = 0.001f
internal val LyricsFocusMiniCoverSize = 56.dp * 0.95f
internal val LyricsFocusCoverStartPadding = 16.dp + 8.dp // HifiSpacing.lg + sm

/** 歌词页底栏：五按钮与屏幕底边间距在歌词聚焦满进度时缩至该比例。 */
internal const val LyricsChromeBottomInsetScale = 0.5f
internal val LyricsChromeDrop = 24.dp - 4.dp

internal fun lyricsChromeBottomInsetScale(lyricsFocus: Float): Float =
    1f - lyricsFocus.coerceIn(0f, 1f) * (1f - LyricsChromeBottomInsetScale)

internal fun lyricsChromeDrop(lyricsFocus: Float): Dp =
    LyricsChromeDrop * lyricsFocus.coerceIn(0f, 1f)
