package com.mica.music.ui.theme

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val DynamicArtworkOverscan = 1.3f
private const val DynamicArtworkNormalSaturation = 2.5f
private const val DynamicArtworkReducedSaturation = 3.5f
private const val DynamicArtworkBlurRadius = 25
private const val DynamicArtworkTransitionMs = 1_000L
private const val DynamicArtworkRotationAMs = 120_000L
private const val DynamicArtworkRotationBMs = 90_000L
private const val DynamicArtworkRotationCMs = 70_000L
private const val DynamicArtworkFrameDelayMs = 42L
private const val DynamicArtworkMaxBufferEntries = 4

internal data class DynamicArtworkTargetSize(
    val width: Int,
    val height: Int,
)

internal object DynamicArtworkBackgroundMath {
    fun normalScaleFactor(densityDpi: Int): Float =
        if (densityDpi < 420) 24f else 16f

    fun reducedScaleFactor(densityDpi: Int): Float =
        if (densityDpi < 420) 72f else 48f

    fun targetSize(
        viewWidth: Int,
        viewHeight: Int,
        scaleFactor: Float,
    ): DynamicArtworkTargetSize =
        DynamicArtworkTargetSize(
            width = (viewWidth * DynamicArtworkOverscan / scaleFactor)
                .roundToInt()
                .coerceAtLeast(1),
            height = (viewHeight * DynamicArtworkOverscan / scaleFactor)
                .roundToInt()
                .coerceAtLeast(1),
        )
}

internal object DynamicArtworkMesh {
    const val Cols = 5
    const val Rows = 5
    const val VertexCount = (Cols + 1) * (Rows + 1) * 2

    fun generate(seed: Long, strength: Float): FloatArray {
        val random = Random(seed)
        val phaseX = random.nextFloat() * Tau
        val phaseY = random.nextFloat() * Tau
        val out = FloatArray(VertexCount)
        for (row in 0..Rows) {
            for (col in 0..Cols) {
                val index = row * (Cols + 1) * 2 + col * 2
                val baseX = col / Cols.toFloat()
                val baseY = row / Rows.toFloat()
                val edgeFalloff = (
                    sin((baseX * Pi).toDouble()).toFloat() *
                        sin((baseY * Pi).toDouble()).toFloat()
                    ).coerceAtLeast(0f)
                val jitterX = (random.nextFloat() * 2f - 1f) * strength * edgeFalloff
                val jitterY = (random.nextFloat() * 2f - 1f) * strength * edgeFalloff
                val waveX = sin((baseY * Tau * 1.35f + phaseX).toDouble()).toFloat() *
                    strength *
                    0.45f *
                    edgeFalloff
                val waveY = sin((baseX * Tau * 1.15f + phaseY).toDouble()).toFloat() *
                    strength *
                    0.45f *
                    edgeFalloff
                out[index] = (baseX + jitterX + waveX).coerceIn(0f, 1f)
                out[index + 1] = (baseY + jitterY + waveY).coerceIn(0f, 1f)
            }
        }
        return out
    }

    private const val Pi = 3.1415927f
    private const val Tau = 6.2831855f
}

