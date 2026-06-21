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
    private var fitX = 1f
    private var fitY = 1f

    private var quadProgram = 0
    private var particleProgram = 0
    private var currentTexture = 0
    private var previousTexture = 0
    private var currentSongId: String? = null
    private var currentHasBitmap = false
    private var currentBitmapGeneration = -1
    private var currentFallbackColor = 0
    private var transitionStartedAtMs = 0L
    private var scatterDirection = 1f
    private var tuning = ParticleCoverTuning()

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
        val aspect = width.toFloat() / height.toFloat()
        if (aspect >= 1f) {
            fitX = 1f / aspect
            fitY = 1f
        } else {
            fitX = 1f
            fitY = aspect
        }
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
            SystemClock.uptimeMillis()
        } else {
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

    fun render() {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (currentTexture == 0) return

        val elapsed = transitionElapsedMs()
        if (previousTexture == 0 || elapsed >= TransitionDurationMs) {
            if (previousTexture != 0) {
                deleteTexture(previousTexture)
                previousTexture = 0
            }
            drawQuad(
                currentTexture,
                alpha = 1f,
                scale = 1f,
                erosion = StablePlaneErosion * tuning.erosionScale,
                residue = false,
            )
            drawQuad(
                currentTexture,
                alpha = StableEdgeResidueAlpha,
                scale = 1f,
                erosion = StablePlaneErosion * tuning.erosionScale * ResidueErosionScale,
                residue = true,
            )
            drawParticles(
                particleSet = edgeParticles,
                texture = currentTexture,
                travel = StableEdgeTravel * tuning.edgeTravelScale,
                alpha = StableEdgeAlpha * tuning.edgeParticleAlpha,
                wobble = EdgeWobble,
                scale = 1f,
                density = tuning.edgeParticleDensity,
            )
            return
        }

        when {
            elapsed < EdgeBoostMs -> {
                val p = smoothStep(0f, EdgeBoostMs.toFloat(), elapsed.toFloat())
                val scale = 1f - 0.035f * p
                drawQuad(
                    texture = previousTexture,
                    alpha = 1f,
                    scale = scale,
                    erosion = StablePlaneErosion * tuning.erosionScale + 0.014f * p,
                    residue = false,
                )
                drawQuad(
                    texture = previousTexture,
                    alpha = StableEdgeResidueAlpha * (1f + 0.30f * p),
                    scale = scale,
                    erosion = (StablePlaneErosion * tuning.erosionScale + 0.014f * p) *
                        ResidueErosionScale,
                    residue = true,
                )
                drawParticles(
                    particleSet = edgeParticles,
                    texture = previousTexture,
                    travel = StableEdgeTravel * tuning.edgeTravelScale + 0.08f * p,
                    alpha = StableEdgeAlpha * tuning.edgeParticleAlpha * (1.0f + 1.15f * p),
                    wobble = EdgeWobble * (1.0f + p),
                    scale = scale,
                    density = tuning.edgeParticleDensity,
                )
            }
            elapsed < ScatterEndMs -> {
                val p = smoothStep(EdgeBoostMs.toFloat(), ScatterEndMs.toFloat(), elapsed.toFloat())
                drawQuad(
                    texture = previousTexture,
                    alpha = 1f - p,
                    scale = 0.965f,
                    erosion = (0.018f + 0.180f * p) * tuning.erosionScale,
                    residue = false,
                )
                drawQuad(
                    texture = previousTexture,
                    alpha = StableEdgeResidueAlpha * (1f - p),
                    scale = 0.965f,
                    erosion = (0.018f + 0.180f * p) * tuning.erosionScale *
                        ResidueErosionScale,
                    residue = true,
                )
                drawParticles(
                    particleSet = edgeParticles,
                    texture = previousTexture,
                    travel = StableEdgeTravel * tuning.edgeTravelScale,
                    alpha = StableEdgeAlpha * tuning.edgeParticleAlpha * (1f - p),
                    wobble = EdgeWobble,
                    scale = 1f,
                    density = tuning.edgeParticleDensity,
                )
                drawParticles(
                    particleSet = transitionParticles,
                    texture = previousTexture,
                    travel = p,
                    alpha = TransitionAlpha,
                    wobble = TransitionWobble,
                    scale = 1f,
                    density = tuning.transitionParticleDensity,
                )
            }
            elapsed < GatherEndMs -> {
                val p = smoothStep(ScatterEndMs.toFloat(), GatherEndMs.toFloat(), elapsed.toFloat())
                drawParticles(
                    particleSet = transitionParticles,
                    texture = currentTexture,
                    travel = 1f - p,
                    alpha = TransitionAlpha,
                    wobble = TransitionWobble,
                    scale = 1f,
                    density = tuning.transitionParticleDensity,
                )
            }
            else -> {
                val p = smoothStep(GatherEndMs.toFloat(), TransitionDurationMs.toFloat(), elapsed.toFloat())
                drawParticles(
                    particleSet = transitionParticles,
                    texture = currentTexture,
                    travel = 0f,
                    alpha = TransitionAlpha * (1f - p),
                    wobble = TransitionWobble * (1f - p),
                    scale = 1f,
                    density = tuning.transitionParticleDensity,
                )
                drawQuad(
                    texture = currentTexture,
                    alpha = p,
                    scale = 0.985f + 0.015f * p,
                    erosion = StablePlaneErosion * tuning.erosionScale,
                    residue = false,
                )
                drawQuad(
                    texture = currentTexture,
                    alpha = StableEdgeResidueAlpha * p,
                    scale = 0.985f + 0.015f * p,
                    erosion = StablePlaneErosion * tuning.erosionScale * ResidueErosionScale,
                    residue = true,
                )
            }
        }
    }

    fun isAnimating(): Boolean =
        previousTexture != 0 && transitionElapsedMs() < TransitionDurationMs

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
        GLES20.glUniform1f(GLES20.glGetUniformLocation(quadProgram, "uResidue"), if (residue) 1f else 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(quadProgram, "uFit"), fitX, fitY)

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
        travel: Float,
        alpha: Float,
        wobble: Float,
        scale: Float,
        density: Float,
    ) {
        if (alpha <= 0.001f || texture == 0) return
        val drawCount = (particleSet.count * density.coerceIn(0.12f, 1.35f))
            .toInt()
            .coerceIn(1, particleSet.count)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(particleProgram)
        bindTexture(texture, GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(particleProgram, "uTexture"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uTravel"), travel.coerceIn(0f, 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uAlpha"), alpha.coerceIn(0f, 2.5f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uScale"), scale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uWobble"), wobble)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uCoverScale"), CoverPlaneScale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uPointScale"), density * 1.16f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uTime"), SystemClock.uptimeMillis() / 1000f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uDirection"), scatterDirection)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(particleProgram, "uFit"), fitX, fitY)

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
            val layer = random.nextFloat().toDouble().pow(1.85).toFloat()
            val edgeDepth = EdgeParticleBand * layer
            val edgeWeight = 1f - (edgeDepth / EdgeParticleBand).coerceIn(0f, 1f)
            val tangent = random.nextFloat()
            val tangentJitter = random.between(-0.030f, 0.030f) * (0.35f + edgeWeight)
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
            val outward = random.between(0.020f, 0.34f) * (0.35f + edgeWeight * 1.15f)
            val shear = random.between(-0.13f, 0.13f) * (0.35f + edgeWeight)
            val scatterX = homeX + normalX * outward + normalY * shear
            val scatterY = homeY + normalY * outward + normalX * shear
            val z = random.between(-1f, 1f) * EdgeDepth * (0.25f + edgeWeight * 1.45f)
            val size = random.between(0.74f, 1.36f) + edgeWeight * random.between(0.35f, 1.28f)
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
        var cursor = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
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
                    size = random.between(0.74f, 1.78f) + edgeBias * random.between(0.22f, 0.92f),
                    detach = (edgeBias * 0.62f + random.between(0.18f, 0.96f) * 0.38f)
                        .coerceIn(0.16f, 1f),
                    seed = random.nextFloat(),
                )
            }
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

    private data class ParticleSet(
        val buffer: FloatBuffer,
        val count: Int,
    )

    companion object {
        const val TransitionDurationMs = 900L
        const val HaloFraction = 0.08f
        private const val EdgeBoostMs = 150L
        private const val ScatterEndMs = 450L
        private const val GatherEndMs = 750L
        private const val StablePlaneErosion = 0.072f
        private const val PlaneNoise = 0.052f
        private const val StableEdgeAlpha = 0.92f
        private const val StableEdgeResidueAlpha = 0.38f
        private const val ResidueErosionScale = 0.54f
        private const val TransitionAlpha = 1.12f
        private const val EdgeWobble = 0.018f
        private const val TransitionWobble = 0.010f
        private const val StableEdgeTravel = 0.14f
        private const val EdgeDepth = 0.145f
        private const val EdgeParticleBand = 0.135f
        private const val MaskFeather = 0.030f
        private const val CoverPlaneScale = 1f / (1f + HaloFraction * 2f)
        private const val EdgeParticleCount = 12800
        private const val TransitionParticleGrid = 112
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
uniform vec2 uFit;
uniform float uScale;
varying vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPosition * uFit * uScale, 0.0, 1.0);
}
"""

private const val QuadFragmentShader = """
precision mediump float;
uniform sampler2D uTexture;
uniform float uAlpha;
uniform float uErosion;
uniform float uNoise;
uniform float uFeather;
uniform float uResidue;
varying vec2 vUv;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec4 color = texture2D(uTexture, vUv);
    if (uErosion <= 0.001) {
        gl_FragColor = vec4(color.rgb, color.a * uAlpha);
        return;
    }
    float edge = min(min(vUv.x, 1.0 - vUv.x), min(vUv.y, 1.0 - vUv.y));
    float grain = hash21(floor(vUv * 260.0));
    float grain2 = hash21(floor((vUv + vec2(0.173, 0.419)) * 137.0));
    float coarse = hash21(floor((vUv + vec2(grain2 * 0.07, grain * 0.05)) * 71.0));
    float noise = grain * 0.50 + grain2 * 0.30 + coarse * 0.20;
    float threshold = uErosion + (noise - 0.5) * uNoise * 1.35;
    float feather = max(0.008, uFeather);
    float dissolve = smoothstep(threshold - feather * 0.75, threshold + feather, edge);
    float fadeWeight = smoothstep(0.0, 0.085, uErosion);
    float mask = mix(1.0, dissolve, fadeWeight);
    float edgeZone = 1.0 - smoothstep(threshold + feather * 0.65, threshold + feather * 3.5, edge);
    float materialFilm = edgeZone * (0.22 + 0.36 * noise);
    mask = max(mask, materialFilm);
    if (uResidue > 0.5) {
        float missing = 1.0 - mask;
        float nearEdge = 1.0 - smoothstep(threshold + feather * 0.8, threshold + feather * 4.0, edge);
        float speckle = smoothstep(0.48, 0.92, grain) * smoothstep(0.28, 0.82, grain2);
        float residue = missing * nearEdge * speckle;
        gl_FragColor = vec4(color.rgb, color.a * residue * uAlpha);
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
uniform vec2 uFit;
uniform float uTravel;
uniform float uAlpha;
uniform float uScale;
uniform float uCoverScale;
uniform float uWobble;
uniform float uPointScale;
uniform float uTime;
uniform float uDirection;
varying vec2 vUv;
varying float vDetach;
varying float vSeed;
varying float vAlpha;

void main() {
    vec3 home = aHome;
    vec3 scatter = aScatter;
    home.xy *= uCoverScale;
    scatter.xy *= uCoverScale;
    vec3 scatterDelta = scatter - home;
    scatterDelta.x *= uDirection;
    scatter = home + scatterDelta;
    float release = smoothstep(aDetach * 0.32, 0.48 + aDetach * 0.52, uTravel);
    vec3 pos = mix(home, scatter, release);
    float homeWeight = 1.0 - uTravel;
    pos.x += sin(uTime * 1.10 + aSeed * 19.0) * uWobble * (0.18 + aDetach * 0.82) * homeWeight;
    pos.y += cos(uTime * 0.90 + aSeed * 27.0) * uWobble * (0.18 + aDetach * 0.82) * homeWeight;
    pos.z += sin(uTime * 0.70 + aSeed * 31.0) * uWobble * 2.4 * aDetach;
    float perspective = 1.0 / clamp(1.0 + pos.z * 0.18, 0.72, 1.38);
    vec2 xy = pos.xy * perspective * uFit * uScale;
    gl_Position = vec4(xy, 0.0, 1.0);
    float burst = sin(release * 3.14159265);
    gl_PointSize = max(
        0.72,
        aSize * uPointScale * (0.96 + aDetach * 0.18) * (1.0 + burst * 0.24) * perspective
    );
    vUv = aUv;
    vDetach = aDetach;
    vSeed = aSeed;
    vAlpha = uAlpha * (0.70 + 0.30 * aDetach) * (0.86 + burst * 0.18);
}
"""

private const val ParticleFragmentShader = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vUv;
varying float vDetach;
varying float vSeed;
varying float vAlpha;

void main() {
    vec2 p = gl_PointCoord - vec2(0.5);
    float d = length(p);
    if (d > 0.5) discard;
    vec3 color = texture2D(uTexture, vUv).rgb;
    float core = smoothstep(0.48, 0.08, d);
    float glow = smoothstep(0.50, 0.0, d) * (0.10 + 0.16 * vDetach);
    float sparkle = 0.86 + 0.18 * fract(vSeed * 37.0);
    float alpha = (core + glow) * vAlpha;
    gl_FragColor = vec4(color * sparkle, alpha);
}
"""
