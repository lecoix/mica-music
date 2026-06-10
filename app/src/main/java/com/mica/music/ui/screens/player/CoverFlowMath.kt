package com.mica.music.ui.screens.player

import com.mica.music.data.PlayerCoverFlowMode
import kotlin.math.abs

internal object CoverFlowMath {
    /** 平行封面带相邻槽位间距（屏宽比例） */
    const val LaneStepFraction = 0.92f

    /** 复古立体封面首格间距（屏宽比例） */
    const val RetroLaneStepFraction = 0.81f

    /** Lane 池窗口半径；与 maxViewDistance 对齐，保证 ±3 槽可渲染 */
    const val LaneWindowRadius = 3

    /** 相对中心的可见距离上限（含最外 lane） */
    const val MaxViewDistance = 3f

    fun laneStepFraction(mode: PlayerCoverFlowMode): Float =
        when (mode) {
            PlayerCoverFlowMode.RETRO_3D -> RetroLaneStepFraction
            PlayerCoverFlowMode.PAUSE_FOLD -> LaneStepFraction
            PlayerCoverFlowMode.STANDARD -> LaneStepFraction
        }

    /** 平行封面带：父层整体平移，槽位仅用整数 laneOffset 定位，避免浮点 offset 与缓动不同步 */
    fun carouselShiftPx(
        laneFraction: Float,
        screenWidthPx: Float,
        mode: PlayerCoverFlowMode,
    ): Float {
        if (mode != PlayerCoverFlowMode.PAUSE_FOLD) return 0f
        return -laneFraction * LaneStepFraction * screenWidthPx
    }

    fun centerScale(mode: PlayerCoverFlowMode, foldProgress: Float): Float =
        when (mode) {
            PlayerCoverFlowMode.RETRO_3D -> 1f - 0.38f * foldProgress
            PlayerCoverFlowMode.PAUSE_FOLD -> 1f - 0.24f * foldProgress
            PlayerCoverFlowMode.STANDARD -> 1f
        }

    fun slotScale(distance: Float, centerScale: Float, mode: PlayerCoverFlowMode): Float {
        if (mode != PlayerCoverFlowMode.RETRO_3D) return centerScale
        val d = distance.coerceIn(0f, 2f)
        return if (d <= 1f) {
            centerScale + (0.52f - centerScale) * d
        } else {
            0.52f + (0.44f - 0.52f) * (d - 1f)
        }
    }

    fun slotAlpha(distance: Float, foldProgress: Float, mode: PlayerCoverFlowMode): Float {
        val d = distance.coerceIn(0f, 2f)
        val farAlpha = if (mode == PlayerCoverFlowMode.RETRO_3D) 1f else 0.48f
        val alpha = when {
            d <= 1f -> 1f
            else -> 1f + (farAlpha - 1f) * (d - 1f)
        }
        return if (d < 0.05f) alpha else alpha * foldProgress
    }

    fun slotTranslation(offset: Float, screenWidthPx: Float, mode: PlayerCoverFlowMode): Float {
        val sign = when {
            offset < 0f -> -1f
            offset > 0f -> 1f
            else -> 0f
        }
        val distance = abs(offset)
        if (mode == PlayerCoverFlowMode.PAUSE_FOLD) {
            return offset * LaneStepFraction * screenWidthPx
        }
        if (mode != PlayerCoverFlowMode.RETRO_3D) {
            val fraction = if (distance <= 1f) {
                LaneStepFraction * distance
            } else {
                LaneStepFraction + LaneStepFraction * (distance - 1f)
            }
            return sign * screenWidthPx * fraction
        }
        val d = distance.coerceIn(0f, 2f)
        val fraction = if (d <= 1f) {
            RetroLaneStepFraction * d
        } else {
            RetroLaneStepFraction + (0.90f - RetroLaneStepFraction) * (d - 1f)
        }
        return sign * screenWidthPx * fraction
    }

    fun slotZIndex(distance: Float, mode: PlayerCoverFlowMode): Float {
        if (mode != PlayerCoverFlowMode.RETRO_3D) return 10f - distance
        return when {
            distance < 0.05f -> 30f
            distance <= 1.05f -> 20f
            else -> 10f - distance
        }
    }

    fun slotRotationY(offset: Float, mode: PlayerCoverFlowMode): Float {
        if (mode != PlayerCoverFlowMode.RETRO_3D) return 0f
        val distance = abs(offset)
        val turn = distance.coerceIn(0f, 1f)
        val easedTurn = turn * turn * (3f - 2f * turn)
        val sign = when {
            offset < 0f -> 1f
            offset > 0f -> -1f
            else -> 0f
        }
        return sign * 75f * easedTurn
    }
}
