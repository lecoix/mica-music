package com.mica.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mica.music.ui.theme.PlayerContentColors

/**
 * PROTOTYPE: landscape cover-flow page with one bottom information/control row.
 * Absorb the accepted geometry into the player layout contract, then remove this wrapper.
 */
@Composable
internal fun LandscapeCoverFlowPrototype(
    colors: PlayerContentColors,
    edgePadding: Dp,
    coverHeight: Dp,
    coverContent: @Composable (Modifier) -> Unit,
    titleContent: @Composable (Modifier) -> Unit,
    lyricsContent: @Composable (Modifier) -> Unit,
    showStandardProgress: Boolean,
    progressContent: @Composable (Modifier) -> Unit,
    controlsContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val barHeight = (maxHeight * 0.22f).coerceIn(72.dp, 88.dp)
        val horizontalPadding = edgePadding.coerceIn(16.dp, 24.dp)
        // Equal side lanes keep the lyric/progress column centered on screen.
        val sideWidth = 240.dp
        val sectionGap = (maxWidth * 0.03f).coerceIn(18.dp, 48.dp)
        val barTop = maxHeight - barHeight
        val targetCoverTop = maxHeight * 0.025f
        val safeCoverHeight = coverHeight.coerceAtLeast(1.dp)
        // PAUSE_FOLD settles at 0.76x. Solve scale and translation from both
        // requested artwork bounds: a small top inset and bottom at barTop.
        val coverScale = (
            (barTop - targetCoverTop).value /
                (safeCoverHeight.value * 0.76f)
            ).coerceIn(1f, 1.35f)
        val coverTranslation = barTop -
            safeCoverHeight / 2 -
            safeCoverHeight * (0.38f * coverScale)

        coverContent(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = coverScale
                    scaleY = coverScale
                    translationY = coverTranslation.toPx()
                    clip = false
                }
                .zIndex(1f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            colors.primary.copy(alpha = 0.02f),
                            colors.primary.copy(alpha = 0.08f),
                        ),
                    ),
                )
                .drawBehind {
                    drawLine(
                        color = colors.primary.copy(alpha = 0.16f),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(horizontal = horizontalPadding)
                .zIndex(2f),
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
