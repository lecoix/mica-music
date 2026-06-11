package com.mica.music.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

private val blurConfiguredMarker = Any()

private fun supportsFloatingIslandRenderEffect(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 列表底边云母渐变色（浮岛卡片所在位置的主题背景）。 */
@Composable
fun rememberFloatingIslandListBackdropColor(): Color {
    val preset = LocalMicaBackgroundPreset.current
    val isDark = MicaTheme.colors.isDark
    return remember(preset, isDark) { preset.bottomThemeColor(isDark) }
}

private fun configureMicaBlurViewOnce(
    blurView: BlurView,
    blurTarget: BlurTarget,
    blurRadiusPx: Float,
    overlayColor: Color,
    decorBackground: android.graphics.drawable.Drawable?,
) {
    if (blurView.tag === blurConfiguredMarker) return
    if (blurView.width <= 0 || blurView.height <= 0) return
    val controller = blurView.setupWith(blurTarget)
        .setBlurAutoUpdate(true)
        .setBlurRadius(blurRadiusPx)
    decorBackground?.let { controller.setFrameClearDrawable(it) }
    blurView.setOverlayColor(overlayColor.toArgb())
    blurView.tag = blurConfiguredMarker
}

/** 柔影外扩留白（须 ≥ [FloatingIslandShadowBlur]）。 */
val FloatingIslandShadowSpread = 16.dp

/** 高斯模糊半径（与卡片同大的色块经此模糊后形成四向均匀柔影）。 */
val FloatingIslandShadowBlur = 12.dp

/** 卡片外为柔影预留的额外高度。 */
val FloatingIslandShadowVerticalExtra = FloatingIslandShadowSpread * 2 + FloatingIslandShadowBlur

/**
 * 与卡片同尺寸的半透明色块 + [BlurEffect]：模糊向外晕开，四边均匀，避免径向「中间浓、左右淡」。
 * 须与云母卡片同位置叠放，且位于其下方。
 */
@Composable
fun FloatingIslandShadowHalo(
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!supportsFloatingIslandRenderEffect()) {
        return
    }
    val density = LocalDensity.current
    val blurPx = with(density) { FloatingIslandShadowBlur.toPx() }
    val fillAlpha = if (isDark) 0.26f else 0.13f
    Box(
        modifier
            .offset(y = 2.dp)
            .graphicsLayer {
                clip = false
                renderEffect = BlurEffect(
                    radiusX = blurPx,
                    radiusY = blurPx,
                    edgeTreatment = TileMode.Decal,
                )
            }
            .background(Color.Black.copy(alpha = fillAlpha)),
    )
}

@Composable
fun MicaMaterialBackdrop(
    modifier: Modifier = Modifier,
    blurRadius: Dp,
    overlayColor: Color,
) {
    val blurTarget = LocalMicaBlurTarget.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val radiusPx = with(density) { blurRadius.toPx() }
    val listBackdropColor = rememberFloatingIslandListBackdropColor()
    if (!supportsFloatingIslandRenderEffect() || blurTarget == null) {
        Box(modifier.background(listBackdropColor))
        return
    }

    val decorBackground = (context as? Activity)?.window?.decorView?.background

    AndroidView(
        modifier = modifier.background(overlayColor),
        factory = { ctx ->
            BlurView(ctx).apply {
                doOnAttach {
                    doOnLayout {
                        configureMicaBlurViewOnce(
                            blurView = this,
                            blurTarget = blurTarget,
                            blurRadiusPx = radiusPx,
                            overlayColor = overlayColor,
                            decorBackground = decorBackground,
                        )
                    }
                }
            }
        },
        update = { blurView ->
            if (blurView.isAttachedToWindow) {
                configureMicaBlurViewOnce(
                    blurView = blurView,
                    blurTarget = blurTarget,
                    blurRadiusPx = radiusPx,
                    overlayColor = overlayColor,
                    decorBackground = decorBackground,
                )
            }
            blurView.setBlurRadius(radiusPx)
            blurView.setOverlayColor(overlayColor.toArgb())
        },
    )
}
