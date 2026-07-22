package com.mica.music.ui.screens.player.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.mica.music.data.Song
import com.mica.music.data.TrackSkipDirection
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.media.MicaSpectrumAnalyzer
import com.mica.music.ui.screens.player.PhotoStackPullAwayDurationMs
import com.mica.music.ui.screens.player.PhotoStackTransitionCard
import com.mica.music.ui.screens.player.PhotoStackTransitionSlot
import com.mica.music.ui.screens.player.photoStackSteadyCards
import com.mica.music.ui.screens.player.photoStackSteadyStack
import com.mica.music.ui.screens.player.photoStackTransitionCards
import com.mica.music.ui.screens.player.photoStackTransitionPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val PhotoStackWaveformBars = 86
private const val PhotoStackVisibleBandMaxFraction = 0.875f
private const val PhotoStackWaveformActivationThreshold = 0.015f
private const val SwipeCommitFraction = 0.11f
private const val SwipeVisualLimitFraction = 0.15f
private const val HostIndexGuardMs = 1_500L

internal data class PhotoStackTransitionFramePx(
    val slotWidthPx: Float,
    val slotHeightPx: Float,
    val cardWidthPx: Float,
    val cardHeightPx: Float,
    val artworkInsetTopPx: Float,
    val artworkInsetHorizontalPx: Float,
    val waveformHeightPx: Float,
) {
    companion object {
        val Empty = PhotoStackTransitionFramePx(
            slotWidthPx = 1f,
            slotHeightPx = 1f,
            cardWidthPx = 1f,
            cardHeightPx = 1f,
            artworkInsetTopPx = 0f,
            artworkInsetHorizontalPx = 0f,
            waveformHeightPx = 1f,
        )
    }
}

private data class PhotoStackPose(
    val translationX: Float,
    val translationY: Float,
    val rotationZ: Float,
    val scale: Float,
    val alpha: Float = 1f,
)

private enum class TouchMode {
    None,
    Swipe,
    Seek,
}

