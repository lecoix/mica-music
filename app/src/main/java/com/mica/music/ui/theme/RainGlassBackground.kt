package com.mica.music.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mica.music.R
import com.mica.music.ui.MicaScreenshotGoldenMode
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

private const val RainGlassRenderScale = 1.0f
private const val RainGlassStaticBackdropKey = 0x5241494E474C4153L // "RAINGLAS"
private const val RainGlassFrameIntervalMs = 16L
private const val RainGlassArtworkTransitionMs = 650L

internal val RainGlassBackgroundSurface = Color(0xFF080C12)
internal val RainGlassForegroundAccent = Color(0xFFE2F3FF)

/**
 * Mica-owned wet-glass pass over a static mipmapped landscape texture.
 *
 * Real-time procedural city backdrops were prototyped and intentionally shelved because the
 * second animated fragment pass consumed too much GPU budget underneath the already-expensive
 * rain layer. See docs/RAIN_GLASS_BACKGROUND.md.
 */
@Composable
internal fun RainGlassBackground(
    fallbackColor: Color,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = rememberMicaMotionEnabled()
    val fallbackColorInt = fallbackColor.toArgb()

    Box(
        modifier
            .fillMaxSize()
            .background(RainGlassBackgroundSurface),
    ) {
        if (!MicaScreenshotGoldenMode.enabled) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> RainGlassBackgroundView(ctx) },
                update = { view ->
                    view.setFallbackColor(fallbackColorInt)
                    view.setMotionEnabled(motionEnabled)
                },
                onRelease = { view -> view.release() },
            )
        }
    }
}

private data class RainGlassSceneSpec(
    val artwork: Bitmap?,
    val artworkKey: Long,
    val fallbackR: Float,
    val fallbackG: Float,
    val fallbackB: Float,
    val motionEnabled: Boolean,
)

private class RainGlassBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var renderThread: RainGlassRenderThread? = null
    private val staticBackdrop: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.rain_glass_landscape)
    private var currentSpec = RainGlassSceneSpec(
        artwork = staticBackdrop,
        artworkKey = if (staticBackdrop != null) RainGlassStaticBackdropKey else Long.MIN_VALUE,
        fallbackR = RainGlassBackgroundSurface.red,
        fallbackG = RainGlassBackgroundSurface.green,
        fallbackB = RainGlassBackgroundSurface.blue,
        motionEnabled = true,
    )

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    fun setArtwork(bitmap: Bitmap, key: Long) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        if (currentSpec.artworkKey == key && currentSpec.artwork != null) return
        publish(currentSpec.copy(artwork = bitmap, artworkKey = key))
    }

    fun clearArtwork() {
        if (currentSpec.artwork == null && currentSpec.artworkKey == Long.MIN_VALUE) return
        publish(currentSpec.copy(artwork = null, artworkKey = Long.MIN_VALUE))
    }

    fun setFallbackColor(argb: Int) {
        val next = currentSpec.copy(
            fallbackR = ((argb ushr 16) and 0xff) / 255f,
            fallbackG = ((argb ushr 8) and 0xff) / 255f,
            fallbackB = (argb and 0xff) / 255f,
        )
        publish(next)
    }

    fun setMotionEnabled(enabled: Boolean) {
        if (currentSpec.motionEnabled == enabled) return
        publish(currentSpec.copy(motionEnabled = enabled))
    }

    fun release() {
        renderThread?.requestStopAndJoin()
        renderThread = null
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        release()
        val bufferWidth = rainGlassBufferSize(width)
        val bufferHeight = rainGlassBufferSize(height)
        DiagnosticLog.event(
            "RainGlassGl",
            "surface-available diag=surface view=${width}x$height buffer=${bufferWidth}x$bufferHeight opaque=$isOpaque",
        )
        surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight)
        renderThread = RainGlassRenderThread(
            surfaceTexture = surfaceTexture,
            initialWidth = bufferWidth,
            initialHeight = bufferHeight,
            initialSpec = currentSpec,
        ).also(Thread::start)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        val bufferWidth = rainGlassBufferSize(width)
        val bufferHeight = rainGlassBufferSize(height)
        DiagnosticLog.event(
            "RainGlassGl",
            "surface-size diag=surface view=${width}x$height buffer=${bufferWidth}x$bufferHeight",
        )
        surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight)
        renderThread?.resize(bufferWidth, bufferHeight)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        DiagnosticLog.event("RainGlassGl", "surface-destroyed diag=surface")
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    private fun publish(spec: RainGlassSceneSpec) {
        if (currentSpec == spec) return
        currentSpec = spec
        renderThread?.updateScene(spec)
    }
}

