package com.mica.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mica.music.ui.motion.rememberMicaMotionEnabled

@Composable
fun rememberAnimatedHifiColors(target: HifiColors): HifiColors {
    val motion = rememberMicaMotionEnabled()
    val spec: AnimationSpec<Color> = remember(motion) {
        if (motion) {
            androidx.compose.animation.core.tween(
                durationMillis = com.mica.music.ui.motion.MicaMotion.DurationMediumMs,
                easing = com.mica.music.ui.motion.MicaMotion.Easing,
            )
        } else {
            androidx.compose.animation.core.tween(0)
        }
    }
    if (!motion) return target
    return HifiColors(
        textPrimary = animateColorAsState(target.textPrimary, spec, label = "textPrimary").value,
        textSecondary = animateColorAsState(target.textSecondary, spec, label = "textSecondary").value,
        textTertiary = animateColorAsState(target.textTertiary, spec, label = "textTertiary").value,
        divider = animateColorAsState(target.divider, spec, label = "divider").value,
        surfaceGlass = animateColorAsState(target.surfaceGlass, spec, label = "surfaceGlass").value,
        surfaceCard = animateColorAsState(target.surfaceCard, spec, label = "surfaceCard").value,
        accent = animateColorAsState(target.accent, spec, label = "accent").value,
        hiRes = animateColorAsState(target.hiRes, spec, label = "hiRes").value,
        like = animateColorAsState(target.like, spec, label = "like").value,
        isDark = target.isDark,
    )
}

/** 根布局云母渐变：浅/深与预设切换时交叉淡入。 */
@Composable
fun AnimatedMicaAppBackground(modifier: Modifier = Modifier) {
    val preset = LocalMicaBackgroundPreset.current
    val isDark = MicaTheme.colors.isDark
    val motion = rememberMicaMotionEnabled()
    val spec: AnimationSpec<Color> = remember(motion) {
        if (motion) {
            androidx.compose.animation.core.tween(
                durationMillis = com.mica.music.ui.motion.MicaMotion.DurationMediumMs,
                easing = com.mica.music.ui.motion.MicaMotion.Easing,
            )
        } else {
            androidx.compose.animation.core.tween(0)
        }
    }
    val (targetStart, targetEnd) = preset.gradientColors(isDark)
    val start = animateColorAsState(targetStart, spec, label = "micaGradStart").value
    val end = animateColorAsState(targetEnd, spec, label = "micaGradEnd").value
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(start, end))),
    )
}
