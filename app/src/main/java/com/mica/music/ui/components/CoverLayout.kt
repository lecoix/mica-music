package com.mica.music.ui.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import kotlin.math.min

/** 原样显示时允许的最宽比例（相对高度），避免列表行被极宽横图撑破。 */
const val CoverMaxAspectRatio = 2.5f

const val CoverMinAspectRatio = 0.45f

fun coerceCoverAspectRatio(width: Float, height: Float): Float {
    if (width <= 0f || height <= 0f) return 1f
    return (width / height).coerceIn(CoverMinAspectRatio, CoverMaxAspectRatio)
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

fun Modifier.intrinsicCoverBox(
    aspectRatio: Float,
    maxWidth: Dp,
    maxHeight: Dp,
): Modifier = sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
    .aspectRatio(aspectRatio.coerceIn(CoverMinAspectRatio, CoverMaxAspectRatio))

/** 歌词聚焦动画中，0=正方形容器，1=按原图比例尺寸。 */
fun coverIntrinsicMorphProgress(lyricsFocus: Float, morphEndFocus: Float = 0.05f): Float =
    (1f - (lyricsFocus / morphEndFocus).coerceIn(0f, 1f))
