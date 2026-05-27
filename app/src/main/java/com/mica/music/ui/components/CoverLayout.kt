package com.mica.music.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** 原样显示时允许的最宽比例（相对高度），避免列表行被极宽横图撑破。 */
const val CoverMaxAspectRatio = 2.5f

const val CoverMinAspectRatio = 0.45f

/** 播放页「原样比例」封面最大占屏高（其余为下半元数据 + 底栏 chrome）。 */
const val PlayerCoverMaxScreenFraction = 0.65f

fun coerceCoverAspectRatio(width: Float, height: Float): Float {
    if (width <= 0f || height <= 0f) return 1f
    return (width / height).coerceIn(CoverMinAspectRatio, CoverMaxAspectRatio)
}

private val coverAspectRatioCache = ConcurrentHashMap<String, Float>()

fun cacheCoverAspectRatio(albumArtUri: String?, aspectRatio: Float) {
    val key = albumArtUri ?: return
    coverAspectRatioCache[key] = aspectRatio.coerceIn(CoverMinAspectRatio, CoverMaxAspectRatio)
}

fun resolveCoverAspectRatioFromUri(context: Context, albumArtUri: String?): Float? {
    if (albumArtUri.isNullOrBlank()) return null
    coverAspectRatioCache[albumArtUri]?.let { return it }
    val ratio = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(Uri.parse(albumArtUri))?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        coerceCoverAspectRatio(bounds.outWidth.toFloat(), bounds.outHeight.toFloat())
    }.getOrNull()
    if (ratio != null) {
        coverAspectRatioCache[albumArtUri] = ratio
    }
    return ratio
}

/**
 * 在 [maxWidth] × [maxHeight] 内按原图比例取最大可用尺寸（不裁切、不留白边策略下的容器）。
 */
fun measureIntrinsicCoverSize(
    aspectRatio: Float,
    maxWidth: Dp,
    maxHeight: Dp,
): Pair<Dp, Dp> {
    val ratio = aspectRatio.coerceIn(CoverMinAspectRatio, CoverMaxAspectRatio)
    var height = min(maxHeight.value, maxWidth.value / ratio)
    var width = height * ratio
    if (width > maxWidth.value) {
        width = maxWidth.value
        height = width / ratio
    }
    return Dp(width) to Dp(height)
}

/**
 * 播放页「原样比例」：优先铺满屏宽，高度按比例延伸，最高 [PlayerCoverMaxScreenFraction] 屏高。
 * 与 [measureIntrinsicCoverSize]（先压高度）不同，竖长图在 65% 以内可做到宽度顶满。
 */
fun measurePlayerCoverFitOriginal(
    aspectRatio: Float,
    screenWidth: Dp,
    screenHeight: Dp,
): Pair<Dp, Dp> {
    val ratio = aspectRatio.coerceIn(CoverMinAspectRatio, CoverMaxAspectRatio)
    val maxHeight = screenHeight * PlayerCoverMaxScreenFraction
    val heightAtFullWidth = screenWidth / ratio
    return if (heightAtFullWidth <= maxHeight) {
        screenWidth to heightAtFullWidth
    } else {
        val h = maxHeight
        val w = Dp(h.value * ratio)
        w to h
    }
}

fun Modifier.intrinsicCoverBox(
    aspectRatio: Float,
    maxWidth: Dp,
    maxHeight: Dp,
): Modifier = sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
    .aspectRatio(aspectRatio.coerceIn(CoverMinAspectRatio, CoverMaxAspectRatio))

/** 歌词聚焦动画中，0=正方形容器，1=按原图比例尺寸。 */
fun coverIntrinsicMorphProgress(lyricsFocus: Float, morphEndFocus: Float = 0.05f): Float =
    (1f - (lyricsFocus / morphEndFocus).coerceIn(0f, 1f))
