package com.mica.music.ui.screens.player.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import com.mica.music.data.ParticleCoverTuning
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.min
import kotlin.math.pow

internal class ParticleCoverRenderer(context: Context) {

    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val quadBuffer = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f,
    ).toFloatBuffer()
    private val edgeParticles = buildEdgeParticles()
    private val transitionParticles = buildTransitionParticles()

    private var width = 1
    private var height = 1

    private var quadProgram = 0
    private var particleProgram = 0
    private var currentTexture = 0
    private var previousTexture = 0
    private var currentSongId: String? = null
    private var currentHasBitmap = false
    private var currentBitmapGeneration = -1
    private var currentFallbackColor = 0
    private var transitionStartedAtMs = 0L
    private var transitionStartDisintegration = 0f
    private var fullCoverHoldUntilMs = 0L
    private var scatterDirection = 1f
    private var tuning = ParticleCoverTuning()
    private var playbackDisintegrationProgress: Float? = null
    private var lyricsProgress = 0f
    private var coverCenterX = 0f
    private var coverCenterY = 0f
    private var coverHalfWidth = 1f
    private var coverHalfHeight = 1f

    fun onSurfaceCreated() {
        quadProgram = createProgram(QuadVertexShader, QuadFragmentShader)
        particleProgram = createProgram(ParticleVertexShader, ParticleFragmentShader)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun onSurfaceChanged(surfaceWidth: Int, surfaceHeight: Int) {
        width = surfaceWidth.coerceAtLeast(1)
        height = surfaceHeight.coerceAtLeast(1)
    }

    fun setCover(
        songId: String,
        bitmap: Bitmap?,
        fallbackColor: Int,
        motionEnabled: Boolean,
    ) {
        val generation = bitmap?.generationId ?: -1
        val hasBitmap = bitmap != null
        if (songId == currentSongId) {
            if (
                currentTexture == 0 ||
                currentHasBitmap != hasBitmap ||
                currentBitmapGeneration != generation ||
                (!hasBitmap && currentFallbackColor != fallbackColor)
            ) {
                deleteTexture(currentTexture)
                currentTexture = createTexture(bitmap, fallbackColor)
                currentHasBitmap = hasBitmap
                currentBitmapGeneration = generation
                currentFallbackColor = fallbackColor
            }
            return
        }

        deleteTexture(previousTexture)
        previousTexture = currentTexture
        currentTexture = createTexture(bitmap, fallbackColor)
        currentSongId = songId
        currentHasBitmap = hasBitmap
        currentBitmapGeneration = generation
        currentFallbackColor = fallbackColor
        scatterDirection = if ((songId.hashCode() and 1) == 0) 1f else -1f
        transitionStartedAtMs = if (motionEnabled && previousTexture != 0) {
            transitionStartDisintegration = playbackDisintegrationProgress ?: 0f
            fullCoverHoldUntilMs = 0L
            SystemClock.uptimeMillis()
        } else {
            transitionStartDisintegration = 0f
            fullCoverHoldUntilMs = if (previousTexture == 0) {
                SystemClock.uptimeMillis() + FullCoverHoldAfterRegroupMs
            } else {
                0L
            }
            0L
        }
        if (!motionEnabled) {
            deleteTexture(previousTexture)
            previousTexture = 0
        }
    }

    fun setTuning(next: ParticleCoverTuning) {
        tuning = next
    }

    fun setPlaybackDisintegrationProgress(progress: Float?) {
        playbackDisintegrationProgress = progress?.coerceIn(0f, 1f)
    }

    fun setLyricsProgress(progress: Float) {
        lyricsProgress = progress.coerceIn(0f, 1f)
    }

    fun setCoverTransform(centerX: Float, centerY: Float, halfWidth: Float, halfHeight: Float) {
        coverCenterX = centerX.coerceIn(-1.5f, 1.5f)
        coverCenterY = centerY.coerceIn(-1.5f, 1.5f)
        coverHalfWidth = halfWidth.coerceIn(0.01f, 1.5f)
        coverHalfHeight = halfHeight.coerceIn(0.01f, 1.5f)
    }

    fun render() {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (currentTexture == 0) return

        val elapsed = transitionElapsedMs()
        val edgeAlphaPeak = EdgeAlphaPeak * tuning.edgeParticleAlpha
        val stableBreakup = StableBreakup * tuning.erosionScale
        if (previousTexture == 0 || elapsed >= TransitionDurationMs) {
            if (previousTexture != 0) {
                deleteTexture(previousTexture)
                previousTexture = 0
                fullCoverHoldUntilMs = SystemClock.uptimeMillis() + FullCoverHoldAfterRegroupMs
            }
            if (SystemClock.uptimeMillis() < fullCoverHoldUntilMs && lyricsProgress <= 0.01f) {
                drawQuad(
                    currentTexture,
                    alpha = 1f,
                    scale = 1f,
                    erosion = 0f,
                    breakup = 0f,
                    edgeOnlyBreakup = 0f,
                    residue = false,
                )
                return
            }
            val songProgress = playbackDisintegrationProgress
            if (songProgress != null) {
                drawPlaybackDisintegration(songProgress, edgeAlphaPeak, lyricsProgress)
                return
            }
            drawQuad(
                currentTexture,
                alpha = 1f,
                scale = 1f,
                erosion = 0f,
                breakup = 0f,
                edgeOnlyBreakup = 0f,
                residue = false,
            )
            return
        }

        if (lyricsProgress > 0.01f) {
            drawLyricsTextureTransition(elapsed, edgeAlphaPeak)
            return
        }

        when {
            elapsed < EdgeBoostMs -> {
                val p = outCubic(elapsed.toFloat() / EdgeBoostMs)
                val startBreakup = transitionStartDisintegration.coerceIn(0f, 1f)
                val oldMostlyBroken = startBreakup >= 0.86f
                val acceleratedBreakup = (startBreakup + (1f - startBreakup) * p * 0.55f)
                    .coerceIn(0f, 1f)
                val scale = 1f - 0.035f * p
                if (!oldMostlyBroken) {
                    drawQuad(
                        texture = previousTexture,
                        alpha = 1f,
                        scale = scale,
                        erosion = 0.018f + 0.025f * p + 0.22f * startBreakup,
                        breakup = acceleratedBreakup,
                        edgeOnlyBreakup = (1f - acceleratedBreakup * 1.35f).coerceIn(0f, 1f),
                        residue = false,
                    )
                }
                drawParticles(
                    particleSet = transitionParticles,
                    texture = previousTexture,
                    travel = (StableEdgeTravel * tuning.edgeTravelScale + startBreakup * 0.34f + p * 0.28f)
                        .coerceIn(0f, 1f),
                    alpha = edgeAlphaPeak * (1.35f + 1.85f * p),
                    wobble = TransitionWobble * (0.8f + p * 1.2f),
                    scale = scale,
                    density = maxOf(0.95f, tuning.transitionParticleDensity),
                    pointScale = 1.0f + acceleratedBreakup * 0.55f,
                    feather = tuning.featherScale,
                )
            }
            elapsed < ScatterEndMs -> {
                val linear = ((elapsed - EdgeBoostMs).toFloat() / (ScatterEndMs - EdgeBoostMs))
                    .coerceIn(0f, 1f)
                val p = outCubic(linear)
                val startBreakup = transitionStartDisintegration.coerceIn(0f, 1f)
                val oldBreakup = (startBreakup + (1f - startBreakup) * (0.55f + p * 0.45f))
                    .coerceIn(0f, 1f)
                if (oldBreakup < 0.98f) {
                    val erosion = (0.050f + 0.300f * oldBreakup) * tuning.erosionScale
                    drawQuad(
                        texture = previousTexture,
                        alpha = (1f - p * 0.55f).coerceIn(0f, 1f),
                        scale = 0.965f,
                        erosion = erosion,
                        breakup = oldBreakup,
                        edgeOnlyBreakup = 0f,
                        residue = false,
                    )
                }
                drawParticles(
                    particleSet = transitionParticles,
                    texture = previousTexture,
                    travel = (startBreakup * 0.36f + p * 0.64f).coerceIn(0f, 1f),
                    alpha = edgeAlphaPeak * TransitionAlphaScale,
                    wobble = TransitionWobble,
                    scale = 1f,
                    density = maxOf(0.95f, tuning.transitionParticleDensity),
                    pointScale = 1.08f + oldBreakup * 0.42f,
                    feather = tuning.featherScale,
                )
            }
            elapsed < GatherEndMs -> {
                val p = easeInOutCubic(
                    (elapsed - ScatterEndMs).toFloat() / (GatherEndMs - ScatterEndMs),
                )
                val lyrics = lyricsProgress.coerceIn(0f, 1f)
                drawParticles(
                    particleSet = transitionParticles,
                    texture = previousTexture,
                    textureB = currentTexture,
                    textureMix = p,
                    travel = if (lyrics > 0.01f) LyricsParticleTravel else 1f - p,
                    alpha = edgeAlphaPeak * TransitionAlphaScale,
                    wobble = TransitionWobble,
                    scale = 1f,
                    density = tuning.transitionParticleDensity,
                    lyrics = lyrics,
                )
            }
            else -> {
                val p = easeInOutCubic(
                    (elapsed - GatherEndMs).toFloat() / (TransitionDurationMs - GatherEndMs),
                )
                val lyrics = lyricsProgress.coerceIn(0f, 1f)
                drawParticles(
                    particleSet = transitionParticles,
                    texture = currentTexture,
                    travel = LyricsParticleTravel * lyrics,
                    alpha = edgeAlphaPeak * TransitionAlphaScale * (1f - p),
                    wobble = TransitionWobble * (1f - p),
                    scale = 1f,
                    density = tuning.transitionParticleDensity,
                    lyrics = lyrics,
                )
                if (lyrics <= 0.01f) {
                    drawQuad(
                        texture = currentTexture,
                        alpha = p,
                        scale = 0.985f + 0.015f * p,
                        erosion = 0.018f,
                        breakup = 1f - p,
                        edgeOnlyBreakup = 0f,
                        residue = false,
                    )
                    drawQuad(
                        texture = currentTexture,
                        alpha = StableEdgeResidueAlpha * p,
                        scale = 0.985f + 0.015f * p,
                        erosion = 0.018f,
                        breakup = 1f - p,
                        edgeOnlyBreakup = 0f,
                        residue = true,
                    )
                }
            }
        }
    }

    fun isAnimating(): Boolean =
        (previousTexture != 0 && transitionElapsedMs() < TransitionDurationMs) ||
            playbackDisintegrationProgress != null

    private fun drawLyricsTextureTransition(
        elapsed: Long,
        edgeAlphaPeak: Float,
    ) {
        val p = easeInOutCubic(elapsed.toFloat() / TransitionDurationMs)
        val lyrics = lyricsProgress.coerceIn(0f, 1f)
        drawParticles(
            particleSet = transitionParticles,
            texture = previousTexture,
            textureB = currentTexture,
            textureMix = p,
            travel = LyricsParticleTravel,
            alpha = edgeAlphaPeak * TransitionAlphaScale,
            wobble = 0f,
            scale = 1f,
            density = tuning.transitionParticleDensity,
            pointScale = 1.0f,
            feather = tuning.featherScale,
            lyrics = lyrics,
        )
    }

    private fun drawPlaybackDisintegration(
        progress: Float,
        edgeAlphaPeak: Float,
        lyricsProgress: Float,
    ) {
        val acceleratedProgress = progress.coerceIn(0f, 1f)
        val lyrics = lyricsProgress.coerceIn(0f, 1f)
        val early = smoothStep(0.05f, 0.28f, acceleratedProgress)
        val playbackBreakup = smoothStep(0.10f, 0.78f, acceleratedProgress)
        val breakup = maxOf(playbackBreakup, lyrics)
        val late = smoothStep(0.72f, 0.98f, acceleratedProgress)
        val almostGone = smoothStep(0.84f, 1.0f, acceleratedProgress)
        val coverAlpha = ((1f - almostGone * 0.98f) * (1f - lyrics * 0.92f)).coerceIn(0f, 1f)
        val particleAlpha = (edgeAlphaPeak * (1.55f + breakup * 1.45f) * (1f - late * 0.18f) *
            (1f - lyrics * 0.28f))
            .coerceIn(0f, 1.6f)
        val travel = (StableEdgeTravel * tuning.edgeTravelScale + breakup * 0.34f + late * 0.06f +
            lyrics * LyricsParticleTravel)
            .coerceIn(0f, 1f)
        val erosion = (0.018f + early * 0.075f + breakup * 0.300f) * tuning.erosionScale
        drawQuad(
            texture = currentTexture,
            alpha = coverAlpha,
            scale = 1f - acceleratedProgress * 0.020f,
            erosion = erosion,
            breakup = (0.08f + breakup * 1.08f).coerceIn(0f, 1f),
            edgeOnlyBreakup = (1f - breakup * 1.35f).coerceIn(0f, 1f),
            residue = false,
        )
        drawParticles(
            particleSet = transitionParticles,
            texture = currentTexture,
            travel = travel,
            alpha = particleAlpha,
            wobble = TransitionWobble * (0.65f + breakup * 0.90f),
            scale = 1f,
            density = maxOf(0.95f, tuning.transitionParticleDensity) * (0.82f + breakup * 0.30f),
            pointScale = 1.05f + breakup * 0.52f,
            feather = tuning.featherScale,
            lyrics = lyrics,
        )
    }

    fun release() {
        deleteTexture(currentTexture)
        deleteTexture(previousTexture)
        currentTexture = 0
        previousTexture = 0
        if (quadProgram != 0) GLES20.glDeleteProgram(quadProgram)
        if (particleProgram != 0) GLES20.glDeleteProgram(particleProgram)
        quadProgram = 0
        particleProgram = 0
    }

    private fun transitionElapsedMs(): Long =
        if (transitionStartedAtMs == 0L) Long.MAX_VALUE else SystemClock.uptimeMillis() - transitionStartedAtMs

    private fun drawQuad(
        texture: Int,
        alpha: Float,
        scale: Float,
        erosion: Float,
        breakup: Float = 0f,
        edgeOnlyBreakup: Float = 1f,
        residue: Boolean = false,
    ) {
        if (alpha <= 0.001f || texture == 0) return
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(quadProgram)
        bindTexture(texture, GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(quadProgram, "uTexture"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(quadProgram, "uAlpha"), alpha.coerceIn(0f, 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(quadProgram, "uScale"), scale * CoverPlaneScale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(quadProgram, "uErosion"), erosion.coerceIn(0f, 0.32f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(quadProgram, "uNoise"), PlaneNoise)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(quadProgram, "uFeather"), MaskFeather * tuning.featherScale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(quadProgram, "uBreakup"), breakup.coerceIn(0f, 1f))
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(quadProgram, "uEdgeOnlyBreakup"),
            edgeOnlyBreakup.coerceIn(0f, 1f),
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(quadProgram, "uResidue"), if (residue) 1f else 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(quadProgram, "uCoverCenter"), coverCenterX, coverCenterY)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(quadProgram, "uCoverHalfSize"),
            coverHalfWidth,
            coverHalfHeight,
        )

        val aPosition = GLES20.glGetAttribLocation(quadProgram, "aPosition")
        val aUv = GLES20.glGetAttribLocation(quadProgram, "aUv")
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, QuadStrideBytes, quadBuffer)
        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, QuadStrideBytes, quadBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    private fun drawParticles(
        particleSet: ParticleSet,
        texture: Int,
        textureB: Int = texture,
        textureMix: Float = 0f,
        travel: Float,
        alpha: Float,
        wobble: Float,
        scale: Float,
        density: Float,
        pointScale: Float = 1f,
        feather: Float = 1f,
        lyrics: Float = 0f,
    ) {
        if (alpha <= 0.001f || texture == 0) return
        val drawCount = (particleSet.count * density.coerceIn(0.12f, 1.35f))
            .toInt()
            .coerceIn(1, particleSet.count)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(particleProgram)
        bindTexture(texture, GLES20.GL_TEXTURE0)
        bindTexture(textureB, GLES20.GL_TEXTURE1)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(particleProgram, "uTextureA"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(particleProgram, "uTextureB"), 1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uTextureMix"), textureMix.coerceIn(0f, 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uTravel"), travel.coerceIn(0f, 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uLyrics"), lyrics.coerceIn(0f, 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uAlpha"), alpha.coerceIn(0f, 2.5f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uScale"), scale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uWobble"), wobble)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uCoverScale"), CoverPlaneScale)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(particleProgram, "uPointScale"),
            PointScale * min(this.density, 1.8f) * pointScale,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(particleProgram, "uFeather"),
            feather.coerceIn(0.55f, 2.60f),
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uTime"), SystemClock.uptimeMillis() / 1000f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uDirection"), scatterDirection)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(particleProgram, "uCoverCenter"), coverCenterX, coverCenterY)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(particleProgram, "uCoverHalfSize"),
            coverHalfWidth,
            coverHalfHeight,
        )

        val aHome = GLES20.glGetAttribLocation(particleProgram, "aHome")
        val aScatter = GLES20.glGetAttribLocation(particleProgram, "aScatter")
        val aUv = GLES20.glGetAttribLocation(particleProgram, "aUv")
        val aSize = GLES20.glGetAttribLocation(particleProgram, "aSize")
        val aDetach = GLES20.glGetAttribLocation(particleProgram, "aDetach")
        val aSeed = GLES20.glGetAttribLocation(particleProgram, "aSeed")
        val buffer = particleSet.buffer
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(aHome)
        GLES20.glVertexAttribPointer(aHome, 3, GLES20.GL_FLOAT, false, ParticleStrideBytes, buffer)
        buffer.position(3)
        GLES20.glEnableVertexAttribArray(aScatter)
        GLES20.glVertexAttribPointer(aScatter, 3, GLES20.GL_FLOAT, false, ParticleStrideBytes, buffer)
        buffer.position(6)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, ParticleStrideBytes, buffer)
        buffer.position(8)
        GLES20.glEnableVertexAttribArray(aSize)
        GLES20.glVertexAttribPointer(aSize, 1, GLES20.GL_FLOAT, false, ParticleStrideBytes, buffer)
        buffer.position(9)
        GLES20.glEnableVertexAttribArray(aDetach)
        GLES20.glVertexAttribPointer(aDetach, 1, GLES20.GL_FLOAT, false, ParticleStrideBytes, buffer)
        buffer.position(10)
        GLES20.glEnableVertexAttribArray(aSeed)
        GLES20.glVertexAttribPointer(aSeed, 1, GLES20.GL_FLOAT, false, ParticleStrideBytes, buffer)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, drawCount)
        GLES20.glDisableVertexAttribArray(aHome)
        GLES20.glDisableVertexAttribArray(aScatter)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glDisableVertexAttribArray(aSize)
        GLES20.glDisableVertexAttribArray(aDetach)
        GLES20.glDisableVertexAttribArray(aSeed)
    }

    private fun createTexture(bitmap: Bitmap?, fallbackColor: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        bindTexture(texture, GLES20.GL_TEXTURE0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val uploadBitmap = bitmap?.takeIf { !it.isRecycled }?.asGlUploadBitmap()
        if (uploadBitmap != null) {
            runCatching {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, uploadBitmap, 0)
            }.onFailure {
                uploadFallbackColor(fallbackColor)
            }
            if (uploadBitmap !== bitmap) {
                uploadBitmap.recycle()
            }
        } else {
            uploadFallbackColor(fallbackColor)
        }
        return texture
    }

    private fun Bitmap.asGlUploadBitmap(): Bitmap? {
        val validConfig = config == Bitmap.Config.ARGB_8888 || config == Bitmap.Config.RGB_565
        if (validConfig && width == height) return this
        val source = if (validConfig) {
            this
        } else {
            runCatching { copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return null
        }
        return runCatching {
            val side = min(source.width, source.height).coerceAtLeast(1)
            val left = ((source.width - side) / 2).coerceAtLeast(0)
            val top = ((source.height - side) / 2).coerceAtLeast(0)
            val converted = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            Canvas(converted).drawBitmap(
                source,
                Rect(left, top, left + side, top + side),
                Rect(0, 0, side, side),
                BitmapUploadPaint,
            )
            if (source !== this) source.recycle()
            converted
        }.getOrNull()
    }

    private fun uploadFallbackColor(color: Int) {
        val buffer = ByteBuffer.allocateDirect(16)
        repeat(4) {
            buffer.put(AndroidColor.red(color).toByte())
            buffer.put(AndroidColor.green(color).toByte())
            buffer.put(AndroidColor.blue(color).toByte())
            buffer.put(0xff.toByte())
        }
        buffer.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            2,
            2,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            buffer,
        )
    }

    private fun bindTexture(texture: Int, unit: Int) {
        GLES20.glActiveTexture(unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    }

    private fun deleteTexture(texture: Int) {
        if (texture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) { "Particle cover GL program link failed" }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Particle cover GL shader compile failed" }
        return shader
    }

    private fun buildEdgeParticles(): ParticleSet {
        val random = Random(0xE06ED957L)
        val data = FloatArray(EdgeParticleCount * ParticleStrideFloats)
        var cursor = 0
        repeat(EdgeParticleCount) {
            val side = random.nextInt(4)
            val layer = random.nextFloat().toDouble().pow(2.65).toFloat()
            val edgeDepth = EdgeParticleBand * layer
            val edgeWeight = (1f - smoothStep(0f, EdgeParticleBand, edgeDepth))
                .toDouble()
                .pow(1.35)
                .toFloat()
            val tangent = random.nextFloat()
            val tangentJitter = random.between(-0.035f, 0.035f) * (0.35f + edgeWeight)
            var u: Float
            var v: Float
            var normalX = 0f
            var normalY = 0f
            when (side) {
                0 -> {
                    u = (tangent + tangentJitter).coerceIn(0f, 1f)
                    v = edgeDepth.coerceIn(0f, 1f)
                    normalY = 1f
                }
                1 -> {
                    u = (1f - edgeDepth).coerceIn(0f, 1f)
                    v = (tangent + tangentJitter).coerceIn(0f, 1f)
                    normalX = 1f
                }
                2 -> {
                    u = (tangent + tangentJitter).coerceIn(0f, 1f)
                    v = (1f - edgeDepth).coerceIn(0f, 1f)
                    normalY = -1f
                }
                else -> {
                    u = edgeDepth.coerceIn(0f, 1f)
                    v = (tangent + tangentJitter).coerceIn(0f, 1f)
                    normalX = -1f
                }
            }
            val homeX = u * 2f - 1f
            val homeY = 1f - v * 2f
            val outward = random.between(0.018f, 0.34f) *
                EdgeParticleBand *
                (0.28f + edgeWeight * 1.18f) *
                2f
            val shear = random.between(-0.11f, 0.11f) *
                EdgeParticleBand *
                (0.25f + edgeWeight) *
                2f
            val scatterX = homeX + normalX * outward + normalY * shear
            val scatterY = homeY + normalY * outward + normalX * shear
            val z = random.between(-1f, 1f) * EdgeDepth * (0.18f + edgeWeight * 1.35f)
            val size = random.between(2.0f, 3.4f) + edgeWeight * random.between(1.4f, 3.4f)
            cursor = putParticle(
                data = data,
                cursor = cursor,
                homeX = homeX,
                homeY = homeY,
                homeZ = z,
                scatterX = scatterX,
                scatterY = scatterY,
                scatterZ = z + random.between(-0.08f, 0.16f),
                u = u,
                v = v,
                size = size,
                detach = edgeWeight,
                seed = random.nextFloat(),
            )
        }
        return ParticleSet(data.toFloatBuffer(), EdgeParticleCount)
    }

    private fun buildTransitionParticles(): ParticleSet {
        val random = Random(0x7A11C05EL)
        val cols = TransitionParticleGrid
        val rows = TransitionParticleGrid
        val count = cols * rows
        val data = FloatArray(count * ParticleStrideFloats)
        val indices = IntArray(count) { it }
        for (i in indices.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val swap = indices[i]
            indices[i] = indices[j]
            indices[j] = swap
        }
        var cursor = 0
        for (index in indices) {
            val r = index / cols
            val c = index % cols
                val u = (c + 0.5f) / cols
                val v = (r + 0.5f) / rows
                val homeX = u * 2f - 1f
                val homeY = 1f - v * 2f
                val edgeDistance = min(
                    min(u, 1f - u),
                    min(v, 1f - v),
                )
                val edgeBias = (1f - (edgeDistance / 0.5f).coerceIn(0f, 1f)).toDouble()
                    .pow(0.55)
                    .toFloat()
                val horizontal = random.nextFloat() < 0.58f
                val direction = if (homeX < 0f) -1f else 1f
                val scatterX: Float
                val scatterY: Float
                val scatterZ: Float
                if (horizontal) {
                    scatterX = homeX + direction * random.between(0.48f, 1.68f) * (0.72f + edgeBias * 0.42f)
                    scatterY = homeY + random.between(-0.62f, 0.62f) * (0.70f + edgeBias * 0.36f)
                    scatterZ = random.between(-1.10f, 1.10f) * (0.75f + edgeBias * 0.45f)
                } else {
                    val radial = random.between(0.34f, 2.18f) * (0.72f + edgeBias * 0.52f)
                    scatterX = homeX * radial + random.between(-0.34f, 0.34f)
                    scatterY = homeY * radial + random.between(-0.34f, 0.34f)
                    scatterZ = random.between(-1.45f, 1.25f)
                }
                cursor = putParticle(
                    data = data,
                    cursor = cursor,
                    homeX = homeX,
                    homeY = homeY,
                    homeZ = random.between(-0.01f, 0.01f),
                    scatterX = scatterX,
                    scatterY = scatterY,
                    scatterZ = scatterZ,
                    u = u,
                    v = v,
                    size = random.between(2.2f, 5.0f),
                    detach = random.between(0.45f, 1f),
                    seed = random.nextFloat(),
                )
        }
        return ParticleSet(data.toFloatBuffer(), count)
    }

    private fun putParticle(
        data: FloatArray,
        cursor: Int,
        homeX: Float,
        homeY: Float,
        homeZ: Float,
        scatterX: Float,
        scatterY: Float,
        scatterZ: Float,
        u: Float,
        v: Float,
        size: Float,
        detach: Float,
        seed: Float,
    ): Int {
        var i = cursor
        data[i++] = homeX
        data[i++] = homeY
        data[i++] = homeZ
        data[i++] = scatterX
        data[i++] = scatterY
        data[i++] = scatterZ
        data[i++] = u
        data[i++] = v
        data[i++] = size
        data[i++] = detach
        data[i++] = seed
        return i
    }

    private fun Random.between(min: Float, max: Float): Float =
        min + nextFloat() * (max - min)

    private fun FloatArray.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * FloatBytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(this@toFloatBuffer)
                position(0)
            }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun outCubic(x: Float): Float {
        val t = 1f - x.coerceIn(0f, 1f)
        return 1f - t * t * t
    }

    private fun easeInOutCubic(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return if (t < 0.5f) {
            4f * t * t * t
        } else {
            1f - (-2f * t + 2f).let { it * it * it } / 2f
        }
    }

    private data class ParticleSet(
        val buffer: FloatBuffer,
        val count: Int,
    )

    companion object {
        const val TransitionDurationMs = 900L
        private const val FullCoverHoldAfterRegroupMs = 10_000L
        private const val EdgeBoostMs = 150L
        private const val ScatterEndMs = 450L
        private const val GatherEndMs = 750L
        private const val StablePlaneErosion = 0.006f
        private const val StableBreakup = 0.32f
        private const val PlaneNoise = 0.022f
        private const val EdgeAlphaPeak = 0.42f
        private const val MaxEdgeParticleDensity = 1.25f
        private const val StableEdgeAlphaScale = 1.95f
        private const val StableEdgeResidueAlpha = 0.38f
        private const val TransitionAlphaScale = 2.35f
        private const val EdgeWobble = 0.018f
        private const val TransitionWobble = 0.010f
        private const val StableEdgeTravel = 0.18f
        private const val LyricsParticleTravel = 0.62f
        private const val EdgeDepth = 0.145f
        private const val EdgeParticleBand = 0.050f
        private const val MaskFeather = 0.030f
        private const val PointScale = 0.83f
        private const val CoverPlaneScale = 1f
        private const val EdgeParticleCount = 11000
        private const val TransitionParticleGrid = 100
        private const val FloatBytes = 4
        private const val QuadStrideBytes = 4 * FloatBytes
        private const val ParticleStrideFloats = 11
        private const val ParticleStrideBytes = ParticleStrideFloats * FloatBytes
        private val BitmapUploadPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    }
}

