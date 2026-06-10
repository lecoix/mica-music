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
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.LocalCoverDisplayMode

private var lastReadyCoverHoldoverUri by mutableStateOf<String?>(null)

/**
 * 已成功解码过的封面 URI 集合。供其它模块查询；**不**再用于跳过 holdover 层，
 * 避免「缓存命中但 AsyncImage 尚未绘出」时出现空白帧。
 */
private val decodedCoverUris = LruCache<String, Boolean>(192)

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
    allowIntrinsicBounds: Boolean = false,
    maxHeight: Dp? = null,
    maxWidth: Dp? = null,
    onAspectRatioChanged: ((Float) -> Unit)? = null,
    letterboxAlpha: Float = 1f,
    crossfadeMillis: Int = 200,
    drawBackdropWhileLoading: Boolean = true,
    onImageReady: () -> Unit = {},
    onImageFailed: () -> Unit = {},
    publishHoldoverOnSuccess: Boolean = true,
    holdoverUntilImageReady: Boolean = false,
    holdoverAlbumArtUri: String? = null,
    stableMemoryCacheKey: String? = null,
) {
    val context = LocalContext.current
    val displayMode = LocalCoverDisplayMode.current
    val resolvedScale = contentScale ?: when (displayMode) {
        CoverDisplayMode.CROP_FILL -> ContentScale.Crop
        CoverDisplayMode.FIT_ORIGINAL -> ContentScale.Fit
    }
    val memoryCacheKey = stableMemoryCacheKey ?: albumArtUri

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

    // 已真正绘上屏的 URI；切换时保留旧图作底，直到新图 onSuccess。
    var lastPaintedUri by remember { mutableStateOf<String?>(null) }
    val isPainted = albumArtUri.isNullOrBlank() || lastPaintedUri == albumArtUri

    LaunchedEffect(albumArtUri) {
        if (albumArtUri.isNullOrBlank()) {
            lastPaintedUri = null
            onImageReady()
        } else {
            MicaImageLoaders.preloadCover(context, albumArtUri)
        }
    }

    val underlayUri = when {
        albumArtUri.isNullOrBlank() -> null
        holdoverAlbumArtUri != null && holdoverAlbumArtUri != albumArtUri -> holdoverAlbumArtUri
        lastPaintedUri != null && lastPaintedUri != albumArtUri -> lastPaintedUri
        !isPainted -> coverHoldoverUri(
            albumArtUri = albumArtUri,
            imageReady = false,
            allowSameUri = holdoverUntilImageReady,
        )
        else -> null
    }

    val effectiveBackdropColor = when {
        underlayUri != null -> Color.Transparent
        drawBackdropWhileLoading || isPainted -> backdropColor
        else -> Color.Transparent
    }
    val coverImageLoader = remember { MicaImageLoaders.cover }

    Box(modifier = layoutModifier.background(effectiveBackdropColor)) {
        if (!underlayUri.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(underlayUri)
                    .crossfade(0)
                    .apply {
                        memoryCacheKey(underlayUri)
                        placeholderMemoryCacheKey(underlayUri)
                    }
                    .build(),
                imageLoader = coverImageLoader,
                contentDescription = null,
                contentScale = resolvedScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!albumArtUri.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(albumArtUri)
                    .crossfade(crossfadeMillis)
                    .apply {
                        if (!memoryCacheKey.isNullOrBlank()) {
                            memoryCacheKey(memoryCacheKey)
                            placeholderMemoryCacheKey(memoryCacheKey)
                        }
                    }
                    .build(),
                imageLoader = coverImageLoader,
                contentDescription = contentDescription,
                contentScale = resolvedScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { state ->
                    lastPaintedUri = albumArtUri
                    markCoverDecoded(albumArtUri)
                    if (publishHoldoverOnSuccess) {
                        markCoverHoldoverReady(albumArtUri)
                    }
                    val size = state.painter.intrinsicSize
                    val ratio = coerceCoverAspectRatio(size.width, size.height)
                    cacheCoverAspectRatio(albumArtUri, ratio)
                    if (!allowIntrinsicBounds && onAspectRatioChanged == null) {
                        onImageReady()
                        return@AsyncImage
                    }
                    if (allowIntrinsicBounds && ratio != aspectRatio) {
                        aspectRatio = ratio
                    }
                    onAspectRatioChanged?.invoke(ratio)
                    onImageReady()
                },
                onError = {
                    lastPaintedUri = albumArtUri
                    onImageFailed()
                },
            )
        }
    }
}