private fun rainGlassBufferSize(value: Int): Int =
    (value.coerceAtLeast(1) * RainGlassRenderScale)
        .roundToInt()
        .coerceAtLeast(1)

private class RainGlassRenderThread(
    surfaceTexture: SurfaceTexture,
    initialWidth: Int,
    initialHeight: Int,
    initialSpec: RainGlassSceneSpec,
) : Thread("mica-rain-glass-gl") {

    private val surface = Surface(surfaceTexture)
    private val lock = Object()
    private val renderer = RainGlassRenderer()

    @Volatile
    private var running = true

    private var width = initialWidth
    private var height = initialHeight
    private var sizeChanged = true
    private var sceneSpec = initialSpec
    private var sceneChanged = true

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var firstSwapLogged = false

    fun resize(newWidth: Int, newHeight: Int) {
        synchronized(lock) {
            width = newWidth.coerceAtLeast(1)
            height = newHeight.coerceAtLeast(1)
            sizeChanged = true
            lock.notifyAll()
        }
    }

    fun updateScene(spec: RainGlassSceneSpec) {
        synchronized(lock) {
            if (sceneSpec == spec) return
            sceneSpec = spec
            sceneChanged = true
            lock.notifyAll()
        }
    }

    fun requestStopAndJoin() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        interrupt()
        if (Thread.currentThread() != this) {
            runCatching { join(500) }
        }
    }

    override fun run() {
        try {
            DiagnosticLog.event(
                "RainGlassGl",
                "render-thread-start diag=thread initialSize=${width}x$height",
            )
            if (!initEgl()) {
                DiagnosticLog.important("RainGlassGl", "render-thread-stop diag=egl-init-failed")
                return
            }
            renderer.onSurfaceCreated()

            var continuous = true
            var nextFrameAt = SystemClock.uptimeMillis()
            while (running) {
                val nextWidth: Int
                val nextHeight: Int
                val applySize: Boolean
                val nextSpec: RainGlassSceneSpec
                val applyScene: Boolean

                synchronized(lock) {
                    while (running && !continuous && !sizeChanged && !sceneChanged) {
                        runCatching { lock.wait() }
                    }
                    if (!running) break

                    nextWidth = width
                    nextHeight = height
                    applySize = sizeChanged
                    sizeChanged = false
                    nextSpec = sceneSpec
                    applyScene = sceneChanged
                    sceneChanged = false
                }

                if (applySize) {
                    GLES20.glViewport(0, 0, nextWidth, nextHeight)
                    renderer.onSurfaceChanged(nextWidth, nextHeight)
                }
                if (applyScene) {
                    renderer.updateScene(nextSpec)
                }

                continuous = renderer.render()
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    DiagnosticLog.important(
                        "RainGlassGl",
                        "egl-swap-failed diag=egl error=${eglErrorHex()}",
                    )
                    break
                } else if (!firstSwapLogged) {
                    firstSwapLogged = true
                    DiagnosticLog.event("RainGlassGl", "first-swap diag=egl ok=true")
                }

                if (continuous) {
                    nextFrameAt += RainGlassFrameIntervalMs
                    val sleepMs = nextFrameAt - SystemClock.uptimeMillis()
                    if (sleepMs > 1L) {
                        runCatching { sleep(sleepMs) }
                    } else {
                        nextFrameAt = SystemClock.uptimeMillis()
                    }
                } else {
                    nextFrameAt = SystemClock.uptimeMillis()
                }
            }
        } catch (throwable: Throwable) {
            DiagnosticLog.important("RainGlassGl", "renderer-stopped", throwable)
        } finally {
            renderer.release()
            releaseEgl()
            surface.release()
        }
    }

    private fun initEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            DiagnosticLog.important(
                "RainGlassGl",
                "egl-get-display-failed diag=egl error=${eglErrorHex()}",
            )
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            DiagnosticLog.important(
                "RainGlassGl",
                "egl-initialize-failed diag=egl error=${eglErrorHex()}",
            )
            return false
        }

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, RainGlassEglOpenGlEs3Bit,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)) {
            DiagnosticLog.important(
                "RainGlassGl",
                "egl-choose-config-failed diag=egl error=${eglErrorHex()}",
            )
            return false
        }
        val config = configs[0]
        if (config == null) {
            DiagnosticLog.important(
                "RainGlassGl",
                "egl-choose-config-empty diag=egl count=${numConfigs[0]} error=${eglErrorHex()}",
            )
            return false
        }

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(RainGlassEglContextClientVersion, 3, EGL14.EGL_NONE),
            0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            DiagnosticLog.important(
                "RainGlassGl",
                "egl-create-context-failed diag=egl client=3 error=${eglErrorHex()}",
            )
            return false
        }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            DiagnosticLog.important(
                "RainGlassGl",
                "egl-create-window-surface-failed diag=egl error=${eglErrorHex()}",
            )
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            DiagnosticLog.important(
                "RainGlassGl",
                "egl-make-current-failed diag=egl error=${eglErrorHex()}",
            )
            return false
        }

        DiagnosticLog.event(
            "RainGlassGl",
            "egl-ready diag=egl version=${version[0]}.${version[1]} configs=${numConfigs[0]} client=3 rgb=8/8/8 alpha=0",
        )
        return true
    }

    private fun eglErrorHex(): String =
        "0x${Integer.toHexString(EGL14.eglGetError())}"

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
    }
}