private const val QuadVertexShader = """
attribute vec2 aPosition;
attribute vec2 aUv;
uniform vec2 uCoverCenter;
uniform vec2 uCoverHalfSize;
uniform float uScale;
varying vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(uCoverCenter + aPosition * uCoverHalfSize * uScale, 0.0, 1.0);
}
"""

private const val QuadFragmentShader = """
precision mediump float;
uniform sampler2D uTexture;
uniform float uAlpha;
uniform float uErosion;
uniform float uNoise;
uniform float uFeather;
uniform float uBreakup;
uniform float uEdgeOnlyBreakup;
uniform float uResidue;
varying vec2 vUv;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float chipAt(vec2 p, vec2 offset) {
    vec2 cell = floor(p) + offset;
    vec2 local = fract(p) - offset;
    float gate = hash21(cell + vec2(17.19, 91.73));
    vec2 center = vec2(
        hash21(cell + vec2(0.37, 41.13)),
        hash21(cell + vec2(73.91, 0.61))
    );
    float radius = mix(0.13, 0.34, hash21(cell + vec2(19.31, 11.17)));
    float d = length(local - center);
    float chip = 1.0 - smoothstep(radius * 0.86, radius, d);
    return chip * step(0.66, gate);
}

float chipField(vec2 uv, float scale) {
    vec2 p = uv * scale;
    float chip = chipAt(p, vec2(0.0, 0.0));
    chip = max(chip, chipAt(p, vec2(1.0, 0.0)));
    chip = max(chip, chipAt(p, vec2(-1.0, 0.0)));
    chip = max(chip, chipAt(p, vec2(0.0, 1.0)));
    chip = max(chip, chipAt(p, vec2(0.0, -1.0)));
    return chip;
}

void main() {
    vec4 color = texture2D(uTexture, vUv);
    if (uErosion <= 0.001) {
        gl_FragColor = vec4(color.rgb, color.a * uAlpha);
        return;
    }
    float edge = min(min(vUv.x, 1.0 - vUv.x), min(vUv.y, 1.0 - vUv.y));
    float edgeBand = clamp(uFeather * 1.35, 0.045, 0.130);
    float chipBand = min(edgeBand, 0.075);
    float stableEdge01 = 1.0 - smoothstep(0.0, edgeBand, edge);
    float chipEdge01 = 1.0 - smoothstep(0.0, chipBand, edge);
    float stableEdgeStrength = pow(stableEdge01, 0.72);
    float chipEdgeStrength = pow(chipEdge01, 0.78);
    vec2 centered = abs(vUv - vec2(0.5)) * 2.0;
    float squareRadius = max(centered.x, centered.y);
    float erosionReach = smoothstep(0.0, 0.085, uErosion);
    float edgeBreakup = erosionReach * chipEdgeStrength * 0.50;

    vec2 warp = vec2(
        hash21(vUv * vec2(37.1, 83.7)),
        hash21(vUv.yx * vec2(61.9, 29.3))
    ) * 0.021;
    float coarseChip = chipField(vUv + warp, 42.0);
    float midChip = chipField(vUv.yx + vec2(0.113, 0.271) - warp, 86.0);
    float fineChip = chipField(vUv + vec2(0.317, 0.149) + warp * 0.5, 154.0);
    float chip = max(coarseChip, max(midChip * 0.86, fineChip * 0.62));
    float frontNoise = coarseChip * 0.15 + midChip * 0.08 + hash21(vUv * 97.3) * 0.10 - 0.15;
    float frontEdge = mix(1.16, -0.22, pow(uBreakup, 0.62));
    float inwardFront = smoothstep(frontEdge - 0.12, frontEdge + 0.16, squareRadius + frontNoise);
    float materialBreakup = mix(chipEdgeStrength, max(chipEdgeStrength, inwardFront), 1.0 - uEdgeOnlyBreakup);
    float effectiveBreakup = clamp(uBreakup * materialBreakup * 2.45 + edgeBreakup, 0.0, 1.0);
    float chipThreshold = mix(0.52, 0.04, effectiveBreakup);
    float hardHole = step(chipThreshold, chip);
    float chippedEdge = max(coarseChip, midChip);
    float edgeShard = step(0.50, chippedEdge) * step(0.010, chipEdgeStrength);
    float frontHole = inwardFront * smoothstep(0.03, 0.18, uBreakup);
    float lateEvaporation = smoothstep(0.82, 1.0, uBreakup);
    float vaporHole = step(mix(0.96, 0.18, lateEvaporation), hash21(vUv * 311.7));
    float hole = max(max(hardHole * (0.35 + 0.65 * inwardFront), frontHole), max(edgeShard * step(0.22, effectiveBreakup), vaporHole * lateEvaporation));
    float mask = 1.0 - hole;

    if (uResidue > 0.5) {
        discard;
    } else {
        gl_FragColor = vec4(color.rgb, color.a * mask * uAlpha);
    }
}
"""

