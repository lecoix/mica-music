package com.mica.music.ui.components

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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

private var lastReadyCoverHoldoverUri by mutableStateOf<String?>(null)

/**
 * 已成功解码过的封面 URI 集合。用于让“就绪”状态脱离单个 composable 的寿命：
 * 当槽位被重建（如封面带切歌跨整数导致整组 key 重建）时，已解码过的封面起始即视为就绪，
 * 避免回退到全局占位（显示别的/上一张封面）造成闪帧。
 */
private val decodedCoverUris = LruCache<String, Boolean>(128)

internal fun coverHoldoverUri(
    albumArtUri: String?,
    imageReady: Boolean,
    allowSameUri: Boolean = false,
): String? =
    lastReadyCoverHoldoverUri?.takeIf {
        !imageReady && (allowSameUri || it != albumArtUri)
    }

internal fun markCoverHoldoverReady(albumArtUri: String?) {
    if (!albumArtUri.isNullOrBlank()) {
        lastReadyCoverHoldoverUri = albumArtUri
    }
}

internal fun markCoverDecoded(albumArtUri: String?) {
    if (!albumArtUri.isNullOrBlank()) {
        decodedCoverUris.put(albumArtUri, true)
    }
}

internal fun coverImageInitiallyReady(albumArtUri: String?): Boolean =
    albumArtUri.isNullOrBlank() ||
        albumArtUri == lastReadyCoverHoldoverUri ||
        decodedCoverUris.get(albumArtUri) == true

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
    drawBackdropWhileLoading: Boolean = true,
    onImageReady: () -> Unit = {},
    onImageFailed: () -> Unit = {},
    publishHoldoverOnSuccess: Boolean = true,
    holdoverUntilImageReady: Boolean = false,
    holdoverAlbumArtUri: String? = null,
    /**
     * 稳定内存缓存键。封面带等会被销毁重建的场景传入封面 URI：
     * 重建时 Coil 会用该键同步取出内存缓存里的位图作为第一帧（不解码、不异步），
     * 避免重建后出现空白帧（模糊/渐变背景下表现为闪一下）。
     */
    stableMemoryCacheKey: String? = null,
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
    var imageReady by remember(albumArtUri) {
        mutableStateOf(
            if (holdoverUntilImageReady) {
                albumArtUri.isNullOrBlank()
            } else {
                coverImageInitiallyReady(albumArtUri)
            },
        )
    }
    LaunchedEffect(albumArtUri, imageReady) {
        if (imageReady) {
            onImageReady()
        }
    }
    val effectiveBackdropColor = if (drawBackdropWhileLoading || imageReady) {
        backdropColor
    } else {
        Color.Transparent
    }
    Box(modifier = layoutModifier.background(effectiveBackdropColor)) {
        val loadingHoldoverUri = if (!imageReady) {
            holdoverAlbumArtUri ?: coverHoldoverUri(
                albumArtUri = albumArtUri,
                imageReady = false,
                allowSameUri = holdoverUntilImageReady,
            )
        } else {
            null
        }
        if (!loadingHoldoverUri.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(loadingHoldoverUri)
                    .crossfade(0)
                    .build(),
                contentDescription = null,
                contentScale = resolvedScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!albumArtUri.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(albumArtUri)
                    .crossfade(crossfadeMillis)
                    .apply {
                        if (!stableMemoryCacheKey.isNullOrBlank()) {
                            memoryCacheKey(stableMemoryCacheKey)
                            placeholderMemoryCacheKey(stableMemoryCacheKey)
                        }
                    }
                    .build(),
                contentDescription = contentDescription,
                contentScale = resolvedScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { state ->
                    imageReady = true
                    markCoverDecoded(albumArtUri)
                    if (publishHoldoverOnSuccess) {
                        markCoverHoldoverReady(albumArtUri)
                    }
                    val size = state.painter.intrinsicSize
                    val ratio = coerceCoverAspectRatio(size.width, size.height)
                    cacheCoverAspectRatio(albumArtUri, ratio)
                    if (!allowIntrinsicBounds && onAspectRatioChanged == null) return@AsyncImage
                    if (allowIntrinsicBounds && ratio != aspectRatio) {
                        aspectRatio = ratio
                    }
                    onAspectRatioChanged?.invoke(ratio)
                },
                onError = {
                    imageReady = true
                    onImageFailed()
                },
            )
        }
    }
}