internal class DynamicArtworkBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val densityDpi = resources.configuration.densityDpi
    private val normalScaleFactor = DynamicArtworkBackgroundMath.normalScaleFactor(densityDpi)
    private val reducedScaleFactor = DynamicArtworkBackgroundMath.reducedScaleFactor(densityDpi)

    private val bufferCache = LinkedHashMap<DynamicArtworkTargetSize, DynamicArtworkBitmapPair>()
    private val blurScratch = DynamicArtworkBlurScratch()
    private val drawPath = Path()
    private val drawMatrix = Matrix()
    private val shaderMatrix = Matrix()
    private val colorMatrix = ColorMatrix()
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val previousPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meshPixels = FloatArray(DynamicArtworkMesh.VertexCount)

    private var currentArtwork: Bitmap? = null
    private var currentArtworkKey: Long = Long.MIN_VALUE
    private var currentShader: BitmapShader? = null
    private var currentShaderBitmap: Bitmap? = null
    private var previousShader: BitmapShader? = null
    private var previousShaderBitmap: Bitmap? = null
    private var fallbackColor: Int = 0xff202020.toInt()
    private var darkScrim: Boolean = true
    private var motionEnabled: Boolean = true
    private var reducedEffects: Boolean = false
    private var scaleFactor: Float = normalScaleFactor
    private var saturation: Float = DynamicArtworkNormalSaturation
    private var artworkSeed: Long = 0L
    private var meshVertices: FloatArray = DynamicArtworkMesh.generate(0L, meshStrength())
    private var isViewAttached: Boolean = false
    private var windowVisible: Boolean = false
    private var transitionCanceled: Boolean = false

    private val transitionAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = DynamicArtworkTransitionMs
        interpolator = PathInterpolator(0f, 0f, 0.3f, 1f)
        addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    transitionCanceled = false
                }

                override fun onAnimationCancel(animation: Animator) {
                    transitionCanceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!transitionCanceled) clearPreviousFrame()
                    invalidate()
                }
            },
        )
    }

    private val rotateA = ValueAnimator.ofFloat(0f, -360f).looping(DynamicArtworkRotationAMs)
    private val rotateB = ValueAnimator.ofFloat(0f, 360f).looping(DynamicArtworkRotationBMs)
    private val rotateC = ValueAnimator.ofFloat(0f, 360f).looping(DynamicArtworkRotationCMs)

    init {
        setWillNotDraw(false)
        currentPaint.alpha = 255
        previousPaint.alpha = 255
    }

    fun setArtwork(bitmap: Bitmap?, key: Long) {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            clearArtwork()
            return
        }
        if (currentArtwork === bitmap && currentArtworkKey == key) return

        transitionAnimator.cancel()
        val hasPreviousFrame = captureCurrentFrameForTransition()
        currentArtwork = bitmap
        currentArtworkKey = key
        artworkSeed = key
        meshVertices = DynamicArtworkMesh.generate(artworkSeed, meshStrength())
        clearCurrentShader()
        if (hasPreviousFrame && motionEnabled && !reducedEffects) {
            transitionAnimator.start()
        } else {
            clearPreviousFrame()
        }
        updateAnimatorState()
        invalidate()
    }

    fun clearArtwork() {
        transitionAnimator.cancel()
        currentArtwork = null
        currentArtworkKey = Long.MIN_VALUE
        clearCurrentShader()
        clearPreviousFrame()
        pauseRotationAnimators()
        invalidate()
    }

    fun setFallbackColor(color: Int) {
        if (fallbackColor == color) return
        fallbackColor = color
        if (currentArtwork == null) invalidate()
    }

    fun setDarkScrim(isDark: Boolean) {
        if (darkScrim == isDark) return
        darkScrim = isDark
        clearCurrentShader()
        invalidate()
    }

    fun setMotionEnabled(enabled: Boolean) {
        if (motionEnabled == enabled) return
        motionEnabled = enabled
        if (!enabled) {
            transitionAnimator.cancel()
            clearPreviousFrame()
        }
        updateAnimatorState()
        invalidate()
    }

    fun setReducedEffects(reduced: Boolean) {
        if (reducedEffects == reduced) return
        reducedEffects = reduced
        scaleFactor = if (reduced) reducedScaleFactor else normalScaleFactor
        saturation = if (reduced) DynamicArtworkReducedSaturation else DynamicArtworkNormalSaturation
        meshVertices = DynamicArtworkMesh.generate(artworkSeed, meshStrength())
        clearCurrentShader()
        updateAnimatorState()
        invalidate()
    }

    fun release() {
        transitionAnimator.cancel()
        rotateA.cancel()
        rotateB.cancel()
        rotateC.cancel()
        clearCurrentShader()
        clearPreviousFrame()
        recycleBuffers()
        currentArtwork = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isViewAttached = true
        windowVisible = windowVisibility == VISIBLE
        updateAnimatorState()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        release()
        isViewAttached = false
        windowVisible = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimatorState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        updateAnimatorState()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        drawPath.reset()
        drawPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        clearCurrentShader()
        clearPreviousFrame()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val artwork = currentArtwork
        if (artwork == null || artwork.isRecycled || width <= 0 || height <= 0) {
            drawFallback(canvas)
            return
        }

        if (currentShader == null || shouldAnimateTexture()) {
            rebuildShader(artwork)
        }

        val oldShader = previousShader
        if (oldShader != null) {
            previousPaint.shader = oldShader
            previousPaint.alpha = 255
            canvas.drawPath(drawPath, previousPaint)
        }

        val newShader = currentShader
        if (newShader != null) {
            currentPaint.shader = newShader
            currentPaint.alpha = currentFrameAlpha()
            canvas.drawPath(drawPath, currentPaint)
        } else {
            drawFallback(canvas)
        }

        if (shouldScheduleNextFrame()) {
            postInvalidateDelayed(DynamicArtworkFrameDelayMs)
        }
    }

    private fun rebuildShader(artwork: Bitmap) {
        val targetSize = DynamicArtworkBackgroundMath.targetSize(width, height, scaleFactor)
        val pair = bufferFor(targetSize)
        val source = pair.nextWritable()
        source.eraseColor(fallbackColor)

        drawArtworkLayers(
            canvas = Canvas(source),
            artwork = artwork,
            targetWidth = targetSize.width,
            targetHeight = targetSize.height,
        )

        val textured = applyMesh(source, pair.other(source))
        applyScrims(Canvas(textured))
        DynamicArtworkBoxBlur.blurInPlace(textured, DynamicArtworkBlurRadius, blurScratch)
        currentShaderBitmap = textured
        currentShader = createShader(textured)
    }

    private fun drawArtworkLayers(
        canvas: Canvas,
        artwork: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ) {
        colorMatrix.setSaturation(saturation)
        artworkPaint.colorFilter = ColorMatrixColorFilter(colorMatrix)

        val side = (max(targetWidth, targetHeight) * DynamicArtworkOverscan).roundToInt()
        val scale = side / artwork.height.toFloat()
        val center = side / 2f
        val translateX = -((side - targetWidth) / 2f)
        val translateY = -((side - targetHeight) / 2f)

        drawArtworkLayer(
            canvas = canvas,
            artwork = artwork,
            scale = scale,
            center = center,
            translateX = translateX,
            translateY = translateY,
            angle = angleOf(rotateA),
        )
        drawArtworkLayer(
            canvas = canvas,
            artwork = artwork,
            scale = scale,
            center = center,
            translateX = translateX - 0.95f * targetWidth,
            translateY = translateY - 0.70f * targetHeight,
            angle = angleOf(rotateB),
        )
        drawArtworkLayer(
            canvas = canvas,
            artwork = artwork,
            scale = scale,
            center = center,
            translateX = translateX - 1.00f * targetWidth,
            translateY = translateY + 0.70f * targetHeight,
            angle = angleOf(rotateC),
            extraRotation = angleOf(rotateC),
            extraPivotX = targetWidth / 2f,
            extraPivotY = targetHeight / 2f,
        )
        artworkPaint.colorFilter = null
    }

    private fun drawArtworkLayer(
        canvas: Canvas,
        artwork: Bitmap,
        scale: Float,
        center: Float,
        translateX: Float,
        translateY: Float,
        angle: Float,
        extraRotation: Float = 0f,
        extraPivotX: Float = 0f,
        extraPivotY: Float = 0f,
    ) {
        drawMatrix.reset()
        drawMatrix.setScale(scale, scale)
        drawMatrix.postRotate(angle, center, center)
        drawMatrix.postTranslate(translateX, translateY)
        if (extraRotation != 0f) {
            drawMatrix.postRotate(extraRotation, extraPivotX, extraPivotY)
        }
        canvas.drawBitmap(artwork, drawMatrix, artworkPaint)
    }

    private fun applyMesh(source: Bitmap, destination: Bitmap): Bitmap {
        destination.eraseColor(Color.TRANSPARENT)
        for (row in 0..DynamicArtworkMesh.Rows) {
            for (col in 0..DynamicArtworkMesh.Cols) {
                val index = row * (DynamicArtworkMesh.Cols + 1) * 2 + col * 2
                meshPixels[index] = meshVertices[index] * source.width
                meshPixels[index + 1] = meshVertices[index + 1] * source.height
            }
        }
        Canvas(destination).drawBitmapMesh(
            source,
            DynamicArtworkMesh.Cols,
            DynamicArtworkMesh.Rows,
            meshPixels,
            0,
            null,
            0,
            null,
        )
        return destination
    }

    private fun applyScrims(canvas: Canvas) {
        scrimPaint.style = Paint.Style.FILL
        scrimPaint.color = if (darkScrim) 0x80000000.toInt() else 0x4d000000
        canvas.drawPaint(scrimPaint)
        scrimPaint.color = if (darkScrim) 0x0dffffff else 0x1affffff
        canvas.drawPaint(scrimPaint)
    }

    private fun createShader(bitmap: Bitmap): BitmapShader =
        BitmapShader(bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
            shaderMatrix.reset()
            shaderMatrix.setScale(scaleFactor, scaleFactor)
            val extraX = (bitmap.width * scaleFactor - width).coerceAtLeast(0f) / 2f
            val extraY = (bitmap.height * scaleFactor - height).coerceAtLeast(0f) / 2f
            shaderMatrix.postTranslate(-extraX, -extraY)
            setLocalMatrix(shaderMatrix)
        }

    private fun currentFrameAlpha(): Int {
        val previous = previousShader
        if (previous == null || !transitionAnimator.isRunning) return 255
        val fadeOut = transitionAnimator.animatedValue as? Float ?: 0f
        return ((1f - fadeOut).coerceIn(0f, 1f) * 255f).roundToInt()
    }

    private fun captureCurrentFrameForTransition(): Boolean {
        val source = currentShaderBitmap ?: return false
        if (source.isRecycled) return false
        val copy = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: return false
        clearPreviousFrame()
        previousShaderBitmap = copy
        previousShader = createShader(copy)
        previousPaint.shader = previousShader
        previousPaint.alpha = 255
        return true
    }

    private fun clearCurrentShader() {
        currentShader = null
        currentShaderBitmap = null
        currentPaint.shader = null
    }

    private fun clearPreviousFrame() {
        previousShader = null
        previousPaint.shader = null
        previousShaderBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        previousShaderBitmap = null
    }

    private fun drawFallback(canvas: Canvas) {
        canvas.drawColor(fallbackColor)
    }

    private fun bufferFor(size: DynamicArtworkTargetSize): DynamicArtworkBitmapPair {
        val existing = bufferCache[size]
        if (existing != null) return existing

        val pair = DynamicArtworkBitmapPair(
            first = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888),
            second = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888),
        )
        bufferCache[size] = pair
        trimBufferCache()
        return pair
    }

    private fun trimBufferCache() {
        while (bufferCache.size > DynamicArtworkMaxBufferEntries) {
            val iterator = bufferCache.entries.iterator()
            if (!iterator.hasNext()) return
            val entry = iterator.next()
            iterator.remove()
            entry.value.recycle()
        }
    }

    private fun recycleBuffers() {
        bufferCache.values.forEach { it.recycle() }
        bufferCache.clear()
        blurScratch.clear()
    }

    private fun shouldAnimateTexture(): Boolean =
        isActiveForRendering() && motionEnabled && !reducedEffects

    private fun shouldScheduleNextFrame(): Boolean =
        shouldAnimateTexture() || transitionAnimator.isRunning

    private fun isActiveForRendering(): Boolean =
        isViewAttached && windowVisible && isShown && visibility == VISIBLE

    private fun updateAnimatorState() {
        if (shouldAnimateTexture() && currentArtwork != null) {
            startRotationAnimators()
        } else {
            pauseRotationAnimators()
        }
    }

    private fun startRotationAnimators() {
        listOf(rotateA, rotateB, rotateC).forEach { animator ->
            when {
                !animator.isStarted -> animator.start()
                animator.isPaused -> animator.resume()
            }
        }
    }

    private fun pauseRotationAnimators() {
        listOf(rotateA, rotateB, rotateC).forEach { animator ->
            if (animator.isStarted && !animator.isPaused) animator.pause()
        }
    }

    private fun angleOf(animator: ValueAnimator): Float =
        animator.animatedValue as? Float ?: 0f

    private fun meshStrength(): Float =
        if (reducedEffects) 0.03f else 0.08f
}