private const val ParticleVertexShader = """
attribute vec3 aHome;
attribute vec3 aScatter;
attribute vec2 aUv;
attribute float aSize;
attribute float aDetach;
attribute float aSeed;
uniform vec2 uCoverCenter;
uniform vec2 uCoverHalfSize;
uniform float uTravel;
uniform float uAlpha;
uniform float uScale;
uniform float uCoverScale;
uniform float uWobble;
uniform float uPointScale;
uniform float uFeather;
uniform float uTime;
uniform float uDirection;
uniform float uLyrics;
varying vec2 vUv;
varying float vDetach;
varying float vSeed;
varying float vAlpha;
varying float vBoundsFade;

void main() {
    vec2 rawHome = aHome.xy;
    vec3 home = aHome;
    vec3 scatter = aScatter;
    home.xy *= uCoverScale;
    scatter.xy *= uCoverScale;
    vec3 scatterDelta = scatter - home;
    scatterDelta.x *= uDirection;
    float featherTravel = 0.72 + (uFeather - 0.55) * 0.42;
    scatter = home + scatterDelta * featherTravel;
    float release = smoothstep(aDetach * 0.28, 0.48 + aDetach * 0.50, uTravel);
    vec2 coverHome = uCoverCenter + home.xy * uCoverHalfSize;
    vec2 coverScatter = uCoverCenter + scatter.xy * uCoverHalfSize;
    vec2 lyricsTarget = rawHome * mix(0.86, 0.98, uLyrics);
    lyricsTarget += vec2(
        sin(uTime * 0.13 + aSeed * 41.0),
        cos(uTime * 0.11 + aSeed * 53.0)
    ) * 0.026;
    vec2 travelTarget = mix(coverScatter, lyricsTarget, uLyrics);
    vec2 pos = mix(coverHome, travelTarget, max(release, uLyrics));
    float z = mix(home.z, mix(scatter.z, scatter.z * 0.18, uLyrics), max(release, uLyrics));
    float homeWeight = 1.0 - uTravel;
    float driftWeight = max(homeWeight, uLyrics * 0.72);
    pos.x += sin(uTime * 1.10 + aSeed * 19.0) * uWobble * (0.18 + aDetach * 0.82) * driftWeight;
    pos.y += cos(uTime * 0.90 + aSeed * 27.0) * uWobble * (0.18 + aDetach * 0.82) * driftWeight;
    z += sin(uTime * 0.70 + aSeed * 31.0) * uWobble * 2.4 * aDetach * driftWeight;
    float perspective = 1.0 / clamp(1.0 + z * 0.18, 0.72, 1.38);
    vec2 xy = mix(uCoverCenter, pos, uScale);
    gl_Position = vec4(xy, 0.0, 1.0);
    float bounds = max(abs(xy.x), abs(xy.y));
    float fadeStart = mix(0.86, 0.94, uLyrics);
    vBoundsFade = 1.0 - smoothstep(fadeStart, 1.0, bounds);
    float burst = sin(release * 3.14159265);
    gl_PointSize = max(
        1.0,
        aSize * uPointScale * (1.0 + burst * 0.24) * perspective
    );
    vUv = aUv;
    vDetach = aDetach;
    vSeed = aSeed;
    vAlpha = uAlpha * (0.72 + 0.28 * aDetach);
}
"""

private const val ParticleFragmentShader = """
precision mediump float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uTextureMix;
uniform float uLyrics;
varying vec2 vUv;
varying float vDetach;
varying float vSeed;
varying float vAlpha;
varying float vBoundsFade;

void main() {
    vec2 p = gl_PointCoord - vec2(0.5);
    float angleBucket = step(0.5, fract(vSeed * 5.13));
    vec2 shard = mix(p, vec2(p.y, -p.x), angleBucket);
    float aspect = mix(0.68, 1.26, fract(vSeed * 17.71));
    vec2 halfSize = vec2(0.46 * aspect, 0.42 / aspect);
    float shardMask = step(abs(shard.x), halfSize.x) * step(abs(shard.y), halfSize.y);
    float dustMask = smoothstep(0.50, 0.18, length(p));
    float mask = mix(shardMask, dustMask, clamp(uLyrics, 0.0, 1.0));
    if (mask <= 0.01) discard;
    vec3 color = mix(texture2D(uTextureA, vUv).rgb, texture2D(uTextureB, vUv).rgb, uTextureMix);
    float alpha = min(vAlpha, 1.0) * vBoundsFade * mask;
    if (alpha <= 0.01) discard;
    gl_FragColor = vec4(color, alpha);
}
"""