private class RainGlassRenderer {

    private var width = 1
    private var height = 1

    private var program = 0
    private var positionLocation = -1
    private var resolutionLocation = -1
    private var timeLocation = -1
    private var fallbackLocation = -1
    private var artworkLocation = -1
    private var previousArtworkLocation = -1
    private var hasArtworkLocation = -1
    private var hasPreviousArtworkLocation = -1
    private var artworkMixLocation = -1

    private var currentTexture = 0
    private var previousTexture = 0
    private var currentArtworkKey = Long.MIN_VALUE
    private var transitionStartMs = 0L

    private var fallbackR = RainGlassBackgroundSurface.red
    private var fallbackG = RainGlassBackgroundSurface.green
    private var fallbackB = RainGlassBackgroundSurface.blue
    private var motionEnabled = true
    private var simulationTimeSeconds = 0f
    private var lastRenderMs = SystemClock.uptimeMillis()
    private var firstRenderLogged = false
    private var noTextureLogged = false
    private var firstDrawLogged = false

    private val quadBuffer = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    ).toRainGlassFloatBuffer()

    fun onSurfaceCreated() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(
            RainGlassBackgroundSurface.red,
            RainGlassBackgroundSurface.green,
            RainGlassBackgroundSurface.blue,
            1f,
        )

        program = createProgram("rain-glass", RainGlassVertexShader, RainGlassFragmentShader)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        resolutionLocation = GLES20.glGetUniformLocation(program, "uResolution")
        timeLocation = GLES20.glGetUniformLocation(program, "uTime")
        fallbackLocation = GLES20.glGetUniformLocation(program, "uFallback")
        artworkLocation = GLES20.glGetUniformLocation(program, "uArtwork")
        previousArtworkLocation = GLES20.glGetUniformLocation(program, "uPreviousArtwork")
        hasArtworkLocation = GLES20.glGetUniformLocation(program, "uHasArtwork")
        hasPreviousArtworkLocation = GLES20.glGetUniformLocation(program, "uHasPreviousArtwork")
        artworkMixLocation = GLES20.glGetUniformLocation(program, "uArtworkMix")
        lastRenderMs = SystemClock.uptimeMillis()

        DiagnosticLog.event(
            "RainGlassGl",
            "gl-ready diag=gl vendor=" + GLES20.glGetString(GLES20.GL_VENDOR) +
                " renderer=" + GLES20.glGetString(GLES20.GL_RENDERER) +
                " version=" + GLES20.glGetString(GLES20.GL_VERSION) +
                " glsl=" + GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) +
                " program=$program scale=$RainGlassRenderScale",
        )
        logGlError("surface-created")
    }

    fun onSurfaceChanged(newWidth: Int, newHeight: Int) {
        width = newWidth.coerceAtLeast(1)
        height = newHeight.coerceAtLeast(1)
    }

    fun updateScene(spec: RainGlassSceneSpec) {
        fallbackR = spec.fallbackR.coerceIn(0f, 1f)
        fallbackG = spec.fallbackG.coerceIn(0f, 1f)
        fallbackB = spec.fallbackB.coerceIn(0f, 1f)
        motionEnabled = spec.motionEnabled

        if (spec.artwork == null) {
            if (currentArtworkKey != Long.MIN_VALUE || currentTexture != 0 || previousTexture != 0) {
                clearTextures()
                currentArtworkKey = Long.MIN_VALUE
            }
            return
        }
        if (spec.artworkKey == currentArtworkKey && currentTexture != 0) return

        val nextTexture = uploadTexture(spec.artwork)
        if (nextTexture == 0) return

        if (previousTexture != 0) {
            deleteTexture(previousTexture)
        }
        previousTexture = currentTexture
        currentTexture = nextTexture
        currentArtworkKey = spec.artworkKey
        transitionStartMs = SystemClock.uptimeMillis()
    }

    /**
     * @return true when another frame is needed without an external scene/size change.
     */
    fun render(): Boolean {
        val nowMs = SystemClock.uptimeMillis()
        val elapsedSeconds = (nowMs - lastRenderMs).coerceAtLeast(0L) / 1000f
        lastRenderMs = nowMs
        if (motionEnabled) {
            simulationTimeSeconds += elapsedSeconds
        }

        if (!firstRenderLogged) {
            firstRenderLogged = true
            DiagnosticLog.event(
                "RainGlassGl",
                "first-render diag=draw program=$program texture=$currentTexture size=${width}x$height motion=$motionEnabled",
            )
        }
        if (currentTexture == 0 && !noTextureLogged) {
            noTextureLogged = true
            DiagnosticLog.important(
                "RainGlassGl",
                "render-without-texture diag=texture size=${width}x$height artworkKey=$currentArtworkKey",
            )
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(resolutionLocation, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(timeLocation, simulationTimeSeconds)
        GLES20.glUniform3f(fallbackLocation, fallbackR, fallbackG, fallbackB)
        GLES20.glUniform1f(hasArtworkLocation, 1f)
        GLES20.glUniform1f(hasPreviousArtworkLocation, 0f)
        GLES20.glUniform1f(artworkMixLocation, 1f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexture)
        GLES20.glUniform1i(artworkLocation, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUniform1i(previousArtworkLocation, 1)

        drawQuad(positionLocation)
        if (!firstDrawLogged) {
            firstDrawLogged = true
            DiagnosticLog.event(
                "RainGlassGl",
                "first-draw diag=draw position=$positionLocation texture=$currentTexture",
            )
            logGlError("first-draw")
        }

        return motionEnabled
    }

    fun release() {
        clearTextures()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun drawQuad(attributeLocation: Int) {
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(attributeLocation)
        GLES20.glVertexAttribPointer(
            attributeLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            quadBuffer,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(attributeLocation)
    }

    private fun uploadTexture(bitmap: Bitmap): Int {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            DiagnosticLog.important(
                "RainGlassGl",
                "texture-rejected diag=texture bitmap=${bitmap.describeForLog()}",
            )
            return 0
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        if (texture == 0) {
            DiagnosticLog.important(
                "RainGlassGl",
                "texture-create-failed diag=texture bitmap=${bitmap.describeForLog()} error=${glErrorHex()}",
            )
            return 0
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR_MIPMAP_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            deleteTexture(texture)
            DiagnosticLog.important(
                "RainGlassGl",
                "texture-upload-failed diag=texture id=$texture bitmap=${bitmap.describeForLog()} error=${glErrorHex(error)}",
            )
            return 0
        }
        DiagnosticLog.event(
            "RainGlassGl",
            "texture-ready diag=texture id=$texture bitmap=${bitmap.describeForLog()} mipmap=true min=linear-mipmap-linear",
        )
        return texture
    }

    private fun clearTextures() {
        deleteTexture(currentTexture)
        deleteTexture(previousTexture)
        currentTexture = 0
        previousTexture = 0
    }

    private fun deleteTexture(texture: Int) {
        if (texture == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    private fun createProgram(label: String, vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader("$label-vertex", GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader("$label-fragment", GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val nextProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(nextProgram, vertex)
        GLES20.glAttachShader(nextProgram, fragment)
        GLES20.glLinkProgram(nextProgram)

        val status = IntArray(1)
        GLES20.glGetProgramiv(nextProgram, GLES20.GL_LINK_STATUS, status, 0)
        val log = GLES20.glGetProgramInfoLog(nextProgram).orEmpty()
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] != GLES20.GL_TRUE) {
            DiagnosticLog.important(
                "RainGlassGl",
                "program-link-failed diag=shader label=$label program=$nextProgram log=${log.takeForLog()}",
            )
        }
        check(status[0] == GLES20.GL_TRUE) { "Rain-glass GL program link failed: $log" }
        DiagnosticLog.event(
            "RainGlassGl",
            "program-linked diag=shader label=$label program=$nextProgram log=${log.takeForLog()}",
        )
        return nextProgram
    }

    private fun compileShader(label: String, type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val log = GLES20.glGetShaderInfoLog(shader).orEmpty()
        if (status[0] != GLES20.GL_TRUE) {
            DiagnosticLog.important(
                "RainGlassGl",
                "shader-compile-failed diag=shader label=$label shader=$shader log=${log.takeForLog()}",
            )
        }
        check(status[0] == GLES20.GL_TRUE) { "Rain-glass GL shader compile failed: $log" }
        DiagnosticLog.event(
            "RainGlassGl",
            "shader-compiled diag=shader label=$label shader=$shader log=${log.takeForLog()}",
        )
        return shader
    }

    private fun logGlError(stage: String) {
        var error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return
        val errors = mutableListOf<String>()
        while (error != GLES20.GL_NO_ERROR) {
            errors += glErrorHex(error)
            error = GLES20.glGetError()
        }
        DiagnosticLog.important(
            "RainGlassGl",
            "gl-error diag=gl stage=$stage errors=${errors.joinToString(",")}",
        )
    }

    private fun glErrorHex(error: Int = GLES20.glGetError()): String =
        "0x${Integer.toHexString(error)}"

    private fun Bitmap.describeForLog(): String =
        "${width}x$height config=$config recycled=$isRecycled generation=$generationId"

    private fun String.takeForLog(): String =
        if (isBlank()) "empty" else replace('\n', ' ').take(240)
}

private fun FloatArray.toRainGlassFloatBuffer(): FloatBuffer =
    ByteBuffer.allocateDirect(size * RainGlassFloatBytes)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(this@toRainGlassFloatBuffer)
            position(0)
        }

private const val RainGlassFloatBytes = 4
private const val RainGlassEglOpenGlEs3Bit = 0x40
private const val RainGlassEglContextClientVersion = 0x3098

internal const val RainGlassVertexShader = """#version 300 es
precision highp float;

in vec2 aPosition;
uniform vec2 uResolution;
out vec2 fragCoord;

void main() {
    vec2 uv = aPosition * 0.5 + 0.5;
    fragCoord = uv * uResolution;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

internal const val RainGlassFragmentShader = """#version 300 es
precision highp float;

in vec2 fragCoord;
out vec4 fragColor;

uniform vec2 uResolution;
uniform float uTime;
uniform sampler2D uArtwork;

float MicaHash21(vec2 p) {
    p = fract(p * vec2(127.1, 311.7));
    p += dot(p, p + 19.19);
    return fract(p.x * p.y);
}

float MicaLifeEnvelope(float phase) {
    float birth = smoothstep(0.0, 0.025, phase);
    float age = clamp((phase - 0.025) / 0.975, 0.0, 1.0);
    float smoothAge = age * age * (3.0 - 2.0 * age);
    return birth * (1.0 - smoothAge);
}

float MicaStaticDropsV2(vec2 uv, float t) {
    vec2 gridUv = uv * 40.0;
    vec2 id = floor(gridUv);
    vec2 cell = fract(gridUv) - 0.5;

    float rx = MicaHash21(id + vec2(11.7, 3.1));
    float ry = MicaHash21(id + vec2(5.3, 19.9));
    float rz = MicaHash21(id + vec2(23.4, 7.7));
    float strength = MicaHash21(id + vec2(31.2, 47.6));

    vec2 center = (vec2(rx, ry) - 0.5) * 0.70;
    float d = length(cell - center);
    float profile = 1.0 - smoothstep(0.0, 0.30, d);
    float phase = fract(t + rz);

    return profile * strength * MicaLifeEnvelope(phase);
}

vec3 MicaHash31(vec2 p) {
    return vec3(
        MicaHash21(p + vec2(5.7, 17.3)),
        MicaHash21(p + vec2(29.1, 3.9)),
        MicaHash21(p + vec2(11.2, 41.6))
    );
}

float MicaMovingBell(float phase) {
    float rise = smoothstep(0.0, 0.85, phase);
    float fall = 1.0 - smoothstep(0.85, 1.0, phase);
    return rise * fall;
}

float MicaMovingDropMaskV2(vec2 uv, float t) {
    vec2 baseUv = uv;
    const vec2 grid = vec2(12.0, 2.0);

    vec2 flowUv = uv;
    flowUv.y += t * 0.75;

    vec2 firstId = floor(flowUv * grid);
    float columnOffset = MicaHash21(vec2(firstId.x, 7.0));
    flowUv.y += columnOffset;

    vec2 id = floor(flowUv * grid);
    vec2 st = fract(flowUv * grid) - vec2(0.5, 0.0);
    vec3 rnd = MicaHash31(id);

    float rawX = rnd.x - 0.5;
    float pathY = baseUv.y * 20.0;
    float wobble = sin(pathY + sin(pathY));
    float x = (rawX + wobble * (0.5 - abs(rawX)) * (rnd.z - 0.5)) * 0.70;

    float phase = fract(t + rnd.z);
    float y = 0.05 + 0.90 * MicaMovingBell(phase);

    vec2 bodyDelta = st - vec2(x, y);
    float bodyDistance = length(bodyDelta * vec2(1.0, 6.0));
    float mainDrop = 1.0 - smoothstep(0.0, 0.385, bodyDistance);

    float below = sqrt(1.0 - smoothstep(y, 1.0, st.y));
    float trailFront = smoothstep(-0.02, 0.02, st.y - y);
    float beadY = fract(baseUv.y * 10.0) + (st.y - 0.5);
    float beadDistance = length(st - vec2(x, beadY));
    float bead = (1.0 - smoothstep(0.0, 0.285, beadDistance)) * below * trailFront;

    return mainDrop + bead;
}

float MicaTrailV5(vec2 uv, float t) {
    vec2 baseUv = uv;
    const vec2 grid = vec2(12.0, 2.0);

    vec2 flowUv = uv;
    flowUv.y += t * 0.75;

    vec2 firstId = floor(flowUv * grid);
    float columnOffset = MicaHash21(vec2(firstId.x, 7.0));
    flowUv.y += columnOffset;

    vec2 id = floor(flowUv * grid);
    vec2 st = fract(flowUv * grid) - vec2(0.5, 0.0);
    vec3 rnd = MicaHash31(id);

    float rawX = rnd.x - 0.5;
    float pathY = baseUv.y * 20.0;
    float wobble = sin(pathY + sin(pathY));
    float x = (rawX + wobble * (0.5 - abs(rawX)) * (rnd.z - 0.5)) * 0.70;

    float phase = fract(t + rnd.z);
    float headY = 0.05 + 0.90 * MicaMovingBell(phase);

    // Explicitly express the reversed-envelope behavior instead of relying
    // on smoothstep with reversed edges.
    float behind = sqrt(1.0 - smoothstep(headY, 1.0, st.y));
    float dx = abs(st.x - x);

    float outerWidth = 0.23 * behind;
    float coreWidth = 0.15 * behind * behind;
    float widthMask = 1.0 - smoothstep(
        coreWidth,
        max(outerWidth, coreWidth + 0.0001),
        dx
    );

    float frontCutoff = smoothstep(-0.02, 0.02, st.y - headY);
    float lengthFade = behind * behind;

    return widthMask * frontCutoff * lengthFade * 0.93;
}

vec2 Drops(vec2 uv, float t, float l0, float l1, float l2) {
    float s = MicaStaticDropsV2(uv, t)*l0;

    float m1 = MicaMovingDropMaskV2(uv, t)*l1;
    float m2 = MicaMovingDropMaskV2(uv*1.85, t)*l2;
    float trail1 = MicaTrailV5(uv, t)*l1;
    float trail2 = MicaTrailV5(uv*1.85, t)*l2;

    float c = s+m1+m2;
    c = smoothstep(0.3, 1.0, c);

    return vec2(c, max(trail1*l0, trail2*l1));
}

vec2 MicaRefractionNormalV2(
    vec2 uv,
    float t,
    float l0,
    float l1,
    float l2,
    float centerMask
) {
    const float normalStep = 0.001;

    vec2 sampleMask = vec2(
        Drops(uv + vec2(normalStep, 0.0), t, l0, l1, l2).x,
        Drops(uv + vec2(0.0, normalStep), t, l0, l1, l2).x
    );

    return sampleMask - vec2(centerMask);
}

float MicaBlurLodV1(
    float maxBlur,
    float minBlur,
    vec2 rainField
) {
    float clearDrop = smoothstep(0.1, 0.2, rainField.x);
    float wetGlassLod = maxBlur - rainField.y;
    float lod = wetGlassLod + (minBlur - wetGlassLod) * clearDrop;
    return lod * 0.60;
}

vec3 MicaWetGlassPostV1(
    vec3 color,
    vec2 uv01,
    float timeSeconds
) {
    float colorPhase = (timeSeconds + 3.0) * 0.10;
    float tintAmount = sin(colorPhase) * 0.5 + 0.5;
    vec3 tint = vec3(1.0) +
        (vec3(0.8, 0.9, 1.3) - vec3(1.0)) * tintAmount;

    float startupFade = smoothstep(0.0, 10.0, timeSeconds);
    vec2 centered = uv01 - 0.5;
    float vignette = 1.0 - dot(centered, centered);

    return color * tint * startupFade * vignette;
}

void main()
{
    vec2 uv = (fragCoord.xy - 0.5 * uResolution.xy) / uResolution.y;
    vec2 UV = fragCoord.xy / uResolution.xy;
    float T = uTime;
    float t = T * 0.2;

    float rainAmount = sin(T * 0.05) * 0.3 + 0.7;
    float maxBlur = mix(3.0, 6.0, rainAmount);
    float minBlur = 2.0;

    uv *= 1.5;
    UV = (UV - 0.5) * 0.96 + 0.5;

    float staticDrops = smoothstep(-0.5, 1.0, rainAmount) * 2.0;
    float layer1 = smoothstep(0.25, 0.75, rainAmount);
    float layer2 = smoothstep(0.0, 0.5, rainAmount);

    vec2 rainField = Drops(uv, t, staticDrops, layer1, layer2);
    vec2 normal = MicaRefractionNormalV2(
        uv,
        t,
        staticDrops,
        layer1,
        layer2,
        rainField.x
    );

    float focus = MicaBlurLodV1(maxBlur, minBlur, rainField);
    vec2 sceneUv = UV + normal;
    vec3 color = textureLod(
        uArtwork,
        sceneUv * vec2(1.0, -1.0) + vec2(0.0, 1.0),
        focus
    ).rgb;

    color = MicaWetGlassPostV1(color, UV, T);
    fragColor = vec4(color, 1.0);
}"""

