package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mica.music.data.CoverDisplayMode
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.LocalCoverDisplayMode

@Composable
fun SongCover(
    albumArtUri: String?,
    fallbackColor: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale? = null,
    /**
     * 原样比例下是否按图片宽高比撑开容器。
     * 默认 false：列表 / 迷你栏 / 歌词聚焦等保持调用方给的正方形容器，图在框内完整显示。
     * 仅播放页全屏封面可设为 true。
     */
    allowIntrinsicBounds: Boolean = false,
    maxHeight: Dp? = null,
    maxWidth: Dp? = null,
    onAspectRatioChanged: ((Float) -> Unit)? = null,
    /** 原样比例黑底衬透明度；歌词↔播放切换时由播放页控制显隐顺序。 */
    letterboxAlpha: Float = 1f,
    /** Coil 加载 crossfade；同一 URI 布局动画时宜为 0，避免切换分支时闪一下。 */
    crossfadeMillis: Int = 200,
) {
    val displayMode = LocalCoverDisplayMode.current
    val resolvedScale = contentScale ?: when (displayMode) {
        CoverDisplayMode.CROP_FILL -> ContentScale.Crop
        CoverDisplayMode.FIT_ORIGINAL -> ContentScale.Fit
    }

    var aspectRatio by remember(albumArtUri) { mutableFloatStateOf(1f) }

    val layoutModifier = when (displayMode) {
        CoverDisplayMode.CROP_FILL -> modifier
        CoverDisplayMode.FIT_ORIGINAL -> {
            if (allowIntrinsicBounds) {
                val maxH = maxHeight
                val maxW = maxWidth
                if (maxH != null && maxW != null) {
                    modifier.intrinsicCoverBox(aspectRatio, maxW, maxH)
                } else {
                    modifier
                }
            } else {
                modifier
            }
        }
    }

    val backdropColor = when (displayMode) {
        CoverDisplayMode.CROP_FILL -> fallbackColor
        CoverDisplayMode.FIT_ORIGINAL ->
            HifiPalette.CoverFitLetterbox.copy(alpha = letterboxAlpha.coerceIn(0f, 1f))
    }
    Box(modifier = layoutModifier.background(backdropColor)) {
        if (!albumArtUri.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(albumArtUri)
                    .crossfade(crossfadeMillis)
                    .build(),
                contentDescription = contentDescription,
                contentScale = resolvedScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { state ->
                    if (!allowIntrinsicBounds && onAspectRatioChanged == null) return@AsyncImage
                    val size = state.painter.intrinsicSize
                    val ratio = coerceCoverAspectRatio(size.width, size.height)
                    if (allowIntrinsicBounds && ratio != aspectRatio) {
                        aspectRatio = ratio
                    }
                    onAspectRatioChanged?.invoke(ratio)
                },
            )
        }
    }
}
