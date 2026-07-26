package com.mica.music.ui.screens.player.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.os.Trace
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.data.Song
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.screens.player.CoverFlowDragCommitFraction
import com.mica.music.ui.screens.player.CoverFlowMath
import com.mica.music.ui.screens.player.CoverFlowRails
import com.mica.music.util.TrackSwitchPerformance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.floor

private data class LaneDrawState(
    val laneIndex: Int,
    val railOffset: Float,
    val song: Song,
    val tx: Float,
    val rotationY: Float,
    val bitmap: Bitmap?,
    val slotAlphaByte: Int,
    val drawScale: Float,
    val scalePivotX: Float,
    val zIndex: Float,
)

/**
 * 封面流：Android View + Canvas 单遍绘制（七轨、倒影、复古 3D）。
 */
@SuppressLint("ViewConstructor")
internal class CoverFlowCarouselView(context: Context) : View(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scratchPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val camera = Camera()
    private val matrix = Matrix()
    private val coverRect = RectF()
    private val reflectionRect = RectF()
    private val reflectionDstRect = RectF()
    private val bitmapSrcRect = Rect()
    private val reflectionSrcRect = Rect()
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val laneStates =
        ArrayList<LaneDrawState>(CoverFlowMath.LandscapeRetroLaneWindowRadius * 2 + 1)

    private var queue: List<Song> = emptyList()
    private var logicalCenter: Int = 0
    private var stripFraction: Float = 0f
    private var coverFlowMode: PlayerCoverFlowMode = PlayerCoverFlowMode.PAUSE_FOLD
    private var laneWindowRadius: Int = CoverFlowMath.LaneWindowRadius
    private var foldProgress: Float = 1f
    private var screenWidthPx: Float = 1f
    private var coverWidthPx: Float = 1f
    private var coverHeightPx: Float = 1f
    private var coverDecodeTarget = CoverDecodeTarget.fromPixels(1f, 1f)
    private var artworkLoadGeneration: Long = 0L
    private var coverStartPaddingPx: Float = 0f
    private var reflectionGapPx: Float = 0f
    private var cameraDistancePx: Float = 48f
    private var motionEnabled: Boolean = true
    private var gesturesEnabled: Boolean = true
    private var fallbackColorArgb: Int = 0xFF000000.toInt()
    private var lastReportedIndex: Int = -1
    private var pendingHostIndex: Int? = null
    private var lastCenterAspectRatio: Float = 0f
    private var pendingPlayQueueIndex: Int? = null
    private var awaitingCommittedPlayIndex: Int? = null
    private var lastDispatchedPlayIndex: Int? = null
    private var lastSupersededHostIndex: Int? = null
    private var hostIndexGuardUntilMs: Long = 0L
    private var visualCommitGeneration: Int = 0

    private var trackAnimator: ValueAnimator? = null
    private var settleAnimator: ValueAnimator? = null
    private var dragStartX: Float = 0f
    private var dragAccumPx: Float = 0f
    private var dragging: Boolean = false

    private val bitmapByUri = mutableMapOf<String, Bitmap>()
    private val pendingLoads = mutableSetOf<String>()

    /** Perf-only callers use this to verify that the local artwork window stays bounded. */
    internal fun diagnosticArtworkState(): ArtworkRetentionDiagnostic = ArtworkRetentionDiagnostic(
        retainedBitmapCount = bitmapByUri.size,
        pendingLoadCount = pendingLoads.size,
        queueSize = queue.size,
    )

    var onPlayQueueIndex: ((Int) -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onCoverLongPress: (() -> Unit)? = null
    var onCenterAspectRatio: ((Float) -> Unit)? = null
    var onMotionActiveChanged: ((Boolean) -> Unit)? = null

    private var motionActive: Boolean = false
    private var lastAnimatorCallbackNs: Long = 0L

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val lane = hitLaneOffset(e.x) ?: return
                if (abs(lane.toFloat() - stripFraction) < 0.12f) {
                    onCoverLongPress?.invoke()
                }
            }
        },
    )

    init {
        setWillNotDraw(false)
    }

    fun setGesturesEnabled(enabled: Boolean) {
        gesturesEnabled = enabled
    }

    fun setScreenWidthPx(px: Float) {
        if (screenWidthPx != px) {
            screenWidthPx = px.coerceAtLeast(1f)
            invalidateFor("screen-size")
        }
    }

    fun setLaneWindowRadius(radius: Int) {
        val bounded = radius.coerceIn(
            CoverFlowMath.LaneWindowRadius,
            CoverFlowMath.LandscapeRetroLaneWindowRadius,
        )
        if (laneWindowRadius != bounded) {
            laneWindowRadius = bounded
            preloadWindow()
            invalidateFor("lane-window")
        }
    }

    fun setCoverSizePx(widthPx: Float, heightPx: Float) {
        val w = widthPx.coerceAtLeast(1f)
        val h = heightPx.coerceAtLeast(1f)
        if (coverWidthPx != w || coverHeightPx != h) {
            val oldAspect = coverWidthPx / coverHeightPx.coerceAtLeast(1f)
            val newAspect = w / h
            coverWidthPx = w
            coverHeightPx = h
            if (abs(oldAspect - newAspect) > 0.001f) {
                scheduleReflectionRebakeForWindow()
            }
            invalidateFor("cover-size")
        }
    }

    fun setCoverDecodeTarget(target: CoverDecodeTarget) {
        if (coverDecodeTarget != target) {
            coverDecodeTarget = target
            artworkLoadGeneration++
            CoverFlowReflectionBake.clear()
            pruneBitmapWindow()
            invalidateFor("cover-decode-target")
        }
    }

    fun setCoverStartPaddingPx(px: Float) {
        if (coverStartPaddingPx != px) {
            coverStartPaddingPx = px.coerceAtLeast(0f)
            invalidateFor("cover-padding")
        }
    }

    fun setReflectionGapPx(px: Float) {
        if (reflectionGapPx != px) {
            reflectionGapPx = px
            invalidateFor("reflection-gap")
        }
    }

    fun setCameraDistancePx(px: Float) {
        if (cameraDistancePx != px) {
            cameraDistancePx = px.coerceAtLeast(1f)
            invalidateFor("camera-distance")
        }
    }

    fun setFoldProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        if (foldProgress != p) {
            foldProgress = p
            invalidateFor("fold-progress")
        }
    }

    fun setCoverFlowMode(mode: PlayerCoverFlowMode) {
        if (coverFlowMode != mode) {
            coverFlowMode = mode
            invalidateFor("cover-mode")
        }
    }

    fun setMotionEnabled(enabled: Boolean) {
        motionEnabled = enabled
    }

    fun setFallbackColor(argb: Int) {
        if (fallbackColorArgb == argb) return
        fallbackColorArgb = argb
        invalidateFor("fallback-color")
    }

    fun applyHostUpdate(songs: List<Song>, index: Int, stageActive: Boolean) {
        if (!shouldDeferHostIndexUpdate()) {
            updateQueue(songs)
        }
        if (!stageActive) {
            pendingHostIndex = null
            pendingPlayQueueIndex = null
            awaitingCommittedPlayIndex = null
            lastDispatchedPlayIndex = null
            lastSupersededHostIndex = null
            hostIndexGuardUntilMs = 0L
            resetToIndex(index)
            return
        }
        pendingPlayQueueIndex?.let { pendingPlay ->
            if (index != pendingPlay && index != lastSupersededHostIndex) {
                pendingPlayQueueIndex = null
                pendingHostIndex = null
                awaitingCommittedPlayIndex = null
                lastDispatchedPlayIndex = null
                lastSupersededHostIndex = null
                hostIndexGuardUntilMs = 0L
                updateQueue(songs)
                resetToIndex(index)
                TrackSwitchPerformance.mark(
                    "coverflow-pending-superseded",
                    "pending=$pendingPlay host=$index",
                )
                return
            }
        }
        lastDispatchedPlayIndex?.let { latestTarget ->
            if (index != latestTarget && SystemClock.uptimeMillis() < hostIndexGuardUntilMs) {
                if (awaitingCommittedPlayIndex == null || index == lastSupersededHostIndex) {
                    return
                }
                // A different host index during the guard window means an external command
                // (for example a button skip) superseded the visual-first gesture commit.
                pendingHostIndex = null
                awaitingCommittedPlayIndex = null
                lastDispatchedPlayIndex = null
                lastSupersededHostIndex = null
                hostIndexGuardUntilMs = 0L
                updateQueue(songs)
                resetToIndex(index)
                return
            }
        }
        awaitingCommittedPlayIndex?.let { target ->
            if (index == target) {
                awaitingCommittedPlayIndex = null
            } else if (target in songs.indices) {
                return
            } else {
                awaitingCommittedPlayIndex = null
            }
        }
        if (shouldDeferHostIndexUpdate()) {
            pendingHostIndex = index
            return
        }
        updateCurrentIndex(index)
    }

    /** 拖拽、strip/track 动画或待派发切歌完成前，推迟 Host 索引同步。 */
    private fun shouldDeferHostIndexUpdate(): Boolean =
        dragging || trackAnimator != null || settleAnimator != null || pendingPlayQueueIndex != null

    private fun flushPendingHostIndex() {
        val pending = pendingHostIndex ?: return
        pendingHostIndex = null
        if (pending == logicalCenter && abs(stripFraction) < 0.0001f) return
        updateCurrentIndex(pending)
    }

    fun updateQueue(songs: List<Song>) {
        val compareStartedNs = SystemClock.elapsedRealtimeNanos()
        val sameRef = queue === songs
        val same = sameRef || sameVisualQueue(queue, songs)
        TrackSwitchPerformance.recordCoverQueueCompare(
            durationNs = SystemClock.elapsedRealtimeNanos() - compareStartedNs,
            queueSize = songs.size,
            skippedBySameRef = sameRef,
        )
        if (same) return
        queue = songs
        preloadWindow()
        invalidateFor("queue")
    }

    private fun sameVisualQueue(current: List<Song>, incoming: List<Song>): Boolean {
        if (current === incoming) return true
        if (current.size != incoming.size) return false
        return current.indices.all { index ->
            val old = current[index]
            val new = incoming[index]
            old.id == new.id &&
                old.albumArtUri == new.albumArtUri &&
                old.coverColorArgb == new.coverColorArgb
        }
    }

    fun updateCurrentIndex(index: Int) {
        updateCurrentIndex(index, replaceRunningTrack = false, fromUserGesture = false)
    }

    private fun updateCurrentIndex(
        index: Int,
        replaceRunningTrack: Boolean,
        fromUserGesture: Boolean,
    ) {
        if (trackAnimator != null && !replaceRunningTrack) {
            pendingHostIndex = index
            return
        }
        if (lastReportedIndex < 0) {
            logicalCenter = index
            stripFraction = 0f
            lastReportedIndex = index
            preloadWindow()
            invalidateFor("initial-index")
            return
        }
        if (logicalCenter == index && abs(stripFraction) < 0.0001f) {
            lastReportedIndex = index
            return
        }
        val delta = index - logicalCenter
        if (delta == 0) return
        TrackSwitchPerformance.mark(
            "cover-index",
            "logical=$logicalCenter target=$index delta=$delta mode=${coverFlowMode.name}",
        )
        cancelAnimators()
        val generation = nextVisualCommitGeneration()
        val fromCenter = logicalCenter
        val endVisual = index.toFloat()
        val startVisual = CoverFlowRails.clampTrackChangeStartVisual(
            fromLogicalCenter = fromCenter,
            startVisual = fromCenter + stripFraction,
            endVisual = endVisual,
            signedDelta = delta,
        )
        val visualDistance = abs(endVisual - startVisual)
        val shouldAnimate = motionEnabled && when {
            fromUserGesture -> visualDistance > 0.0001f
            else -> abs(delta) == 1 && visualDistance > 0.0001f
        }
        if (!shouldAnimate) {
            commitTrackIndex(index)
            setMotionActive(false)
            return
        }
        val baseDuration = if (coverFlowMode == PlayerCoverFlowMode.RETRO_3D) {
            MicaMotion.DurationLongMs
        } else {
            MicaMotion.DurationMediumMs
        }
        val duration = if (fromUserGesture && visualDistance > 1f) {
            (baseDuration * visualDistance.coerceAtMost(2f)).toInt()
        } else {
            baseDuration
        }
        if (abs(startVisual - endVisual) < 0.0001f) {
            commitTrackIndex(index)
            setMotionActive(false)
            return
        }
        trackAnimator = ValueAnimator.ofFloat(startVisual, endVisual).apply {
            this.duration = if (motionEnabled) duration.toLong() else 0L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                recordAnimatorCallback()
                val v = anim.animatedValue as Float
                if (fromUserGesture && visualDistance > 1f) {
                    // 多步切歌：链式单步推进，确保 stripFraction 始终落在 [-1, 1]，
                    // 使固定 7-lane 窗口始终能覆盖视觉中心，避免出现“后面槽位空白”。
                    val forward = endVisual >= startVisual
                    val stepCenter = if (forward) floor(v) else ceil(v)
                    val clampedCenter = stepCenter.toInt().coerceIn(0, queue.lastIndex.coerceAtLeast(0))
                    if (clampedCenter != logicalCenter) {
                        logicalCenter = clampedCenter
                        lastReportedIndex = clampedCenter
                        preloadWindow()
                    }
                    stripFraction = (v - logicalCenter.toFloat()).coerceIn(-1f, 1f)
                } else {
                    stripFraction = v - fromCenter
                }
                invalidateFor("track-animator")
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    lastAnimatorCallbackNs = 0L
                    setMotionActive(true)
                    TrackSwitchPerformance.mark("cover-animation-start", "duration=$duration")
                }
                override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (generation != visualCommitGeneration) return
                    commitTrackIndex(index)
                    trackAnimator = null
                    setMotionActive(false)
                    TrackSwitchPerformance.mark("cover-animation-end", "index=$index")
                    flushPendingPlayQueueIndex()
                    flushPendingHostIndex()
                }
            })
            start()
        }
    }

    fun resetToIndex(index: Int) {
        cancelAnimators()
        commitTrackIndex(index)
        setMotionActive(false)
    }

    private fun commitTrackIndex(index: Int) {
        logicalCenter = index
        stripFraction = 0f
        lastReportedIndex = index
        preloadWindow()
        invalidateFor("commit-index")
    }

    override fun onDetachedFromWindow() {
        cancelAnimators()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        var stateBuildNs = 0L
        var laneDrawNs = 0L
        var laneCount = 0
        Trace.beginSection("MicaCoverFlow.onDraw")
        try {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val slotW = if (coverWidthPx > 0f) coverWidthPx else width.toFloat()
            val slotH = if (coverHeightPx > 0f) coverHeightPx else height.toFloat()
            val cx = coverCenterX()
            val cy = contentCenterY(slotH)
            val stateBuildStartedNs = SystemClock.elapsedRealtimeNanos()
            laneStates.clear()
            for (laneIndex in -laneWindowRadius..laneWindowRadius) {
                buildLaneState(laneIndex, slotW)?.let(laneStates::add)
            }
            laneStates.sortBy { it.zIndex }
            stateBuildNs = SystemClock.elapsedRealtimeNanos() - stateBuildStartedNs
            laneCount = laneStates.size
            val laneDrawStartedNs = SystemClock.elapsedRealtimeNanos()
            for (state in laneStates) {
                drawLane(canvas, state, cx, cy, slotW, slotH)
            }
            laneDrawNs = SystemClock.elapsedRealtimeNanos() - laneDrawStartedNs
            laneStates.firstOrNull { abs(it.railOffset) < 0.08f }?.let { center ->
                val ratio = if (center.bitmap != null && center.bitmap.height > 0) {
                    center.bitmap.width.toFloat() / center.bitmap.height
                } else {
                    1f
                }
                if (abs(ratio - lastCenterAspectRatio) > 0.001f) {
                    lastCenterAspectRatio = ratio
                    onCenterAspectRatio?.invoke(ratio)
                }
            }
        } finally {
            Trace.endSection()
            TrackSwitchPerformance.recordCoverDraw(
                durationNs = SystemClock.elapsedRealtimeNanos() - startedNs,
                stateBuildNs = stateBuildNs,
                laneDrawNs = laneDrawNs,
                laneCount = laneCount,
                reflection = reflectionEnabled(),
            )
        }
    }

    private fun buildLaneState(
        laneIndex: Int,
        slotW: Float,
    ): LaneDrawState? {
        val railOffset = CoverFlowRails.railOffset(laneIndex, stripFraction)
        if (abs(railOffset) > laneWindowRadius.toFloat()) return null
        val song = queue.getOrNull(logicalCenter + laneIndex) ?: return null
        val slotAlpha = CoverFlowRails.alpha(railOffset, foldProgress, coverFlowMode)
        if (slotAlpha < 0.01f) return null
        val bitmap = bitmapFor(
            uri = song.albumArtUri,
            reflectionEligible = CoverFlowMath.shouldRenderReflection(laneIndex, laneWindowRadius),
        )
        // Side lanes with no artwork would paint coverColorArgb placeholders; those flash
        // outside the center when decode targets change during the lyrics fold transition.
        if (bitmap == null && abs(railOffset) >= 0.05f) return null
        return LaneDrawState(
            laneIndex = laneIndex,
            railOffset = railOffset,
            song = song,
            tx = CoverFlowRails.translationPx(
                railOffset,
                layoutWidthPx(),
                coverFlowMode,
                expandedRetro = usesExpandedRetroRails(),
            ),
            rotationY = CoverFlowRails.rotationY(
                railOffset,
                coverFlowMode,
                expandedRetro = usesExpandedRetroRails(),
            ),
            bitmap = bitmap,
            slotAlphaByte = (slotAlpha * 255).toInt().coerceIn(0, 255),
            drawScale = CoverFlowRails.drawScale(
                railOffset,
                coverFlowMode,
                foldProgress,
                expandedRetro = usesExpandedRetroRails(),
            ),
            scalePivotX = CoverFlowRails.pivotX(railOffset, slotW, coverFlowMode),
            zIndex = CoverFlowRails.zIndex(railOffset, coverFlowMode),
        )
    }

    /** 封面与倒影同一变换栈绘制，保证复古倾斜衔接且 z 序正确（远→近）。 */
    private fun drawLane(
        canvas: Canvas,
        state: LaneDrawState,
        cx: Float,
        cy: Float,
        slotW: Float,
        slotH: Float,
    ) {
        paint.alpha = state.slotAlphaByte
        canvas.save()
        canvas.translate(cx + state.tx, cy)
        canvas.translate(state.scalePivotX, 0f)
        applyRetroTransform(canvas, state.rotationY, state.scalePivotX)
        canvas.scale(state.drawScale, state.drawScale)
        canvas.translate(-state.scalePivotX, 0f)
        coverRect.set(-slotW * 0.5f, -slotH * 0.5f, slotW * 0.5f, slotH * 0.5f)
        val bitmap = state.bitmap
        if (bitmap != null) {
            centerCropSrc(bitmap, slotW, slotH, bitmapSrcRect)
            canvas.drawBitmap(bitmap, bitmapSrcRect, coverRect, paint)
        } else if (abs(state.railOffset) < 0.05f) {
            // Center-only placeholder while the first decode is in flight.
            paint.color = state.song.coverColorArgb
            canvas.drawRect(coverRect, paint)
            paint.color = fallbackColorArgb
        } else {
            canvas.restore()
            return
        }
        if (
            reflectionEnabled() &&
            CoverFlowMath.shouldRenderReflection(state.laneIndex, laneWindowRadius)
        ) {
            drawReflection(
                canvas = canvas,
                albumArtUri = state.song.albumArtUri,
                bitmap = bitmap,
                song = state.song,
                slotW = slotW,
                slotH = slotH,
                slotAlphaByte = state.slotAlphaByte,
            )
        }
        canvas.restore()
    }

    private fun applyRetroTransform(
        canvas: Canvas,
        rotationY: Float,
        pivotX: Float,
    ) {
        if (coverFlowMode != PlayerCoverFlowMode.RETRO_3D || abs(rotationY) < 0.01f) return
        camera.save()
        camera.setLocation(0f, 0f, -cameraDistancePx)
        camera.rotateY(rotationY)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-pivotX, 0f)
        matrix.postTranslate(pivotX, 0f)
        canvas.concat(matrix)
    }

    private fun reflectionEnabled(): Boolean =
        coverFlowMode == PlayerCoverFlowMode.PAUSE_FOLD ||
            coverFlowMode == PlayerCoverFlowMode.RETRO_3D

    private fun drawReflection(
        canvas: Canvas,
        albumArtUri: String?,
        bitmap: Bitmap?,
        song: Song,
        slotW: Float,
        slotH: Float,
        slotAlphaByte: Int,
    ) {
        val reflectionAlphaMultiplier = CoverFlowMath.reflectionAlphaMultiplier(foldProgress)
        if (reflectionAlphaMultiplier <= 0f) return
        val reflectionSlotAlphaByte = (slotAlphaByte * reflectionAlphaMultiplier)
            .toInt()
            .coerceIn(0, 255)
        val reflH = slotH * CoverFlowMath.ReflectionHeightFraction
        val gap = reflectionGapPx
        val coverBottom = slotH * 0.5f
        val top = coverBottom + gap
        val bottom = top + reflH
        reflectionRect.set(-slotW * 0.5f, top, slotW * 0.5f, bottom)
        val combinedAlpha = ((CoverFlowMath.ReflectionAlpha * reflectionSlotAlphaByte / 255f) * 255f)
            .toInt()
            .coerceIn(0, 255)
        canvas.save()
        canvas.clipRect(reflectionRect)
        val slotAspect = slotW / slotH.coerceAtLeast(1f)
        val bakedReflection = if (
            CoverFlowReflectionBake.ENABLED &&
            bitmap != null &&
            !albumArtUri.isNullOrBlank()
        ) {
            CoverFlowReflectionBake.cached(albumArtUri, slotAspect)
        } else {
            null
        }
        if (bakedReflection != null) {
            // ReflectionAlpha 已烘焙进位图；此处只应用槽位透明度。
            paint.alpha = reflectionSlotAlphaByte
            canvas.drawBitmap(bakedReflection, null, reflectionRect, paint)
            paint.alpha = 255
            canvas.restore()
            return
        }
        if (bitmap != null) {
            centerCropSrc(bitmap, slotW, slotH, reflectionSrcRect)
            val srcSliceH = (reflectionSrcRect.height() * CoverFlowMath.ReflectionHeightFraction)
                .toInt()
                .coerceIn(1, reflectionSrcRect.height())
            reflectionSrcRect.top = reflectionSrcRect.bottom - srcSliceH
            layerPaint.alpha = combinedAlpha
            val layerId = canvas.saveLayer(
                reflectionRect.left,
                reflectionRect.top,
                reflectionRect.right,
                reflectionRect.bottom,
                layerPaint,
            )
            scratchPaint.shader = null
            scratchPaint.xfermode = null
            scratchPaint.alpha = 255
            canvas.save()
            canvas.translate(0f, bottom)
            canvas.scale(1f, -1f)
            canvas.drawBitmap(
                bitmap,
                reflectionSrcRect,
                reflectionDstRect.apply { set(-slotW * 0.5f, 0f, slotW * 0.5f, reflH) },
                scratchPaint,
            )
            canvas.restore()
            gradientPaint.shader = LinearGradient(
                0f,
                top,
                0f,
                bottom,
                intArrayOf(
                    0xFFFFFFFF.toInt(),
                    0x8CFFFFFF.toInt(),
                    0x00FFFFFF,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            gradientPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            gradientPaint.alpha = 255
            canvas.drawRect(reflectionRect, gradientPaint)
            gradientPaint.xfermode = null
            gradientPaint.shader = null
            canvas.restoreToCount(layerId)
        } else {
            val baseAlpha = CoverFlowMath.ReflectionAlpha * reflectionSlotAlphaByte / 255f
            gradientPaint.shader = LinearGradient(
                0f,
                top,
                0f,
                bottom,
                intArrayOf(
                    applyAlpha(song.coverColorArgb, baseAlpha),
                    applyAlpha(song.coverColorArgb, baseAlpha * 0.55f),
                    0x00FFFFFF,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(reflectionRect, gradientPaint)
            gradientPaint.shader = null
        }
        canvas.restore()
    }

    private fun applyAlpha(argb: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return argb and 0x00FFFFFF or (a shl 24)
    }

    /** 封面视觉中心 X（含 startPadding，与 Compose 封面区对齐）。 */
    private fun coverCenterX(): Float =
        if (coverWidthPx > 0f) {
            coverStartPaddingPx + coverWidthPx * 0.5f
        } else {
            width * 0.5f
        }

    /** 槽位步进以封面实际宽度为基准（与 Compose 槽位同坐标系），避免用屏宽导致间距被拉大。 */
    private fun layoutWidthPx(): Float = when {
        coverWidthPx > 1f -> coverWidthPx
        width > 0 -> width.toFloat()
        else -> screenWidthPx.coerceAtLeast(1f)
    }

    private fun laneStepFraction(): Float = when (coverFlowMode) {
        PlayerCoverFlowMode.PAUSE_FOLD -> CoverFlowRails.PauseFoldStep
        PlayerCoverFlowMode.RETRO_3D -> if (usesExpandedRetroRails()) {
            CoverFlowRails.LandscapeRetroFirstStep
        } else {
            CoverFlowRails.RetroFirstStep
        }
        else -> CoverFlowMath.LaneStepFraction
    }

    private fun usesExpandedRetroRails(): Boolean =
        coverFlowMode == PlayerCoverFlowMode.RETRO_3D &&
            laneWindowRadius > CoverFlowMath.LaneWindowRadius

    /** 有倒影时封面顶对齐，下方留给倒影区。 */
    private fun contentCenterY(slotH: Float): Float =
        if (reflectionEnabled()) {
            slotH * 0.5f
        } else {
            height * 0.5f
        }

    private fun centerCropSrc(bitmap: Bitmap, dstW: Float, dstH: Float, out: Rect) {
        val bw = bitmap.width
        val bh = bitmap.height
        if (bw <= 0 || bh <= 0) {
            out.set(0, 0, bw, bh)
            return
        }
        val dstRatio = dstW / dstH
        val srcRatio = bw.toFloat() / bh
        if (srcRatio > dstRatio) {
            val cropW = (bh * dstRatio).toInt().coerceAtMost(bw)
            val x = (bw - cropW) / 2
            out.set(x, 0, x + cropW, bh)
        } else {
            val cropH = (bw / dstRatio).toInt().coerceAtMost(bh)
            val y = (bh - cropH) / 2
            out.set(0, y, bw, y + cropH)
        }
    }

    private fun bitmapFor(
        uri: String?,
        reflectionEligible: Boolean,
    ): Bitmap? {
        if (uri.isNullOrBlank()) return null
        val bitmapKey = coverDecodeTarget.memoryCacheKey(uri)
        bitmapByUri[bitmapKey]?.let { cached ->
            if (!CoverFlowBitmaps.isPollutedThumbnail(cached)) return cached
            bitmapByUri.remove(bitmapKey)
        }
        CoverFlowBitmaps.memoryBitmap(uri, coverDecodeTarget)?.let { cached ->
            if (!CoverFlowBitmaps.isPollutedThumbnail(cached)) {
                bitmapByUri[bitmapKey] = cached
                return cached
            }
            MicaImageLoaders.evictCoverMemory(uri, coverDecodeTarget)
        }
        val loadGeneration = artworkLoadGeneration
        val loadToken = "$loadGeneration:$bitmapKey"
        if (pendingLoads.add(loadToken)) {
            val loadStartedNs = SystemClock.elapsedRealtimeNanos()
            TrackSwitchPerformance.coverAsyncStarted("cover-load")
            TrackSwitchPerformance.mark("cover-load-start", "uri=${uri.takeLast(48)}")
            val loadTarget = coverDecodeTarget
            scope.launch {
                try {
                    val loaded = CoverFlowBitmaps.ensureLoaded(context, uri, loadTarget)
                    loaded?.let { bmp ->
                        if (shouldAcceptArtworkLoad(
                                requestGeneration = loadGeneration,
                                activeGeneration = artworkLoadGeneration,
                                requestTarget = loadTarget,
                                activeTarget = coverDecodeTarget,
                                bitmapKey = bitmapKey,
                                retainedKeys = retainedBitmapKeys(),
                            )
                        ) {
                            bitmapByUri[bitmapKey] = bmp
                            if (reflectionEligible) {
                                scheduleReflectionBake(uri, bmp)
                            }
                            invalidateFor("cover-load")
                        }
                        TrackSwitchPerformance.mark(
                            "cover-load-end",
                            "uri=${uri.takeLast(48)} size=${bmp.width}x${bmp.height}",
                        )
                    }
                } finally {
                    pendingLoads.remove(loadToken)
                    TrackSwitchPerformance.coverAsyncFinished(
                        kind = "cover-load",
                        durationNs = SystemClock.elapsedRealtimeNanos() - loadStartedNs,
                        cacheHit = false,
                    )
                }
            }
        }
        return null
    }

    private fun preloadWindow() {
        pruneBitmapWindow()
        val radius = laneWindowRadius
        for (offset in -radius..radius) {
            val uri = queue.getOrNull(logicalCenter + offset)?.albumArtUri ?: continue
            val reflectionEligible = CoverFlowMath.shouldRenderReflection(offset, laneWindowRadius)
            bitmapFor(uri, reflectionEligible)
            if (reflectionEligible) {
                bitmapByUri[coverDecodeTarget.memoryCacheKey(uri)]
                    ?.let { scheduleReflectionBake(uri, it) }
            }
        }
    }

    private fun retainedBitmapKeys(): Set<String> = retainedArtworkKeys(
        queue = queue,
        centerIndex = logicalCenter,
        visibleOffsets = -laneWindowRadius..laneWindowRadius,
        decodeTarget = coverDecodeTarget,
        extraIndices = listOfNotNull(
            pendingHostIndex,
            pendingPlayQueueIndex,
            awaitingCommittedPlayIndex,
            lastDispatchedPlayIndex,
        ),
    )

    private fun pruneBitmapWindow() {
        bitmapByUri.keys.retainAll(retainedBitmapKeys())
    }

    private fun coverSlotAspect(): Float =
        coverWidthPx / coverHeightPx.coerceAtLeast(1f)

    private fun scheduleReflectionBake(uri: String, cover: Bitmap) {
        if (!CoverFlowReflectionBake.ENABLED || !reflectionEnabled()) return
        val aspect = coverSlotAspect()
        val cacheHit = CoverFlowReflectionBake.cached(uri, aspect) != null
        val bakeStartedNs = SystemClock.elapsedRealtimeNanos()
        TrackSwitchPerformance.coverAsyncStarted("reflection-bake")
        scope.launch {
            runCatching {
                CoverFlowReflectionBake.ensureBaked(uri, cover, aspect)
            }.onFailure {
                com.mica.music.util.DiagnosticLog.event(
                    "CoverFlow",
                    "reflection-bake-failed uri=${uri.takeLast(48)}",
                    it,
                )
            }
            TrackSwitchPerformance.coverAsyncFinished(
                kind = "reflection-bake",
                durationNs = SystemClock.elapsedRealtimeNanos() - bakeStartedNs,
                cacheHit = cacheHit,
            )
            invalidateFor("reflection-bake")
        }
    }

    private fun scheduleReflectionRebakeForWindow() {
        if (!CoverFlowReflectionBake.ENABLED || !reflectionEnabled()) return
        val radius = laneWindowRadius
        for (offset in -radius..radius) {
            val uri = queue.getOrNull(logicalCenter + offset)?.albumArtUri ?: continue
            CoverFlowReflectionBake.evict(uri)
            if (CoverFlowMath.shouldRenderReflection(offset, laneWindowRadius)) {
                bitmapByUri[coverDecodeTarget.memoryCacheKey(uri)]
                    ?.let { scheduleReflectionBake(uri, it) }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled) return false
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAnimators()
                setMotionActive(true)
                dragStartX = event.x
                dragAccumPx = 0f
                dragging = true
                TrackSwitchPerformance.beginCoverFlowWindow(logicalCenter, queue.size)
                TrackSwitchPerformance.mark("coverflow-drag-start", "queueSize=${queue.size}")
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val deltaPx = event.x - dragStartX
                dragStartX = event.x
                dragAccumPx += deltaPx
                stripFraction -= deltaPx / (layoutWidthPx() * laneStepFraction())
                invalidateFor("drag")
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return true
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (abs(dragAccumPx) < 12f) {
                    if (!handleTap(event.x)) {
                        setMotionActive(false)
                    }
                } else {
                    handleDragEnd()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float): Boolean {
        val laneOffset = hitLaneOffset(x) ?: return false
        val distance = abs(laneOffset.toFloat() - stripFraction)
        if (distance < 0.12f) return false
        val queueIndex = logicalCenter + laneOffset
        if (queueIndex in queue.indices) {
            TrackSwitchPerformance.armTrigger("coverflow-tap")
            TrackSwitchPerformance.mark("coverflow-tap", "lane=$laneOffset index=$queueIndex")
            dispatchPlayQueueIndex(queueIndex)
            scheduleMotionIdleFallback()
            return true
        }
        return false
    }

    /** 按钮切歌与滑动 commit 共用：先跑封面动画，结束后再 [dispatchPlayQueueIndex]。 */
    fun skipToIndexVisualFirst(index: Int) {
        if (index !in queue.indices) return
        playQueueIndexAfterVisualCommit(index)
    }

    private fun playQueueIndexAfterVisualCommit(index: Int) {
        lastSupersededHostIndex = logicalCenter
        updateCurrentIndex(index, replaceRunningTrack = true, fromUserGesture = true)
        if (trackAnimator == null) {
            dispatchPlayQueueIndex(index)
        } else {
            pendingPlayQueueIndex = index
        }
    }

    private fun flushPendingPlayQueueIndex() {
        val pending = pendingPlayQueueIndex ?: return
        pendingPlayQueueIndex = null
        val hostAlreadyCommitted = pendingHostIndex == pending
        pendingHostIndex = null
        if (hostAlreadyCommitted) {
            awaitingCommittedPlayIndex = null
            lastDispatchedPlayIndex = null
            lastSupersededHostIndex = null
            hostIndexGuardUntilMs = 0L
            TrackSwitchPerformance.mark(
                "coverflow-dispatch-skip",
                "index=$pending reason=host-already-committed",
            )
            return
        }
        dispatchPlayQueueIndex(pending)
    }

    private fun dispatchPlayQueueIndex(index: Int) {
        awaitingCommittedPlayIndex = index
        lastDispatchedPlayIndex = index
        hostIndexGuardUntilMs = SystemClock.uptimeMillis() + HOST_INDEX_GUARD_MS
        onPlayQueueIndex?.invoke(index)
    }

    private fun hitLaneOffset(x: Float): Int? {
        if (width <= 0) return null
        val cx = coverCenterX()
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        for (lane in -laneWindowRadius..laneWindowRadius) {
            val railOffset = CoverFlowRails.railOffset(lane, stripFraction)
            val tx = CoverFlowRails.translationPx(
                railOffset,
                layoutWidthPx(),
                coverFlowMode,
                expandedRetro = usesExpandedRetroRails(),
            )
            val centerX = cx + tx
            val halfW = coverWidthPx.coerceAtLeast(1f) * 0.45f
            if (x in (centerX - halfW)..(centerX + halfW)) {
                val d = abs(x - centerX)
                if (d < bestDist) {
                    bestDist = d
                    best = lane
                }
            }
        }
        return best
    }

    private fun handleDragEnd() {
        when {
            stripFraction > CoverFlowDragCommitFraction -> {
                val target = nextDragCommitTarget()
                TrackSwitchPerformance.armTrigger("coverflow-drag-next")
                TrackSwitchPerformance.mark(
                    "coverflow-drag-commit",
                    "strip=${"%.3f".format(stripFraction)} target=$target",
                )
                if (target != logicalCenter) {
                    playQueueIndexAfterVisualCommit(target)
                } else {
                    onNext?.invoke()
                }
                scheduleMotionIdleFallback()
            }
            stripFraction < -CoverFlowDragCommitFraction -> {
                val target = previousDragCommitTarget()
                TrackSwitchPerformance.armTrigger("coverflow-drag-prev")
                TrackSwitchPerformance.mark(
                    "coverflow-drag-commit",
                    "strip=${"%.3f".format(stripFraction)} target=$target",
                )
                if (target != logicalCenter) {
                    playQueueIndexAfterVisualCommit(target)
                } else {
                    onPrevious?.invoke()
                }
                scheduleMotionIdleFallback()
            }
            else -> {
                TrackSwitchPerformance.mark(
                    "coverflow-drag-cancel",
                    "strip=${"%.3f".format(stripFraction)}",
                )
                animateStripTo(0f)
            }
        }
    }

    private fun animateStripTo(target: Float) {
        settleAnimator?.cancel()
        val generation = nextVisualCommitGeneration()
        val start = stripFraction
        if (abs(start - target) < 0.0001f) {
            setMotionActive(false)
            return
        }
        settleAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = if (motionEnabled) MicaMotion.DurationMediumMs.toLong() else 0L
            interpolator = LinearInterpolator()
            addUpdateListener {
                recordAnimatorCallback()
                stripFraction = it.animatedValue as Float
                invalidateFor("settle-animator")
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    lastAnimatorCallbackNs = 0L
                    setMotionActive(true)
                    TrackSwitchPerformance.mark(
                        "coverflow-settle-start",
                        "from=${"%.3f".format(start)} to=${"%.3f".format(target)}",
                    )
                }
                override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (generation != visualCommitGeneration) return
                    settleAnimator = null
                    setMotionActive(false)
                    TrackSwitchPerformance.mark("coverflow-settle-end", "strip=${"%.3f".format(stripFraction)}")
                    discardPendingHostIndexAfterCancelledGesture()
                }
            })
            start()
        }
    }

    private fun nextDragCommitTarget(): Int {
        val steps = ceil(stripFraction).toInt().coerceAtLeast(1)
        return (logicalCenter + steps).coerceAtMost(queue.lastIndex)
    }

    private fun previousDragCommitTarget(): Int {
        val steps = floor(stripFraction).toInt().coerceAtMost(-1)
        return (logicalCenter + steps).coerceAtLeast(0)
    }

    private fun discardPendingHostIndexAfterCancelledGesture() {
        val pending = pendingHostIndex
        val lastPlay = lastDispatchedPlayIndex
        if (pending != null && lastPlay != null && pending != lastPlay) {
            pendingHostIndex = null
            return
        }
        flushPendingHostIndex()
    }

    private fun cancelAnimators() {
        nextVisualCommitGeneration()
        trackAnimator?.cancel()
        trackAnimator = null
        settleAnimator?.cancel()
        settleAnimator = null
        pendingPlayQueueIndex = null
    }

    private fun nextVisualCommitGeneration(): Int {
        visualCommitGeneration += 1
        return visualCommitGeneration
    }

    private fun setMotionActive(active: Boolean) {
        if (motionActive == active) return
        motionActive = active
        onMotionActiveChanged?.invoke(active)
    }

    private fun recordAnimatorCallback() {
        if (coverFlowMode != PlayerCoverFlowMode.RETRO_3D) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val previousNs = lastAnimatorCallbackNs
        lastAnimatorCallbackNs = nowNs
        if (previousNs != 0L) {
            TrackSwitchPerformance.recordCoverAnimatorFrame(nowNs - previousNs)
        }
    }

    private fun invalidateFor(reason: String) {
        if (coverFlowMode == PlayerCoverFlowMode.RETRO_3D) {
            TrackSwitchPerformance.recordCoverInvalidate(reason)
        }
        invalidate()
    }

    private fun scheduleMotionIdleFallback() {
        postDelayed(
            {
                if (!dragging && trackAnimator == null && settleAnimator == null) {
                    flushPendingHostIndex()
                    if (abs(stripFraction) >= 0.0001f) {
                        TrackSwitchPerformance.mark(
                            "coverflow-boundary-settle",
                            "strip=${"%.3f".format(stripFraction)}",
                        )
                        animateStripTo(0f)
                    } else {
                        setMotionActive(false)
                    }
                }
            },
            MicaMotion.DurationLongMs.toLong() + 80L,
        )
    }

    fun release() {
        cancelAnimators()
        scope.cancel()
        pendingLoads.clear()
        bitmapByUri.clear()
        CoverFlowReflectionBake.clear()
        pendingHostIndex = null
        pendingPlayQueueIndex = null
        awaitingCommittedPlayIndex = null
        lastDispatchedPlayIndex = null
        lastSupersededHostIndex = null
        hostIndexGuardUntilMs = 0L
        onPlayQueueIndex = null
        onPrevious = null
        onNext = null
        onCoverLongPress = null
        onCenterAspectRatio = null
        setMotionActive(false)
        onMotionActiveChanged = null
    }

    companion object {
        private const val HOST_INDEX_GUARD_MS = 1_500L
    }
}
