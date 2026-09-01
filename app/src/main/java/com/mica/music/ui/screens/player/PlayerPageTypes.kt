package com.mica.music.ui.screens.player

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.mica.music.data.CompactLyricsLineMode
import com.mica.music.ui.components.PlayerCoverMaxScreenFraction
import com.mica.music.ui.theme.HifiSize

/** 播放页主场景（互斥优先级：Queue > Lyrics > Immersive > Normal）。 */
enum class PlayerPageScene {
    Normal,
    Lyrics,
    Immersive,
    Queue,
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
    /** Artwork origin X. May be negative for oversized/left-shifted custom covers. */
    val startPadding: Dp,
    /** Artwork origin Y. May be negative for up-shifted custom covers. */
    val topPadding: Dp,
    val blockHeight: Dp,
    val particleInfoTopPadding: Dp,
    val letterboxAlpha: Float,
    val zoneStop: Float,
)

/** Compose `padding` rejects negatives; keep the inset and the overflow offset separate. */
internal fun coverOriginPadding(value: Dp): Dp = value.coerceAtLeast(0.dp)

internal fun coverOriginOffset(value: Dp): Dp = value.coerceAtMost(0.dp)

internal fun Modifier.coverOriginPlacement(start: Dp = 0.dp, top: Dp = 0.dp): Modifier =
    padding(start = coverOriginPadding(start), top = coverOriginPadding(top))
        .offset(x = coverOriginOffset(start), y = coverOriginOffset(top))

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
    val queueLayoutFocus: Float,
    val immersiveProgress: Float,
    val showStandardProgress: Boolean,
    val coverEdgeOnPlaySurface: Boolean,
    val showChromeProgressInTransition: Boolean,
    val chromeProgressAlpha: Float,
    val spectrumOverlayAlpha: Float,
    val lyricLineSlots: Int,
    val hideInfoAndLyrics: Boolean,
)

private const val PhotoStackListLyricsBottomGapFraction = 0.05f

/** 拍立得 LIST 歌词页保留五按钮控制行，底部留白取有效屏高 5% 与剩余空间上限的较小值。 */
internal fun LowerPanelFrame.forPhotoStackListLyricsPage(
    effectiveScreenHeight: Dp,
): LowerPanelFrame {
    val bottomGap = minOf(
        effectiveScreenHeight.coerceAtLeast(0.dp) * PhotoStackListLyricsBottomGapFraction,
        controlsBottomPadding,
    )
    return copy(
        chromeHeight = HifiSize.touchTarget + bottomGap,
        controlsBottomPadding = bottomGap,
    )
}

@Immutable
data class PlayerPageFrame(
    val scene: PlayerPageScene,
    val lyricsProgress: Float,
    val queueProgress: Float,
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
    val queueExpanded: Boolean = false,
    val queueProgress: Float = 0f,
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

internal fun playerHeaderFocus(lyricsProgress: Float, queueProgress: Float): Float =
    maxOf(lyricsProgress.coerceIn(0f, 1f), queueProgress.coerceIn(0f, 1f))

internal fun lerpCoverFrame(from: CoverFrame, to: CoverFrame, t: Float): CoverFrame {
    val u = t.coerceIn(0f, 1f)
    if (u <= 0f) return from
    if (u >= 1f) return to
    return CoverFrame(
        width = lerp(from.width, to.width, u),
        height = lerp(from.height, to.height, u),
        startPadding = lerp(from.startPadding, to.startPadding, u),
        topPadding = lerp(from.topPadding, to.topPadding, u),
        blockHeight = lerp(from.blockHeight, to.blockHeight, u),
        particleInfoTopPadding = lerp(from.particleInfoTopPadding, to.particleInfoTopPadding, u),
        letterboxAlpha = from.letterboxAlpha + (to.letterboxAlpha - from.letterboxAlpha) * u,
        zoneStop = from.zoneStop + (to.zoneStop - from.zoneStop) * u,
    )
}

/**
 * Custom-standard rest cover as a CoverSection frame: artwork is the scaled slot,
 * [CoverFrame.blockHeight] includes [coverTop] so the clip box is not a wide strip.
 */
internal fun customQueueCoverFrameAtRest(
    restCover: CoverFrame,
    visualScale: Float,
    coverTop: Dp,
    extraStartPadding: Dp,
    panelHeight: Dp,
): CoverFrame {
    val scale = visualScale.coerceAtLeast(0f)
    val width = restCover.width * scale
    val height = restCover.height * scale
    val topPadding = coverTop + restCover.topPadding * scale
    val artworkBlock = restCover.blockHeight * scale
    val blockHeight = topPadding + artworkBlock
    return restCover.copy(
        width = width,
        height = height,
        startPadding = extraStartPadding + restCover.startPadding * scale,
        topPadding = topPadding,
        blockHeight = blockHeight,
        zoneStop = (blockHeight.value / panelHeight.value.coerceAtLeast(1f))
            .coerceIn(0.12f, PlayerCoverMaxScreenFraction),
    )
}

/** Slot-local cover: same artwork size as [panelFrame], origin at the layout slot. */
internal fun customQueueCoverFrameInSlot(panelFrame: CoverFrame): CoverFrame =
    panelFrame.copy(
        topPadding = 0.dp,
        blockHeight = (panelFrame.blockHeight - panelFrame.topPadding).coerceAtLeast(panelFrame.height),
    )
