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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.Song
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.screens.player.CoverFlowMath
import com.mica.music.ui.screens.player.CoverFlowRails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    private val bitmapSrcRect = Rect()
    private val reflectionSrcRect = Rect()
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var queue: List<Song> = emptyList()
    private var logicalCenter: Int = 0
    private var stripFraction: Float = 0f
    private var coverFlowMode: PlayerCoverFlowMode = PlayerCoverFlowMode.PAUSE_FOLD
    private var foldProgress: Float = 1f
    private var screenWidthPx: Float = 1f
    private var coverWidthPx: Float = 1f
    private var coverHeightPx: Float = 1f
    private var coverStartPaddingPx: Float = 0f
    private var reflectionGapPx: Float = 0f
    private var cameraDistancePx: Float = 48f
    private var motionEnabled: Boolean = true
    private var gesturesEnabled: Boolean = true
    private var fallbackColorArgb: Int = 0xFF000000.toInt()
    private var lastReportedIndex: Int = -1

    private var trackAnimator: ValueAnimator? = null
    private var settleAnimator: ValueAnimator? = null
    private var dragStartX: Float = 0f
    private var dragAccumPx: Float = 0f
    private var dragging: Boolean = false

    private val bitmapByUri = mutableMapOf<String, Bitmap>()
    private val pendingLoads = mutableSetOf<String>()

    var onPlayQueueIndex: ((Int) -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onCoverLongPress: (() -> Unit)? = null
    var onCenterAspectRatio: ((Float) -> Unit)? = null

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
            invalidate()
        }
    }

    fun setCoverSizePx(widthPx: Float, heightPx: Float) {
        val w = widthPx.coerceAtLeast(1f)
        val h = heightPx.coerceAtLeast(1f)
        if (coverWidthPx != w || coverHeightPx != h) {
            coverWidthPx = w
            coverHeightPx = h
            invalidate()
        }
    }

    fun setCoverStartPaddingPx(px: Float) {
        if (coverStartPaddingPx != px) {
            coverStartPaddingPx = px.coerceAtLeast(0f)
            invalidate()
        }
    }

    fun setReflectionGapPx(px: Float) {
        if (reflectionGapPx != px) {
            reflectionGapPx = px
            invalidate()
        }
    }

    fun setCameraDistancePx(px: Float) {
        if (cameraDistancePx != px) {
            cameraDistancePx = px.coerceAtLeast(1f)
            invalidate()
        }
    }

    fun setFoldProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        if (foldProgress != p) {
            foldProgress = p
            invalidate()
        }
    }

    fun setCoverFlowMode(mode: PlayerCoverFlowMode) {
        if (coverFlowMode != mode) {
            coverFlowMode = mode
            invalidate()
        }
    }

    fun setMotionEnabled(enabled: Boolean) {
        motionEnabled = enabled
    }

    fun setFallbackColor(argb: Int) {
        fallbackColorArgb = argb
        invalidate()
    }

    fun updateQueue(songs: List<Song>) {
        queue = songs
        preloadWindow()
        invalidate()
    }

    fun updateCurrentIndex(index: Int) {
        if (trackAnimator != null) return
        if (lastReportedIndex < 0) {
            logicalCenter = index
            stripFraction = 0f
            lastReportedIndex = index
            preloadWindow()
            invalidate()
            return
        }
        if (logicalCenter == index && abs(stripFraction) < 0.0001f) {
            lastReportedIndex = index
            return
        }
        val delta = index - logicalCenter
        if (delta == 0) return
        cancelAnimators()
        val shouldAnimate = motionEnabled && abs(delta) == 1
        if (!shouldAnimate) {
            commitTrackIndex(index)
            return
        }
        val duration = if (coverFlowMode == PlayerCoverFlowMode.RETRO_3D) {
            MicaMotion.DurationLongMs
        } else {
            MicaMotion.DurationMediumMs
        }
        val fromCenter = logicalCenter
        val endVisual = index.toFloat()
        val startVisual = CoverFlowRails.clampTrackChangeStartVisual(
            fromLogicalCenter = fromCenter,
            startVisual = fromCenter + stripFraction,
            endVisual = endVisual,
            signedDelta = delta,
        )
        if (abs(startVisual - endVisual) < 0.0001f) {
            commitTrackIndex(index)
            return
        }
        trackAnimator = ValueAnimator.ofFloat(startVisual, endVisual).apply {
            this.duration = if (motionEnabled) duration.toLong() else 0L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                stripFraction = v - fromCenter
                invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) = Unit
                override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    stripFraction = endVisual - fromCenter
                    commitTrackIndex(index)
                    trackAnimator = null
                }
            })
            start()
        }
    }

    fun resetToIndex(index: Int) {
        cancelAnimators()
        commitTrackIndex(index)
    }

    private fun commitTrackIndex(index: Int) {
        logicalCenter = index
        stripFraction = 0f
        lastReportedIndex = index
        preloadWindow()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAnimators()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val slotW = if (coverWidthPx > 0f) coverWidthPx else width.toFloat()
        val slotH = if (coverHeightPx > 0f) coverHeightPx else height.toFloat()
        val cx = coverCenterX()
        val cy = contentCenterY(slotH)
        val lanes = (-CoverFlowMath.LaneWindowRadius..CoverFlowMath.LaneWindowRadius).toList()
        val laneStates = lanes.mapNotNull { laneIndex ->
            buildLaneState(laneIndex, slotW)
        }.sortedBy { it.zIndex }
        for (state in laneStates) {
            drawLane(canvas, state, cx, cy, slotW, slotH)
        }
        laneStates.firstOrNull { abs(it.railOffset) < 0.08f }?.let { center ->
            val ratio = if (center.bitmap != null && center.bitmap.height > 0) {
                center.bitmap.width.toFloat() / center.bitmap.height
            } else {
                1f
            }
            onCenterAspectRatio?.invoke(ratio)
        }
    }

    private fun buildLaneState(
        laneIndex: Int,
        slotW: Float,
    ): LaneDrawState? {
        val railOffset = CoverFlowRails.railOffset(laneIndex, stripFraction)
        if (abs(railOffset) > CoverFlowMath.MaxViewDistance) return null
        val song = queue.getOrNull(logicalCenter + laneIndex) ?: return null
        val slotAlpha = CoverFlowRails.alpha(railOffset, foldProgress, coverFlowMode)
        if (slotAlpha < 0.01f) return null
        return LaneDrawState(
            laneIndex = laneIndex,
            railOffset = railOffset,
            song = song,
            tx = CoverFlowRails.translationPx(railOffset, layoutWidthPx(), coverFlowMode),
            rotationY = CoverFlowRails.rotationY(railOffset, coverFlowMode),
            bitmap = bitmapFor(song.albumArtUri),
            slotAlphaByte = (slotAlpha * 255).toInt().coerceIn(0, 255),
            drawScale = CoverFlowRails.drawScale(railOffset, coverFlowMode, foldProgress),
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
        } else {
            paint.color = state.song.coverColorArgb
            canvas.drawRect(coverRect, paint)
            paint.color = fallbackColorArgb
        }
        if (reflectionEnabled()) {
            drawReflection(canvas, bitmap, state.song, slotW, slotH, state.slotAlphaByte)
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
        bitmap: Bitmap?,
        song: Song,
        slotW: Float,
        slotH: Float,
        slotAlphaByte: Int,
    ) {
        val reflH = slotH * ReflectionHeightFraction
        val gap = reflectionGapPx
        val coverBottom = slotH * 0.5f
        val top = coverBottom + gap
        val bottom = top + reflH
        reflectionRect.set(-slotW * 0.5f, top, slotW * 0.5f, bottom)
        val combinedAlpha = ((ReflectionAlpha * slotAlphaByte / 255f) * 255f)
            .toInt()
            .coerceIn(0, 255)
        canvas.save()
        canvas.clipRect(reflectionRect)
        if (bitmap != null) {
            centerCropSrc(bitmap, slotW, slotH, reflectionSrcRect)
            val srcSliceH = (reflectionSrcRect.height() * ReflectionHeightFraction)
                .toInt()
                .coerceIn(1, reflectionSrcRect.height())
            reflectionSrcRect.top = reflectionSrcRect.bottom - srcSliceH
            val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = combinedAlpha
            }
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
                RectF(-slotW * 0.5f, 0f, slotW * 0.5f, reflH),
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
            val baseAlpha = ReflectionAlpha * slotAlphaByte / 255f
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
        PlayerCoverFlowMode.RETRO_3D -> CoverFlowRails.RetroFirstStep
        else -> CoverFlowMath.LaneStepFraction
    }

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

    private fun bitmapFor(uri: String?): Bitmap? {
        if (uri.isNullOrBlank()) return null
        bitmapByUri[uri]?.let { cached ->
            if (!CoverFlowBitmaps.isPollutedThumbnail(cached)) return cached
            bitmapByUri.remove(uri)
        }
        CoverFlowBitmaps.memoryBitmap(uri)?.let { cached ->
            if (!CoverFlowBitmaps.isPollutedThumbnail(cached)) {
                bitmapByUri[uri] = cached
                return cached
            }
            MicaImageLoaders.evictCoverMemory(uri)
        }
        if (!pendingLoads.contains(uri)) {
            pendingLoads.add(uri)
            scope.launch {
                CoverFlowBitmaps.ensureLoaded(context, uri)?.let { bmp ->
                    bitmapByUri[uri] = bmp
                    invalidate()
                }
                pendingLoads.remove(uri)
            }
        }
        return null
    }

    private fun preloadWindow() {
        val radius = CoverFlowMath.LaneWindowRadius
        for (offset in -radius..radius) {
            val uri = queue.getOrNull(logicalCenter + offset)?.albumArtUri ?: continue
            bitmapFor(uri)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled) return false
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAnimators()
                dragStartX = event.x
                dragAccumPx = 0f
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val deltaPx = event.x - dragStartX
                dragStartX = event.x
                dragAccumPx += deltaPx
                stripFraction -= deltaPx / (layoutWidthPx() * laneStepFraction())
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return true
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (abs(dragAccumPx) < 12f) {
                    handleTap(event.x)
                } else {
                    handleDragEnd()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float) {
        val laneOffset = hitLaneOffset(x) ?: return
        val distance = abs(laneOffset.toFloat() - stripFraction)
        if (distance < 0.12f) return
        val queueIndex = logicalCenter + laneOffset
        if (queueIndex in queue.indices) {
            onPlayQueueIndex?.invoke(queueIndex)
        }
    }

    private fun hitLaneOffset(x: Float): Int? {
        if (width <= 0) return null
        val cx = coverCenterX()
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        for (lane in -CoverFlowMath.LaneWindowRadius..CoverFlowMath.LaneWindowRadius) {
            val railOffset = CoverFlowRails.railOffset(lane, stripFraction)
            val tx = CoverFlowRails.translationPx(railOffset, layoutWidthPx(), coverFlowMode)
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
            stripFraction > DRAG_COMMIT -> {
                val target = (logicalCenter + 1).coerceAtMost(queue.lastIndex)
                if (target != logicalCenter) {
                    onPlayQueueIndex?.invoke(target)
                } else {
                    onNext?.invoke()
                }
            }
            stripFraction < -DRAG_COMMIT -> {
                val target = (logicalCenter - 1).coerceAtLeast(0)
                if (target != logicalCenter) {
                    onPlayQueueIndex?.invoke(target)
                } else {
                    onPrevious?.invoke()
                }
            }
            else -> animateStripTo(0f)
        }
    }

    private fun animateStripTo(target: Float) {
        settleAnimator?.cancel()
        val start = stripFraction
        if (abs(start - target) < 0.0001f) return
        settleAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = if (motionEnabled) MicaMotion.DurationMediumMs.toLong() else 0L
            interpolator = LinearInterpolator()
            addUpdateListener {
                stripFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun cancelAnimators() {
        trackAnimator?.cancel()
        trackAnimator = null
        settleAnimator?.cancel()
        settleAnimator = null
    }

    companion object {
        private const val DRAG_COMMIT = 0.35f
        private const val ReflectionHeightFraction = 0.28f
        private const val ReflectionAlpha = 0.24f
    }
}
