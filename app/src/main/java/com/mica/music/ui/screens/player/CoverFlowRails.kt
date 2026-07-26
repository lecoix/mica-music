package com.mica.music.ui.screens.player

import com.mica.music.data.PlayerCoverFlowMode
import kotlin.math.abs

/**
 * 七轨封面流：固定 lane ∈ [-3,3]，每张图沿轨道 [railOffset] 定位。
 *
 * - 唯一动画量：[stripFraction]（0→1 切下一首，0→-1 切上一首）
 * - 轨道坐标：[railOffset] = laneIndex - stripFraction
 * - 切歌末帧提交：logicalCenter += Δ，stripFraction = 0；lane 减 1 后 [railOffset] 不变
 */
internal object CoverFlowRails {
    const val PauseFoldStep = 0.80f
    /** 复古：|railOffset|=1 时的累计间距系数（相对封面宽）。 */
    const val RetroFirstStep = 1.10f
    /** 复古：|railOffset|=2 时的累计间距系数（相对封面宽）。 */
    const val RetroOuterStep = 1.20f
    /** 横屏 13 槽：中心相邻封面的间距。 */
    const val LandscapeRetroFirstStep = 1.10f
    /** 横屏 13 槽：向屏幕两侧持续拉开的轨道间距。 */
    const val LandscapeRetroOuterStep = 0.196f
    /** 横屏复古：12 张侧封面统一放大，中心封面保持不变。 */
    const val LandscapeRetroSideScaleMultiplier = 1.10f
    /** 横屏复古：±1 封面的转角。 */
    const val LandscapeRetroFirstRotationY = 73f
    /** 横屏复古：从 ±2 开始，每向外一槽减少的转角。 */
    const val LandscapeRetroOuterRotationStep = 1f
    /** 横屏复古：最外侧封面的最小转角。 */
    const val LandscapeRetroMinimumRotationY = 68f
    const val NearSideScale = 0.85f
    const val OuterSideScale = 0.90f

    fun railOffset(laneIndex: Int, stripFraction: Float): Float =
        laneIndex - stripFraction

    fun translationPx(
        railOffset: Float,
        coverWidthPx: Float,
        mode: PlayerCoverFlowMode,
        expandedRetro: Boolean = false,
    ): Float {
        val w = coverWidthPx.coerceAtLeast(1f)
        if (mode == PlayerCoverFlowMode.PAUSE_FOLD) {
            return railOffset * PauseFoldStep * w
        }
        if (mode != PlayerCoverFlowMode.RETRO_3D) {
            return CoverFlowMath.slotTranslation(railOffset, w, mode)
        }
        val sign = when {
            railOffset < 0f -> -1f
            railOffset > 0f -> 1f
            else -> 0f
        }
        if (expandedRetro) {
            val d = abs(railOffset)
            val fraction = if (d <= 1f) {
                LandscapeRetroFirstStep * d
            } else {
                LandscapeRetroFirstStep + LandscapeRetroOuterStep * (d - 1f)
            }
            return sign * w * fraction
        }
        val d = abs(railOffset).coerceIn(0f, 3f)
        val fraction = when {
            d <= 1f -> RetroFirstStep * d
            d <= 2f -> RetroFirstStep + (RetroOuterStep - RetroFirstStep) * (d - 1f)
            else -> {
                val thirdStep = RetroOuterStep + (RetroOuterStep - RetroFirstStep) * 0.55f
                RetroOuterStep + (thirdStep - RetroOuterStep) * (d - 2f)
            }
        }
        return sign * w * fraction
    }

    fun drawScale(
        railOffset: Float,
        mode: PlayerCoverFlowMode,
        foldProgress: Float,
        expandedRetro: Boolean = false,
    ): Float {
        val distance = abs(railOffset)
        val centerScale = CoverFlowMath.centerScale(mode, foldProgress)
        val base = CoverFlowMath.slotScale(distance, centerScale, mode)
        if (mode != PlayerCoverFlowMode.RETRO_3D) return base
        val landscapeMultiplier = if (expandedRetro) {
            1f + (LandscapeRetroSideScaleMultiplier - 1f) * distance.coerceIn(0f, 1f)
        } else {
            1f
        }
        return base * retroExtraScale(distance) * landscapeMultiplier
    }

    fun rotationY(
        railOffset: Float,
        mode: PlayerCoverFlowMode,
        expandedRetro: Boolean = false,
    ): Float {
        val base = CoverFlowMath.slotRotationY(railOffset, mode)
        if (!expandedRetro || mode != PlayerCoverFlowMode.RETRO_3D) return base
        val outerDistance = (abs(railOffset) - 1f).coerceAtLeast(0f)
        val targetAngle =
            (LandscapeRetroFirstRotationY - LandscapeRetroOuterRotationStep * outerDistance)
                .coerceAtLeast(LandscapeRetroMinimumRotationY)
        val angleMultiplier = targetAngle / 75f
        return base * angleMultiplier
    }

    /** 外缘枢轴与中心枢轴随 |railOffset| 平滑过渡，避免切轨时跳变。 */
    fun pivotX(railOffset: Float, slotWidthPx: Float, mode: PlayerCoverFlowMode): Float {
        if (mode != PlayerCoverFlowMode.RETRO_3D) return 0f
        val d = abs(railOffset)
        val centerWeight = ((1.05f - d) / 1.05f).coerceIn(0f, 1f)
        val edgePivot = if (railOffset < 0f) slotWidthPx * 0.5f else -slotWidthPx * 0.5f
        return edgePivot * (1f - centerWeight)
    }

    fun zIndex(railOffset: Float, mode: PlayerCoverFlowMode): Float {
        val d = abs(railOffset)
        return if (mode == PlayerCoverFlowMode.RETRO_3D) {
            30f - d * 10f
        } else {
            10f - d
        }
    }

    fun alpha(railOffset: Float, foldProgress: Float, mode: PlayerCoverFlowMode): Float =
        CoverFlowMath.slotAlpha(abs(railOffset), foldProgress, mode)

    /**
     * 相邻切歌补间起点钳在 [fromLogicalCenter, endVisual]（或反向），只补剩余距离。
     * 拖动已滑到一半再点下一首时，避免动画先弹回 0。
     */
    fun clampTrackChangeStartVisual(
        fromLogicalCenter: Int,
        startVisual: Float,
        endVisual: Float,
        signedDelta: Int,
    ): Float =
        when {
            signedDelta > 0 -> startVisual.coerceIn(fromLogicalCenter.toFloat(), endVisual)
            signedDelta < 0 -> startVisual.coerceIn(endVisual, fromLogicalCenter.toFloat())
            else -> startVisual
        }

    private fun retroExtraScale(distance: Float): Float {
        val d = distance.coerceIn(0f, 2.05f)
        return when {
            d < 0.08f -> 1f
            d <= 1.05f -> {
                val t = ((d - 0.08f) / (1.05f - 0.08f)).coerceIn(0f, 1f)
                1f + (NearSideScale - 1f) * t
            }
            else -> {
                val t = ((d - 1.05f) / (2.05f - 1.05f)).coerceIn(0f, 1f)
                NearSideScale + (OuterSideScale - NearSideScale) * t
            }
        }
    }
}