@SuppressLint("ViewConstructor")
internal class PhotoStackTransitionView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x38000000
    }
    private val artworkFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardRect = RectF()
    private val shadowRect = RectF()
    private val artworkRect = RectF()
    private val bitmapSrcRect = Rect()
    private val spectrumDisplayLevels = FloatArray(PhotoStackWaveformBars)
    private val spectrumInvalidator = object : Runnable {
        override fun run() {
            spectrumLoopScheduled = false
            if (!shouldRunSpectrumLoop()) return
            invalidate()
            scheduleSpectrumLoop()
        }
    }

    private var frame = PhotoStackTransitionFramePx.Empty
    private var decodeTarget = CoverDecodeTarget.fromPixels(1f, 1f)
    private var artworkLoadGeneration: Long = 0L
    private var shadowTuning = PhotoStackShadowTuning()
    private var shadowBitmapCache: Bitmap? = null
    private var shadowBitmapCacheKey: String? = null
    private var motionEnabled = true
    private var gesturesEnabled = true

    private var queue: List<Song> = emptyList()
    private var logicalCenter: Int = 0
    private var lastReportedIndex: Int = -1
    private var pendingHostIndex: Int? = null
    private var pendingPlayQueueIndex: Int? = null
    private var awaitingCommittedPlayIndex: Int? = null
    private var lastDispatchedPlayIndex: Int? = null
    private var lastSupersededHostIndex: Int? = null
    private var hostIndexGuardUntilMs: Long = 0L
    private var visualCommitGeneration: Int = 0

    private var activeTransitionCards: List<PhotoStackTransitionCard> = emptyList()
    private var transitionProgress = 1f
    private var trackAnimator: ValueAnimator? = null
    private var settleAnimator: ValueAnimator? = null

    private var sliderValue = 0f
    private var seekRangeStart = 0f
    private var seekRangeEnd = 1f
    private var isPlaying = false
    private var spectrumEnabled = false
    private var onSeekValueChange: ((Float) -> Unit)? = null
    private var onSeekFinished: (() -> Unit)? = null
    private var onPlayQueueIndex: ((Int) -> Unit)? = null
    private var onPrevious: (() -> Unit)? = null
    private var onNext: (() -> Unit)? = null
    private var onCoverLongPress: (() -> Unit)? = null
    private var onMotionActiveChanged: ((Boolean) -> Unit)? = null
    private var motionActive = false

    private var touchMode = TouchMode.None
    private var lastTouchX = 0f
    private var dragFraction = 0f
    private var dragging = false
    private var spectrumLoopScheduled = false
    private var spectrumBoundSongId: String? = null
    private var spectrumActivatedForBoundSong = false

    private val bitmapByKey = mutableMapOf<String, Bitmap>()
    private val pendingLoads = mutableSetOf<String>()

    /** Perf-only callers use this to verify that the local artwork window stays bounded. */
    internal fun diagnosticArtworkState(): ArtworkRetentionDiagnostic = ArtworkRetentionDiagnostic(
        retainedBitmapCount = bitmapByKey.size,
        pendingLoadCount = pendingLoads.size,
        queueSize = queue.size,
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (pointToFrontCardLocal(e.x, e.y) == null) return
                cancelTouchGesture()
                onCoverLongPress?.invoke()
            }
        },
    )

    init {
        setWillNotDraw(false)
    }

    fun setMotionEnabled(enabled: Boolean) {
        motionEnabled = enabled
    }

    fun setGesturesEnabled(enabled: Boolean) {
        gesturesEnabled = enabled
    }

    fun setShadowTuning(tuning: PhotoStackShadowTuning) {
        if (shadowTuning == tuning) return
        shadowTuning = tuning
        clearShadowBitmapCache()
        invalidate()
    }

    fun setFrame(frame: PhotoStackTransitionFramePx) {
        if (this.frame == frame) return
        this.frame = frame
        val artworkSizePx =
            (frame.cardWidthPx - frame.artworkInsetHorizontalPx * 2f).coerceAtLeast(1f)
        val nextDecodeTarget = CoverDecodeTarget.fromPixels(artworkSizePx, artworkSizePx)
        if (decodeTarget != nextDecodeTarget) {
            decodeTarget = nextDecodeTarget
            artworkLoadGeneration++
            pruneBitmapWindow()
        }
        clearShadowBitmapCache()
        invalidate()
    }

    fun setPlaybackState(
        sliderValue: Float,
        rangeStart: Float,
        rangeEnd: Float,
        isPlaying: Boolean,
        spectrumEnabled: Boolean,
        onSeekValueChange: (Float) -> Unit,
        onSeekFinished: () -> Unit,
    ) {
        this.sliderValue = sliderValue
        seekRangeStart = rangeStart
        seekRangeEnd = rangeEnd.coerceAtLeast(rangeStart + 1f)
        this.isPlaying = isPlaying
        this.spectrumEnabled = spectrumEnabled
        this.onSeekValueChange = onSeekValueChange
        this.onSeekFinished = onSeekFinished
        updateSpectrumLoop()
        invalidate()
    }

    fun setCallbacks(
        onPlayQueueIndex: (Int) -> Unit,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        onMotionActiveChanged: (Boolean) -> Unit,
        onCoverLongPress: (() -> Unit)? = null,
    ) {
        this.onPlayQueueIndex = onPlayQueueIndex
        this.onPrevious = onPrevious
        this.onNext = onNext
        this.onMotionActiveChanged = onMotionActiveChanged
        this.onCoverLongPress = onCoverLongPress
    }

    fun applyHostUpdate(
        songs: List<Song>,
        index: Int,
        stageActive: Boolean,
    ) {
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
                return
            }
        }
        lastDispatchedPlayIndex?.let { latestTarget ->
            if (index != latestTarget && SystemClock.uptimeMillis() < hostIndexGuardUntilMs) {
                if (awaitingCommittedPlayIndex == null || index == lastSupersededHostIndex) {
                    return
                }
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

    fun skipToIndexVisualFirst(index: Int) {
        if (index !in queue.indices) return
        playQueueIndexAfterVisualCommit(index)
    }

    private fun shouldDeferHostIndexUpdate(): Boolean =
        dragging || trackAnimator != null || settleAnimator != null || pendingPlayQueueIndex != null

    private fun flushPendingHostIndex() {
        val pending = pendingHostIndex ?: return
        pendingHostIndex = null
        if (pending == logicalCenter && activeTransitionCards.isEmpty() && abs(dragFraction) < 0.0001f) return
        updateCurrentIndex(pending)
    }

    private fun updateQueue(songs: List<Song>) {
        if (sameVisualQueue(queue, songs)) return
        queue = songs
        preloadWindow()
        invalidate()
    }

    private fun sameVisualQueue(current: List<Song>, incoming: List<Song>): Boolean {
        if (current === incoming) return true
        if (current.size != incoming.size) return false
        return current.indices.all { idx ->
            val old = current[idx]
            val new = incoming[idx]
            old.id == new.id &&
                old.albumArtUri == new.albumArtUri &&
                old.coverColorArgb == new.coverColorArgb
        }
    }

    private fun updateCurrentIndex(
        index: Int,
        replaceRunningTrack: Boolean = false,
        fromUserGesture: Boolean = false,
    ) {
        if (trackAnimator != null && !replaceRunningTrack) {
            pendingHostIndex = index
            return
        }
        if (lastReportedIndex < 0) {
            logicalCenter = index.coerceInValidRange()
            lastReportedIndex = logicalCenter
            dragFraction = 0f
            activeTransitionCards = emptyList()
            transitionProgress = 1f
            preloadWindow()
            invalidate()
            return
        }
        if (logicalCenter == index && activeTransitionCards.isEmpty() && abs(dragFraction) < 0.0001f) {
            lastReportedIndex = index
            return
        }
        val delta = index - logicalCenter
        if (delta == 0) {
            lastReportedIndex = index
            return
        }
        cancelAnimators()
        val targetSong = queue.getOrNull(index) ?: return
        val currentSong = queue.getOrNull(logicalCenter) ?: targetSong
        val direction = if (delta > 0) TrackSkipDirection.TO_NEXT else TrackSkipDirection.TO_PREVIOUS
        activeTransitionCards = photoStackTransitionCards(
            photoStackTransitionPlan(
                queue = queue,
                currentIndex = index,
                currentSong = targetSong,
                settledFrontSong = currentSong,
                direction = direction,
            ),
        )
        pruneBitmapWindow()
        if (activeTransitionCards.isEmpty() || !motionEnabled) {
            activeTransitionCards = emptyList()
            transitionProgress = 1f
            commitTrackIndex(index)
            setMotionActive(false)
            flushPendingPlayQueueIndex()
            flushPendingHostIndex()
            return
        }
        transitionProgress = 0f
        val generation = nextVisualCommitGeneration()
        trackAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PhotoStackPullAwayDurationMs.toLong()
            interpolator = DecelerateInterpolator(1.65f)
            addUpdateListener {
                transitionProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    setMotionActive(true)
                }

                override fun onAnimationCancel(animation: android.animation.Animator) = Unit

                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (generation != visualCommitGeneration) return
                    trackAnimator = null
                    transitionProgress = 1f
                    activeTransitionCards = emptyList()
                    commitTrackIndex(index)
                    setMotionActive(false)
                    flushPendingPlayQueueIndex()
                    flushPendingHostIndex()
                }
            })
            start()
        }
        if (fromUserGesture) {
            dragFraction = 0f
        }
        updateSpectrumLoop()
    }

    private fun resetToIndex(index: Int) {
        cancelAnimators()
        activeTransitionCards = emptyList()
        transitionProgress = 1f
        commitTrackIndex(index)
        setMotionActive(false)
    }

    private fun commitTrackIndex(index: Int) {
        logicalCenter = index.coerceInValidRange()
        lastReportedIndex = logicalCenter
        dragFraction = 0f
        preloadWindow()
        invalidate()
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
            return
        }
        dispatchPlayQueueIndex(pending)
    }

    private fun dispatchPlayQueueIndex(index: Int) {
        awaitingCommittedPlayIndex = index
        lastDispatchedPlayIndex = index
        hostIndexGuardUntilMs = SystemClock.uptimeMillis() + HostIndexGuardMs
        onPlayQueueIndex?.invoke(index)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val cards = if (activeTransitionCards.isNotEmpty()) {
            activeTransitionCards
        } else {
            steadyCards()
        }
        syncSpectrumBinding(cards)
        for (card in cards) {
            drawCard(canvas, card, poseFor(card.slot, transitionProgress))
        }
        updateSpectrumLoop()
    }

    private fun steadyCards(): List<PhotoStackTransitionCard> {
        val currentSong = queue.getOrNull(logicalCenter) ?: return emptyList()
        return photoStackSteadyCards(
            photoStackSteadyStack(
                queue = queue,
                currentIndex = logicalCenter,
                currentSong = currentSong,
            ),
        )
    }

    private fun drawCard(
        canvas: Canvas,
        card: PhotoStackTransitionCard,
        pose: PhotoStackPose,
    ) {
        if (pose.alpha <= 0.001f) return
        val halfWidth = frame.cardWidthPx * 0.5f
        val halfHeight = frame.cardHeightPx * 0.5f
        val centerX = width * 0.5f + pose.translationX
        val centerY = halfHeight + pose.translationY
        val layerPadding = shadowLayerPaddingPx()

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(pose.rotationZ)
        canvas.scale(pose.scale, pose.scale)
        val layerId = canvas.saveLayerAlpha(
            -halfWidth - layerPadding,
            -halfHeight - layerPadding,
            halfWidth + layerPadding,
            halfHeight + layerPadding,
            (pose.alpha * 255).toInt().coerceIn(0, 255),
        )

        drawShadowHalo(canvas, halfWidth, halfHeight)

        cardRect.set(-halfWidth, -halfHeight, halfWidth, halfHeight)
        paperPaint.shader = LinearGradient(
            cardRect.left,
            cardRect.top,
            cardRect.right,
            cardRect.bottom,
            intArrayOf(0xFFFAF8F2.toInt(), 0xFFF2EEE6.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(cardRect, paperPaint)
        paperPaint.shader = null

        borderPaint.color = 0x1A34291D
        borderPaint.strokeWidth = dp(1f)
        canvas.drawRect(cardRect, borderPaint)

        val artworkSize = (frame.cardWidthPx - frame.artworkInsetHorizontalPx * 2f).coerceAtLeast(1f)
        artworkRect.set(
            -halfWidth + frame.artworkInsetHorizontalPx,
            -halfHeight + frame.artworkInsetTopPx,
            -halfWidth + frame.artworkInsetHorizontalPx + artworkSize,
            -halfHeight + frame.artworkInsetTopPx + artworkSize,
        )
        artworkFallbackPaint.color = card.song.coverColorArgb
        canvas.drawRect(artworkRect, artworkFallbackPaint)
        bitmapFor(card.song)?.let { bitmap ->
            centerCropSrc(bitmap, artworkRect.width(), artworkRect.height(), bitmapSrcRect)
            canvas.drawBitmap(bitmap, bitmapSrcRect, artworkRect, bitmapPaint)
        }

        if (card.showProgress) {
            drawProgressStrip(canvas)
        }

        canvas.restoreToCount(layerId)
        canvas.restore()
    }

    private fun drawShadowHalo(
        canvas: Canvas,
        halfWidth: Float,
        halfHeight: Float,
    ) {
        val shadowBitmap = shadowBitmap() ?: return
        val padding = shadowLayerPaddingPx()
        shadowRect.set(
            -halfWidth - padding,
            -halfHeight - padding,
            halfWidth + padding,
            halfHeight + padding,
        )
        canvas.drawBitmap(shadowBitmap, null, shadowRect, shadowBitmapPaint)
    }

    private fun shadowLayerPaddingPx(): Float {
        val spread = max(
            dp(shadowTuning.sideSpreadDp),
            max(
                dp(shadowTuning.topSpreadDp),
                dp(shadowTuning.bottomSpreadDp) + dp(shadowTuning.bottomOffsetDp),
            ),
        )
        val radius = max(
            dp(shadowTuning.topCornerRadiusDp),
            dp(shadowTuning.bottomCornerRadiusDp) + dp(shadowTuning.bottomOffsetDp),
        )
        return max(spread, radius) + dp(10f)
    }

    private fun shadowBitmap(): Bitmap? {
        val cardWidth = frame.cardWidthPx.coerceAtLeast(1f)
        val cardHeight = frame.cardHeightPx.coerceAtLeast(1f)
        val padding = shadowLayerPaddingPx().coerceAtLeast(1f)
        val width = (cardWidth + padding * 2f).roundToInt().coerceAtLeast(1)
        val height = (cardHeight + padding * 2f).roundToInt().coerceAtLeast(1)
        val cacheKey = listOf(width, height, shadowTuning.hashCode()).joinToString(":")
        shadowBitmapCache?.takeIf { !it.isRecycled && shadowBitmapCacheKey == cacheKey }?.let {
            return it
        }

        clearShadowBitmapCache()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val targetCanvas = Canvas(bitmap)
        val baseRect = RectF(
            padding,
            padding,
            padding + cardWidth,
            padding + cardHeight,
        )

        val ambientBlur = (
            max(dp(shadowTuning.sideSpreadDp), dp(shadowTuning.topSpreadDp)) * 0.62f
            ).coerceAtLeast(1f)
        val topBlur = dp(shadowTuning.topSpreadDp).coerceAtLeast(1f)
        val bottomBlur = dp(shadowTuning.bottomSpreadDp).coerceAtLeast(1f)
        val topRadius = dp(shadowTuning.topCornerRadiusDp).coerceAtLeast(1f)
        val bottomRadius = dp(shadowTuning.bottomCornerRadiusDp).coerceAtLeast(1f)
        val ambientRadius = max(topRadius, bottomRadius)

        drawBlurredShadowPass(
            targetCanvas = targetCanvas,
            baseRect = baseRect,
            blurRadius = ambientBlur,
            offsetY = -topBlur * 0.04f,
            insetX = dp(shadowTuning.sideInsetDp) * 0.5f,
            alpha = max(shadowTuning.sideAlpha * 0.38f, shadowTuning.topAlpha * 0.42f),
            cornerRadius = ambientRadius,
        )
        drawBlurredShadowPass(
            targetCanvas = targetCanvas,
            baseRect = baseRect,
            blurRadius = topBlur,
            offsetY = -topBlur * 0.38f,
            insetX = dp(shadowTuning.sideInsetDp) * 0.2f,
            alpha = max(shadowTuning.topAlpha * 0.65f, shadowTuning.topCornerAlpha * 0.58f),
            cornerRadius = topRadius,
        )
        drawBlurredShadowPass(
            targetCanvas = targetCanvas,
            baseRect = baseRect,
            blurRadius = bottomBlur,
            offsetY = dp(shadowTuning.bottomOffsetDp),
            insetX = dp(shadowTuning.sideInsetDp),
            alpha = max(shadowTuning.bottomAlpha * 0.92f, shadowTuning.bottomCornerAlpha * 0.88f),
            cornerRadius = bottomRadius,
        )

        shadowBitmapCache = bitmap
        shadowBitmapCacheKey = cacheKey
        return bitmap
    }

    private fun drawBlurredShadowPass(
        targetCanvas: Canvas,
        baseRect: RectF,
        blurRadius: Float,
        offsetY: Float,
        insetX: Float,
        alpha: Float,
        cornerRadius: Float,
    ) {
        if (alpha <= 0.001f) return
        val rect = RectF(
            baseRect.left + insetX,
            baseRect.top,
            baseRect.right - insetX,
            baseRect.bottom,
        )
        rect.offset(0f, offsetY)
        shadowPaint.shader = null
        shadowPaint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        shadowPaint.color = applyAlpha(0xFF000000.toInt(), alpha)
        targetCanvas.drawRoundRect(rect, cornerRadius, cornerRadius, shadowPaint)
        shadowPaint.maskFilter = null
    }

    private fun clearShadowBitmapCache() {
        shadowBitmapCache?.recycle()
        shadowBitmapCache = null
        shadowBitmapCacheKey = null
    }

    private fun drawProgressStrip(canvas: Canvas) {
        val liveLevels = if (spectrumEnabled && isPlaying) MicaSpectrumAnalyzer.levels.value else emptyList()
        val playedFraction = ((sliderValue - seekRangeStart) / (seekRangeEnd - seekRangeStart)).coerceIn(0f, 1f)
        val count = PhotoStackWaveformBars
        val gap = dp(2f)
        val barWidth = ((artworkRect.width() - gap * (count - 1)) / count).coerceIn(dp(1f), dp(1.6f))
        val centerY = (artworkRect.bottom + cardRect.bottom) * 0.5f
        val quietHeight = dp(1.4f)
        val activeRhythm = spectrumEnabled && isPlaying
        var hasMeaningfulLevel = false
        for (index in 0 until count) {
            val target = spectrumTargetLevel(liveLevels, index)
            val current = spectrumDisplayLevels[index]
            val next = when {
                activeRhythm -> {
                    val attack = if (target > current) 0.62f else 0.18f
                    current + (target - current) * attack
                }
                else -> current
            }
            spectrumDisplayLevels[index] = next.coerceIn(0f, 1f)
            if (spectrumDisplayLevels[index] > PhotoStackWaveformActivationThreshold) {
                hasMeaningfulLevel = true
            }
        }
        if (activeRhythm && hasMeaningfulLevel) {
            spectrumActivatedForBoundSong = true
        }
        if (!spectrumEnabled || !spectrumActivatedForBoundSong) {
            return
        }

        for (index in 0 until count) {
            val fraction = index / (count - 1f).coerceAtLeast(1f)
            val halfHeight = if (activeRhythm || spectrumDisplayLevels[index] > 0.001f) {
                val shapedPeak = spectrumDisplayLevels[index] * 0.84f
                (quietHeight + shapedPeak * frame.waveformHeightPx * 0.48f)
                    .coerceIn(quietHeight, max(frame.waveformHeightPx * 0.50f, quietHeight))
            } else {
                quietHeight / 2f
            }
            progressPaint.color = applyAlpha(
                0xFF12202C.toInt(),
                if (fraction <= playedFraction) 0.68f else 0.28f,
            )
            val left = artworkRect.left + index * (barWidth + gap)
            val right = left + barWidth
            canvas.drawRoundRect(
                left,
                centerY - halfHeight,
                right,
                centerY + halfHeight,
                barWidth * 0.5f,
                barWidth * 0.5f,
                progressPaint,
            )
        }
    }

    private fun syncSpectrumBinding(cards: List<PhotoStackTransitionCard>) {
        val progressSongId = cards.lastOrNull { it.showProgress }?.song?.id
        if (spectrumBoundSongId == progressSongId) return
        spectrumBoundSongId = progressSongId
        spectrumActivatedForBoundSong = false
        resetSpectrumDisplayLevels()
    }

    private fun spectrumTargetLevel(
        liveLevels: List<Float>,
        index: Int,
    ): Float {
        if (liveLevels.isEmpty()) return 0f
        val sourceMax = (liveLevels.size - 1f) * PhotoStackVisibleBandMaxFraction
        val sourcePosition = index * sourceMax / (PhotoStackWaveformBars - 1f)
        val left = sourcePosition.toInt().coerceIn(0, liveLevels.lastIndex)
        val right = (left + 1).coerceAtMost(liveLevels.lastIndex)
        val fraction = sourcePosition - left
        return (liveLevels[left] + (liveLevels[right] - liveLevels[left]) * fraction).coerceIn(0f, 1f)
    }

    private fun poseFor(
        slot: PhotoStackTransitionSlot,
        progress: Float,
    ): PhotoStackPose = when (slot) {
        PhotoStackTransitionSlot.SteadyBack -> backPose()
        PhotoStackTransitionSlot.SteadyMiddle -> middlePose()
        PhotoStackTransitionSlot.SteadyFront -> frontIdlePose()
        PhotoStackTransitionSlot.NextEmergingBack -> lerpPose(
            start = PhotoStackPose(
                translationX = dp(40f),
                translationY = dp(64f),
                rotationZ = 10f,
                scale = 0.84f,
                alpha = 0f,
            ),
            end = backPose(),
            progress = progress,
            alpha = if (progress < 0.2f) 0f else lerp(0.22f, 0.78f, (progress - 0.2f) / 0.8f),
        )
        PhotoStackTransitionSlot.NextStackMiddle -> lerpPose(
            start = backPose(),
            end = middlePose(),
            progress = progress,
        )
        PhotoStackTransitionSlot.NextStackFront -> lerpPose(
            start = middlePose(),
            end = frontPose(),
            progress = progress,
        )
        PhotoStackTransitionSlot.NextLeavingFront -> lerpPose(
            start = frontPose(),
            end = PhotoStackPose(
                translationX = -(frame.slotWidthPx + frame.cardWidthPx + dp(24f)),
                translationY = dp(-18f),
                rotationZ = -12.5f,
                scale = 0.98f,
            ),
            progress = progress,
        )
        PhotoStackTransitionSlot.PreviousFadingBack -> lerpPose(
            start = backPose(),
            end = PhotoStackPose(
                translationX = dp(58f),
                translationY = dp(46f),
                rotationZ = 11f,
                scale = 0.82f,
                alpha = 0f,
            ),
            progress = progress,
        )
        PhotoStackTransitionSlot.PreviousStackBack -> lerpPose(
            start = middlePose(),
            end = backPose(),
            progress = progress,
        )
        PhotoStackTransitionSlot.PreviousStackMiddle -> lerpPose(
            start = frontPose(),
            end = middlePose(),
            progress = progress,
        )
        PhotoStackTransitionSlot.PreviousIncomingFront -> lerpPose(
            start = PhotoStackPose(
                translationX = -(frame.slotWidthPx + frame.cardWidthPx + dp(12f)),
                translationY = dp(-14f),
                rotationZ = -10.5f,
                scale = 0.98f,
                alpha = 0.58f,
            ),
            end = frontPose(),
            progress = progress,
            alpha = lerp(0.58f, 1f, progress),
        )
    }

    private fun frontPose(): PhotoStackPose = PhotoStackPose(
        translationX = 0f,
        translationY = 0f,
        rotationZ = -1.4f,
        scale = 1f,
    )

    private fun frontIdlePose(): PhotoStackPose = PhotoStackPose(
        translationX = dragFraction * frame.slotWidthPx * 0.35f,
        translationY = 0f,
        rotationZ = -1.4f + dragFraction * 6f,
        scale = 1f,
    )

    private fun middlePose(): PhotoStackPose = PhotoStackPose(
        translationX = dp(14f),
        translationY = dp(10f),
        rotationZ = 3.2f,
        scale = 0.95f,
    )

    private fun backPose(): PhotoStackPose = PhotoStackPose(
        translationX = dp(28f),
        translationY = dp(22f),
        rotationZ = 6.4f,
        scale = 0.90f,
    )

    private fun lerpPose(
        start: PhotoStackPose,
        end: PhotoStackPose,
        progress: Float,
        alpha: Float = lerp(start.alpha, end.alpha, progress),
    ): PhotoStackPose = PhotoStackPose(
        translationX = lerp(start.translationX, end.translationX, progress),
        translationY = lerp(start.translationY, end.translationY, progress),
        rotationZ = lerp(start.rotationZ, end.rotationZ, progress),
        scale = lerp(start.scale, end.scale, progress),
        alpha = alpha,
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled || activeTransitionCards.isNotEmpty()) return false
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                dragging = true
                setMotionActive(true)
                touchMode = if (pointToFrontProgressLocal(event.x, event.y) != null) {
                    updateSeekFromTouch(event.x, event.y)
                    TouchMode.Seek
                } else {
                    TouchMode.Swipe
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (touchMode) {
                    TouchMode.Seek -> updateSeekFromTouch(event.x, event.y)
                    TouchMode.Swipe -> {
                        val deltaX = event.x - lastTouchX
                        lastTouchX = event.x
                        dragFraction = (dragFraction + deltaX / width.coerceAtLeast(1)).coerceIn(
                            -SwipeVisualLimitFraction,
                            SwipeVisualLimitFraction,
                        )
                        invalidate()
                    }
                    TouchMode.None -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                when (touchMode) {
                    TouchMode.Seek -> {
                        updateSeekFromTouch(event.x, event.y)
                        onSeekFinished?.invoke()
                        setMotionActive(false)
                    }
                    TouchMode.Swipe -> handleSwipeRelease()
                    TouchMode.None -> Unit
                }
                touchMode = TouchMode.None
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                if (touchMode == TouchMode.Seek) {
                    onSeekFinished?.invoke()
                }
                touchMode = TouchMode.None
                animateDragFractionToZero()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelTouchGesture() {
        parent?.requestDisallowInterceptTouchEvent(false)
        dragging = false
        touchMode = TouchMode.None
        dragFraction = 0f
        setMotionActive(false)
        invalidate()
    }

    private fun handleSwipeRelease() {
        when {
            dragFraction > SwipeCommitFraction -> {
                val target = (logicalCenter - 1).coerceAtLeast(0)
                if (target != logicalCenter) {
                    playQueueIndexAfterVisualCommit(target)
                } else {
                    onPrevious?.invoke()
                    animateDragFractionToZero()
                }
            }
            dragFraction < -SwipeCommitFraction -> {
                val target = (logicalCenter + 1).coerceAtMost(queue.lastIndex.coerceAtLeast(0))
                if (target != logicalCenter) {
                    playQueueIndexAfterVisualCommit(target)
                } else {
                    onNext?.invoke()
                    animateDragFractionToZero()
                }
            }
            else -> animateDragFractionToZero()
        }
    }

    private fun animateDragFractionToZero() {
        settleAnimator?.cancel()
        val start = dragFraction
        if (abs(start) < 0.0001f) {
            dragFraction = 0f
            setMotionActive(false)
            return
        }
        val generation = nextVisualCommitGeneration()
        settleAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = if (motionEnabled) 170L else 0L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                dragFraction = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) = Unit

                override fun onAnimationCancel(animation: android.animation.Animator) = Unit

                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (generation != visualCommitGeneration) return
                    settleAnimator = null
                    dragFraction = 0f
                    setMotionActive(false)
                    discardPendingHostIndexAfterCancelledGesture()
                }
            })
            start()
        }
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

    private fun updateSeekFromTouch(
        x: Float,
        y: Float,
    ) {
        val local = pointToFrontProgressLocal(x, y) ?: return
        val halfWidth = frame.cardWidthPx * 0.5f
        val artworkSize = (frame.cardWidthPx - frame.artworkInsetHorizontalPx * 2f).coerceAtLeast(1f)
        val left = -halfWidth + frame.artworkInsetHorizontalPx
        val position = ((local.first - left) / artworkSize).coerceIn(0f, 1f)
        val value = seekRangeStart + position * (seekRangeEnd - seekRangeStart)
        onSeekValueChange?.invoke(value)
    }

    private fun pointToFrontProgressLocal(
        x: Float,
        y: Float,
    ): Pair<Float, Float>? {
        val local = pointToFrontCardLocal(x, y) ?: return null
        val halfWidth = frame.cardWidthPx * 0.5f
        val halfHeight = frame.cardHeightPx * 0.5f
        val artworkSize = (frame.cardWidthPx - frame.artworkInsetHorizontalPx * 2f).coerceAtLeast(1f)
        val left = -halfWidth + frame.artworkInsetHorizontalPx
        val right = left + artworkSize
        val top = halfHeight - dp(46f)
        val bottom = halfHeight - dp(28f)
        return local.takeIf { it.first in left..right && it.second in top..bottom }
    }

    private fun pointToFrontCardLocal(
        x: Float,
        y: Float,
    ): Pair<Float, Float>? {
        val pose = frontIdlePose()
        val halfWidth = frame.cardWidthPx * 0.5f
        val halfHeight = frame.cardHeightPx * 0.5f
        val centerX = width * 0.5f + pose.translationX
        val centerY = halfHeight + pose.translationY
        val dx = x - centerX
        val dy = y - centerY
        val radians = pose.rotationZ / 180f * PI.toFloat()
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        val localX = (dx * cos + dy * sin) / pose.scale
        val localY = (-dx * sin + dy * cos) / pose.scale
        return if (localX in -halfWidth..halfWidth && localY in -halfHeight..halfHeight) {
            localX to localY
        } else {
            null
        }
    }

    private fun bitmapFor(song: Song): Bitmap? {
        val uri = song.albumArtUri ?: return null
        val key = decodeTarget.memoryCacheKey(uri)
        bitmapByKey[key]?.let { cached ->
            if (!CoverFlowBitmaps.isPollutedThumbnail(cached)) return cached
            bitmapByKey.remove(key)
        }
        CoverFlowBitmaps.memoryBitmap(uri, decodeTarget)?.let { cached ->
            if (!CoverFlowBitmaps.isPollutedThumbnail(cached)) {
                bitmapByKey[key] = cached
                return cached
            }
            MicaImageLoaders.evictCoverMemory(uri, decodeTarget)
        }
        val loadGeneration = artworkLoadGeneration
        val loadToken = "$loadGeneration:$key"
        if (pendingLoads.add(loadToken)) {
            val activeTarget = decodeTarget
            scope.launch {
                try {
                    val loaded = CoverFlowBitmaps.ensureLoaded(context, uri, activeTarget)
                    if (
                        loaded != null &&
                        shouldAcceptArtworkLoad(
                            requestGeneration = loadGeneration,
                            activeGeneration = artworkLoadGeneration,
                            requestTarget = activeTarget,
                            activeTarget = decodeTarget,
                            bitmapKey = key,
                            retainedKeys = retainedBitmapKeys(),
                        )
                    ) {
                        bitmapByKey[key] = loaded
                        invalidate()
                    }
                } finally {
                    pendingLoads.remove(loadToken)
                }
            }
        }
        return null
    }

    private fun centerCropSrc(
        bitmap: Bitmap,
        dstWidth: Float,
        dstHeight: Float,
        out: Rect,
    ) {
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            out.set(0, 0, bitmapWidth, bitmapHeight)
            return
        }
        val dstRatio = dstWidth / dstHeight
        val srcRatio = bitmapWidth.toFloat() / bitmapHeight
        if (srcRatio > dstRatio) {
            val cropWidth = (bitmapHeight * dstRatio).toInt().coerceAtMost(bitmapWidth)
            val cropX = (bitmapWidth - cropWidth) / 2
            out.set(cropX, 0, cropX + cropWidth, bitmapHeight)
        } else {
            val cropHeight = (bitmapWidth / dstRatio).toInt().coerceAtMost(bitmapHeight)
            val cropY = (bitmapHeight - cropHeight) / 2
            out.set(0, cropY, bitmapWidth, cropY + cropHeight)
        }
    }

    private fun preloadWindow() {
        pruneBitmapWindow()
        for (offset in -1..3) {
            val song = queue.getOrNull(logicalCenter + offset) ?: continue
            bitmapFor(song)
        }
    }

    private fun retainedBitmapKeys(): Set<String> = retainedArtworkKeys(
        queue = queue,
        centerIndex = logicalCenter,
        visibleOffsets = -1..3,
        decodeTarget = decodeTarget,
        extraIndices = listOfNotNull(
            pendingHostIndex,
            pendingPlayQueueIndex,
            awaitingCommittedPlayIndex,
            lastDispatchedPlayIndex,
        ),
        extraSongs = activeTransitionCards.map { it.song },
    )

    private fun pruneBitmapWindow() {
        bitmapByKey.keys.retainAll(retainedBitmapKeys())
    }

    private fun applyAlpha(argb: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return argb and 0x00FFFFFF or (a shl 24)
    }

    private fun shouldRunSpectrumLoop(): Boolean =
        trackAnimator != null ||
            settleAnimator != null ||
            (steadyCards().any { it.showProgress } &&
                activeTransitionCards.isEmpty() &&
                spectrumEnabled &&
                isPlaying)

    private fun updateSpectrumLoop() {
        if (shouldRunSpectrumLoop()) {
            scheduleSpectrumLoop()
        } else if (spectrumLoopScheduled) {
            removeCallbacks(spectrumInvalidator)
            spectrumLoopScheduled = false
        }
    }

    private fun scheduleSpectrumLoop() {
        if (spectrumLoopScheduled) return
        spectrumLoopScheduled = true
        postOnAnimation(spectrumInvalidator)
    }

    private fun resetSpectrumDisplayLevels() {
        for (index in spectrumDisplayLevels.indices) {
            spectrumDisplayLevels[index] = 0f
        }
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

    private fun Int.coerceInValidRange(): Int =
        if (queue.isEmpty()) 0 else coerceIn(0, queue.lastIndex)

    private fun dp(value: Float): Float = value * density

    override fun onDetachedFromWindow() {
        cancelAnimators()
        removeCallbacks(spectrumInvalidator)
        spectrumLoopScheduled = false
        clearShadowBitmapCache()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    fun release() {
        cancelAnimators()
        removeCallbacks(spectrumInvalidator)
        spectrumLoopScheduled = false
        bitmapByKey.clear()
        pendingLoads.clear()
        clearShadowBitmapCache()
        onSeekValueChange = null
        onSeekFinished = null
        onPlayQueueIndex = null
        onPrevious = null
        onNext = null
        onCoverLongPress = null
        onMotionActiveChanged = null
        setMotionActive(false)
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction.coerceIn(0f, 1f)
