package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.zIndex

/**
 * Persistent landscape cover-flow artwork layer. [progress] 0 = player stage, 1 = lyrics-list
 * left lane; [foldProgress] is derived as `1 - progress` for side-cover collapse.
 */
@Composable
internal fun LandscapeCoverFlowCoverLayer(
    progress: Float,
    edgePadding: Dp,
    coverHeight: Dp,
    contentPadding: PaddingValues,
    lyricsCoverSize: Dp,
    coverLaneWidth: Dp,
    horizontalPadding: Dp,
    topPadding: Dp,
    coverContent: @Composable (modifier: Modifier, foldProgress: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = progress.coerceIn(0f, 1f)
    val foldProgress = 1f - t

    BoxWithConstraints(modifier.fillMaxSize()) {
        val bottomInset = contentPadding.calculateBottomPadding()
        val contentHeight = (maxHeight - bottomInset).coerceAtLeast(1.dp)
        val barHeight = (contentHeight * 0.22f).coerceIn(72.dp, 88.dp)
        val barTop = contentHeight - barHeight
        val targetCoverTop = contentHeight * 0.025f
        val safeCoverHeight = coverHeight.coerceAtLeast(1.dp)
        val playerCoverScale = (
            (barTop - targetCoverTop).value /
                (safeCoverHeight.value * 0.76f)
            ).coerceIn(1f, 1.35f)
        val playerCoverTranslationY = barTop -
            safeCoverHeight / 2 -
            safeCoverHeight * (0.38f * playerCoverScale)

        val lyricsCoverScale = (
            lyricsCoverSize.value / safeCoverHeight.value.coerceAtLeast(0.01f)
            ).coerceIn(0.2f, 1.5f)
        val lyricsCenterX = horizontalPadding + coverLaneWidth / 2
        val playerCenterX = maxWidth / 2
        val lyricsLaneHeight = (contentHeight - topPadding).coerceAtLeast(1.dp)
        val lyricsCoverTop = topPadding + (lyricsLaneHeight - lyricsCoverSize) / 4
        val lyricsCoverTranslationY =
            lyricsCoverTop + lyricsCoverSize / 2 - safeCoverHeight / 2

        val coverScale = playerCoverScale + (lyricsCoverScale - playerCoverScale) * t
        val coverTranslationX = (lyricsCenterX - playerCenterX) * t
        val coverTranslationY = lerp(playerCoverTranslationY, lyricsCoverTranslationY, t)

        coverContent(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = coverScale
                    scaleY = coverScale
                    translationX = coverTranslationX.toPx()
                    translationY = coverTranslationY.toPx()
                    clip = false
                }
                .zIndex(1f),
            foldProgress,
        )
    }
}

/** Bottom information row for landscape cover-flow player (title | lyrics/progress | controls). */
@Composable
internal fun LandscapeCoverFlowPlayerBar(
    edgePadding: Dp,
    contentPadding: PaddingValues,
    showStandardProgress: Boolean,
    titleContent: @Composable (Modifier) -> Unit,
    lyricsContent: @Composable (Modifier) -> Unit,
    progressContent: @Composable (Modifier) -> Unit,
    controlsContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val layoutDirection = LocalLayoutDirection.current
        val bottomInset = contentPadding.calculateBottomPadding()
        val startInset = contentPadding.calculateLeftPadding(layoutDirection)
        val endInset = contentPadding.calculateRightPadding(layoutDirection)
        val contentHeight = (maxHeight - bottomInset).coerceAtLeast(1.dp)
        val barHeight = (contentHeight * 0.22f).coerceIn(72.dp, 88.dp)
        val horizontalPad = edgePadding.coerceIn(16.dp, 24.dp)
        val sideWidth = 240.dp
        val sectionGap = (maxWidth * 0.03f).coerceIn(18.dp, 48.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight + bottomInset)
                .align(Alignment.BottomCenter)
                .padding(
                    start = horizontalPad + startInset,
                    end = horizontalPad + endInset,
                    bottom = bottomInset,
                ),
            horizontalArrangement = Arrangement.spacedBy(sectionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(sideWidth),
                contentAlignment = Alignment.CenterStart,
            ) {
                titleContent(Modifier.fillMaxWidth())
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    lyricsContent(Modifier.fillMaxWidth())
                }
                if (showStandardProgress) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .graphicsLayer {
                                translationY = (-2).dp.toPx()
                            },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        progressContent(Modifier.fillMaxWidth())
                    }
                }
            }
            Box(
                modifier = Modifier.width(sideWidth),
                contentAlignment = Alignment.CenterEnd,
            ) {
                controlsContent(Modifier.fillMaxWidth())
            }
        }
    }
}
