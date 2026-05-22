package com.mica.music.ui.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** 全局动效：克制、短促；与系统「减少动态效果」联动。 */
object MicaMotion {
    const val DurationShortMs = 200
    const val DurationMediumMs = 320
    const val DurationLongMs = 400

    val Easing = FastOutSlowInEasing

    val LocalEnabled = staticCompositionLocalOf { true }

    fun isReduceMotion(context: Context): Boolean {
        val resolver = context.contentResolver
        val transition = Settings.Global.getFloat(
            resolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        )
        val animator = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        return transition == 0f || animator == 0f
    }

    fun tweenFloat(enabled: Boolean, durationMs: Int = DurationMediumMs): FiniteAnimationSpec<Float> =
        if (enabled) tween(durationMillis = durationMs, easing = Easing) else tween(0)

    fun tweenDp(enabled: Boolean, durationMs: Int = DurationLongMs): FiniteAnimationSpec<Dp> =
        if (enabled) tween(durationMillis = durationMs, easing = Easing) else tween(0)

    fun tweenColor(enabled: Boolean, durationMs: Int = DurationMediumMs): AnimationSpec<Color> =
        if (enabled) tween(durationMillis = durationMs, easing = Easing) else tween(0)

    fun tweenIntOffset(enabled: Boolean, durationMs: Int = DurationMediumMs): FiniteAnimationSpec<IntOffset> =
        if (enabled) tween(durationMillis = durationMs, easing = Easing) else tween(0)

    fun tweenIntSize(enabled: Boolean, durationMs: Int = DurationLongMs): FiniteAnimationSpec<IntSize> =
        if (enabled) tween(durationMillis = durationMs, easing = Easing) else tween(0)

    /**
     * 前进：新页从右侧推入，旧页向左滑出。
     * 返回：新页从左侧滑入，旧页向右滑出。
     * 同级：[depth] 不变时仅交叉淡入淡出。
     */
    fun <T> directionalPaneTransition(
        enabled: Boolean,
        depth: (T) -> Int,
    ): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
        if (!enabled) {
            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
        } else {
            val fade = tween<Float>(DurationMediumMs, easing = Easing)
            val slide = tweenIntOffset(enabled, DurationMediumMs)
            val forward = depth(targetState) > depth(initialState)
            val back = depth(targetState) < depth(initialState)
            val transform = when {
                forward ->
                    (fadeIn(fade) + slideInHorizontally(slide) { fullWidth -> fullWidth })
                        .togetherWith(
                            fadeOut(fade) + slideOutHorizontally(slide) { fullWidth -> -fullWidth },
                        )
                back ->
                    (fadeIn(fade) + slideInHorizontally(slide) { fullWidth -> -fullWidth })
                        .togetherWith(
                            fadeOut(fade) + slideOutHorizontally(slide) { fullWidth -> fullWidth },
                        )
                else -> fadeIn(fade) togetherWith fadeOut(fade)
            }
            transform.using(SizeTransform(clip = false))
        }
    }

    fun <T> paneTransition(enabled: Boolean): AnimatedContentTransitionScope<T>.() -> ContentTransform =
        directionalPaneTransition(enabled) { 0 }

    /**
     * 进入/退出搜索：与前进/返回同向的轻量横向推入，任意分区均可一致打开搜索。
     */
    fun <T> homePaneWithSearchTransition(
        enabled: Boolean,
        depth: (T) -> Int,
        isSearch: (T) -> Boolean,
    ): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
        if (!enabled) {
            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
        } else {
            val enteringSearch = isSearch(targetState) && !isSearch(initialState)
            val leavingSearch = isSearch(initialState) && !isSearch(targetState)
            if (enteringSearch || leavingSearch) {
                val fade = tween<Float>(DurationMediumMs, easing = Easing)
                val slide = tweenIntOffset(enabled, DurationMediumMs)
                val transform = if (enteringSearch) {
                    (fadeIn(fade) + slideInHorizontally(slide) { fullWidth -> fullWidth })
                        .togetherWith(
                            fadeOut(fade) + slideOutHorizontally(slide) { fullWidth -> -fullWidth },
                        )
                } else {
                    (fadeIn(fade) + slideInHorizontally(slide) { fullWidth -> -fullWidth })
                        .togetherWith(
                            fadeOut(fade) + slideOutHorizontally(slide) { fullWidth -> fullWidth },
                        )
                }
                transform.using(SizeTransform(clip = false))
            } else {
                directionalPaneTransition(enabled, depth).invoke(this)
            }
        }
    }

    fun drawerPushSpec(enabled: Boolean): FiniteAnimationSpec<Float> =
        tweenFloat(enabled, DurationMediumMs)

    fun topBarSearchTransition(enabled: Boolean): AnimatedContentTransitionScope<Boolean>.() -> ContentTransform = {
        if (!enabled) {
            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
        } else {
            val fade = tween<Float>(DurationShortMs, easing = Easing)
            val slide = tweenIntOffset(enabled, DurationShortMs)
            if (targetState) {
                (fadeIn(fade) + slideInHorizontally(slide) { width -> width / 4 })
                    .togetherWith(fadeOut(fade) + slideOutHorizontally(slide) { width -> -width / 4 })
            } else {
                (fadeIn(fade) + slideInHorizontally(slide) { width -> -width / 4 })
                    .togetherWith(fadeOut(fade) + slideOutHorizontally(slide) { width -> width / 4 })
            }
        }
    }

    fun <T> verticalScreenTransition(enabled: Boolean): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
        if (!enabled) {
            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
        } else {
            val spec = tween<Float>(DurationMediumMs, easing = Easing)
            fadeIn(spec) togetherWith fadeOut(spec)
        }
    }

    /** 播放页歌词切句：下一句时整块自下而上滚入，上一句则反向。 */
    fun playerLyricIndexTransition(enabled: Boolean): AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
        if (!enabled) {
            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
        } else {
            val slide = tweenIntOffset(enabled, DurationLongMs)
            val transform = if (targetState > initialState) {
                slideInVertically(slide) { fullHeight -> fullHeight }
                    .togetherWith(slideOutVertically(slide) { fullHeight -> -fullHeight })
            } else {
                slideInVertically(slide) { fullHeight -> -fullHeight }
                    .togetherWith(slideOutVertically(slide) { fullHeight -> fullHeight })
            }
            transform.using(SizeTransform(clip = false))
        }
    }
}

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { MicaMotion.isReduceMotion(context) }
}

@Composable
fun rememberMicaMotionEnabled(): Boolean {
    val reduce = rememberReduceMotion()
    return !reduce && MicaMotion.LocalEnabled.current
}