private data class DynamicArtworkBitmapPair(
    val first: Bitmap,
    val second: Bitmap,
) {
    private var useFirstNext = true

    fun nextWritable(): Bitmap {
        useFirstNext = !useFirstNext
        return if (useFirstNext) first else second
    }

    fun other(bitmap: Bitmap): Bitmap =
        if (bitmap === first) second else first

    fun recycle() {
        if (!first.isRecycled) first.recycle()
        if (!second.isRecycled) second.recycle()
    }
}

private class DynamicArtworkBlurScratch {
    var pixels: IntArray = IntArray(0)
        private set
    var temp: IntArray = IntArray(0)
        private set

    fun ensure(size: Int) {
        if (pixels.size < size) pixels = IntArray(size)
        if (temp.size < size) temp = IntArray(size)
    }

    fun clear() {
        pixels = IntArray(0)
        temp = IntArray(0)
    }
}

private object DynamicArtworkBoxBlur {
    fun blurInPlace(bitmap: Bitmap, radius: Int, scratch: DynamicArtworkBlurScratch) {
        if (radius <= 0 || bitmap.width <= 1 || bitmap.height <= 1) return
        val width = bitmap.width
        val height = bitmap.height
        val size = width * height
        scratch.ensure(size)
        val pixels = scratch.pixels
        val temp = scratch.temp
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        horizontal(src = pixels, dst = temp, width = width, height = height, radius = radius)
        vertical(src = temp, dst = pixels, width = width, height = height, radius = radius)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun horizontal(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val color = src[row + offset.coerceIn(0, width - 1)]
                a += color ushr 24
                r += color shr 16 and 0xff
                g += color shr 8 and 0xff
                b += color and 0xff
            }
            for (x in 0 until width) {
                dst[row + x] = packColor(a / window, r / window, g / window, b / window)
                val remove = src[row + (x - radius).coerceIn(0, width - 1)]
                val add = src[row + (x + radius + 1).coerceIn(0, width - 1)]
                a += (add ushr 24) - (remove ushr 24)
                r += (add shr 16 and 0xff) - (remove shr 16 and 0xff)
                g += (add shr 8 and 0xff) - (remove shr 8 and 0xff)
                b += (add and 0xff) - (remove and 0xff)
            }
        }
    }

    private fun vertical(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val color = src[offset.coerceIn(0, height - 1) * width + x]
                a += color ushr 24
                r += color shr 16 and 0xff
                g += color shr 8 and 0xff
                b += color and 0xff
            }
            for (y in 0 until height) {
                dst[y * width + x] = packColor(a / window, r / window, g / window, b / window)
                val remove = src[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                a += (add ushr 24) - (remove ushr 24)
                r += (add shr 16 and 0xff) - (remove shr 16 and 0xff)
                g += (add shr 8 and 0xff) - (remove shr 8 and 0xff)
                b += (add and 0xff) - (remove and 0xff)
            }
        }
    }

    private fun packColor(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
}

private fun ValueAnimator.looping(durationMs: Long): ValueAnimator =
    apply {
        duration = durationMs
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
    }
