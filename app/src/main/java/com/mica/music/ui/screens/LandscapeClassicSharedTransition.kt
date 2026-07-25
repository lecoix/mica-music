package com.mica.music.ui.screens

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mica.music.ui.motion.MicaMotion

/** 横屏经典 LIST：播放页 ↔ 歌词页共享元素 key。 */
internal object LandscapeClassicSharedKeys {
    const val Cover = "landscapeClassicCover"
    const val Title = "landscapeClassicTitle"
    const val Chrome = "landscapeClassicChrome"
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberLandscapeClassicBoundsTransform(motionEnabled: Boolean): BoundsTransform {
    return remember(motionEnabled) {
        BoundsTransform { _, _ ->
            tween(
                durationMillis = if (motionEnabled) MicaMotion.DurationLongMs else 0,
                easing = MicaMotion.Easing,
            )
        }
    }
}
