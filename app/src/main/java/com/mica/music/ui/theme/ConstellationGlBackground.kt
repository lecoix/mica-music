package com.mica.music.ui.theme

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.mica.music.ui.MicaScreenshotGoldenMode
import com.mica.music.util.DiagnosticLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Mica-owned procedural constellation background.
 *
 * The Android/EGL renderer is intentionally self-contained. It renders a procedural star field
 * in one full-screen pass, then draws a tiny fixed constellation graph with GL lines/points.
 */
@Composable
internal fun ConstellationGlBackground(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(ConstellationBackgroundSurface),
    ) {
        if (!MicaScreenshotGoldenMode.enabled) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> ConstellationGlBackgroundView(context) },
                onRelease = { view -> view.release() },
            )
        }
    }
}

internal val ConstellationBackgroundSurface = Color(0xFF07111F)
internal val ConstellationForegroundAccent = Color(0xFF9CCBFF)

private class ConstellationGlBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var renderThread: ConstellationRenderThread? = null

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    fun release() {
        renderThread?.requestStopAndJoin()
        renderThread = null
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        release()
        surfaceTexture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        renderThread = ConstellationRenderThread(
            surfaceTexture,
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
        ).also(Thread::start)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        renderThread?.resize(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
}

private class ConstellationRenderThread(
    surfaceTexture: SurfaceTexture,
    initialWidth: Int,
    initialHeight: Int,
) : Thread("mica-constellation-gl") {

    private val surface = Surface(surfaceTexture)
    private val lock = Object()
    private val renderer = ConstellationRenderer()

    @Volatile
    private var running = true
    private var width = initialWidth
    private var height = initialHeight
    private var sizeChanged = true

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun resize(newWidth: Int, newHeight: Int) {
        synchronized(lock) {
            width = newWidth.coerceAtLeast(1)
            height = newHeight.coerceAtLeast(1)
            sizeChanged = true
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
            if (!initEgl()) {
                DiagnosticLog.event("ConstellationGl", "egl-init-failed")
                return
            }
            renderer.onSurfaceCreated()
            var nextFrameAt = SystemClock.uptimeMillis()
            while (running) {
                val nextWidth: Int
                val nextHeight: Int
                val applySize: Boolean
                synchronized(lock) {
                    nextWidth = width
                    nextHeight = height
                    applySize = sizeChanged
                    sizeChanged = false
                }
                if (applySize) {
                    GLES20.glViewport(0, 0, nextWidth, nextHeight)
                    renderer.onSurfaceChanged(nextWidth, nextHeight)
                }
                renderer.render()
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    DiagnosticLog.event(
                        "ConstellationGl",
                        "egl-swap-failed error=${EGL14.eglGetError()}",
                    )
                    break
                }

                nextFrameAt += ConstellationFrameIntervalMs
                val sleepMs = nextFrameAt - SystemClock.uptimeMillis()
                if (sleepMs > 1L) {
                    runCatching { sleep(sleepMs) }
                } else {
                    nextFrameAt = SystemClock.uptimeMillis()
                }
            }
        } catch (throwable: Throwable) {
            DiagnosticLog.event("ConstellationGl", "renderer-stopped", throwable)
        } finally {
            renderer.release()
            releaseEgl()
            surface.release()
        }
    }

    private fun initEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EglOpenGlEs2Bit,
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
            return false
        }
        val config = configs[0] ?: return false

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EglContextClientVersion, 2, EGL14.EGL_NONE),
            0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

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

private class ConstellationRenderer {
    private var width = 1
    private var height = 1
    private var startMs = SystemClock.uptimeMillis()

    private var backgroundProgram = 0
    private var backgroundPositionLocation = -1
    private var backgroundResolutionLocation = -1
    private var backgroundTimeLocation = -1

    private var lineProgram = 0
    private var linePositionLocation = -1
    private var lineTimeLocation = -1

    private var pointProgram = 0
    private var pointPositionLocation = -1
    private var pointSeedLocation = -1
    private var pointTimeLocation = -1

    private val quadBuffer = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    ).toFloatBuffer()

    // Two deliberately simple Mica-owned constellation glyphs. Coordinates are NDC so the graph
    // stays crisp and cheap: GL draws 11 short lines instead of evaluating line distance per pixel.
    private val lineBuffer = floatArrayOf(
        // Upper W.
        -0.78f, 0.68f, -0.48f, 0.78f,
        -0.48f, 0.78f, -0.18f, 0.62f,
        -0.18f, 0.62f, 0.14f, 0.74f,
        0.14f, 0.74f, 0.46f, 0.57f,
        // Central hunter-like graph.
        -0.40f, 0.25f, -0.24f, 0.02f,
        0.36f, 0.22f, 0.28f, -0.02f,
        -0.24f, 0.02f, 0.02f, 0.00f,
        0.02f, 0.00f, 0.28f, -0.02f,
        -0.24f, 0.02f, -0.32f, -0.32f,
        0.28f, -0.02f, 0.38f, -0.34f,
        -0.40f, 0.25f, 0.36f, 0.22f,
    ).toFloatBuffer()

    private val pointBuffer = floatArrayOf(
        -0.78f, 0.68f, 0.13f,
        -0.48f, 0.78f, 0.91f,
        -0.18f, 0.62f, 1.73f,
        0.14f, 0.74f, 2.47f,
        0.46f, 0.57f, 3.28f,
        -0.40f, 0.25f, 4.06f,
        0.36f, 0.22f, 4.89f,
        -0.24f, 0.02f, 5.52f,
        0.02f, 0.00f, 6.31f,
        0.28f, -0.02f, 7.14f,
        -0.32f, -0.32f, 8.03f,
        0.38f, -0.34f, 8.77f,
    ).toFloatBuffer()

    fun onSurfaceCreated() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(0.027f, 0.067f, 0.122f, 1f)

        backgroundProgram = createProgram(ConstellationVertexShader, StarFieldFragmentShader)
        backgroundPositionLocation = GLES20.glGetAttribLocation(backgroundProgram, "aPosition")
        backgroundResolutionLocation = GLES20.glGetUniformLocation(backgroundProgram, "uResolution")
        backgroundTimeLocation = GLES20.glGetUniformLocation(backgroundProgram, "uTime")

        lineProgram = createProgram(ConstellationLineVertexShader, ConstellationLineFragmentShader)
        linePositionLocation = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        lineTimeLocation = GLES20.glGetUniformLocation(lineProgram, "uTime")

        pointProgram = createProgram(ConstellationPointVertexShader, ConstellationPointFragmentShader)
        pointPositionLocation = GLES20.glGetAttribLocation(pointProgram, "aPosition")
        pointSeedLocation = GLES20.glGetAttribLocation(pointProgram, "aSeed")
        pointTimeLocation = GLES20.glGetUniformLocation(pointProgram, "uTime")

        startMs = SystemClock.uptimeMillis()
        DiagnosticLog.event(
            "ConstellationGl",
            "surface-created vendor=${GLES20.glGetString(GLES20.GL_VENDOR)} " +
                "renderer=${GLES20.glGetString(GLES20.GL_RENDERER)}",
        )
    }

    fun onSurfaceChanged(newWidth: Int, newHeight: Int) {
        width = newWidth.coerceAtLeast(1)
        height = newHeight.coerceAtLeast(1)
    }

    fun render() {
        if (backgroundProgram == 0 || lineProgram == 0 || pointProgram == 0) return
        val timeSeconds = (SystemClock.uptimeMillis() - startMs) / 1000f

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(backgroundProgram)
        GLES20.glUniform2f(backgroundResolutionLocation, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(backgroundTimeLocation, timeSeconds)
        drawQuad(backgroundPositionLocation)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(lineProgram)
        GLES20.glUniform1f(lineTimeLocation, timeSeconds)
        GLES20.glLineWidth(1.2f)
        lineBuffer.position(0)
        GLES20.glEnableVertexAttribArray(linePositionLocation)
        GLES20.glVertexAttribPointer(
            linePositionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            2 * FloatBytes,
            lineBuffer,
        )
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, ConstellationLineVertexCount)
        GLES20.glDisableVertexAttribArray(linePositionLocation)

        GLES20.glUseProgram(pointProgram)
        GLES20.glUniform1f(pointTimeLocation, timeSeconds)
        pointBuffer.position(0)
        GLES20.glEnableVertexAttribArray(pointPositionLocation)
        GLES20.glVertexAttribPointer(
            pointPositionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            3 * FloatBytes,
            pointBuffer,
        )
        pointBuffer.position(2)
        GLES20.glEnableVertexAttribArray(pointSeedLocation)
        GLES20.glVertexAttribPointer(
            pointSeedLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            3 * FloatBytes,
            pointBuffer,
        )
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, ConstellationPointCount)
        GLES20.glDisableVertexAttribArray(pointPositionLocation)
        GLES20.glDisableVertexAttribArray(pointSeedLocation)

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        if (backgroundProgram != 0) GLES20.glDeleteProgram(backgroundProgram)
        if (lineProgram != 0) GLES20.glDeleteProgram(lineProgram)
        if (pointProgram != 0) GLES20.glDeleteProgram(pointProgram)
        backgroundProgram = 0
        lineProgram = 0
        pointProgram = 0
    }

    private fun drawQuad(positionLocation: Int) {
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            2 * FloatBytes,
            quadBuffer,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val nextProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(nextProgram, vertex)
        GLES20.glAttachShader(nextProgram, fragment)
        GLES20.glLinkProgram(nextProgram)

        val status = IntArray(1)
        GLES20.glGetProgramiv(nextProgram, GLES20.GL_LINK_STATUS, status, 0)
        val log = GLES20.glGetProgramInfoLog(nextProgram)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) { "Constellation GL program link failed: $log" }
        return nextProgram
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val log = GLES20.glGetShaderInfoLog(shader)
        check(status[0] == GLES20.GL_TRUE) { "Constellation GL shader compile failed: $log" }
        return shader
    }

    private fun FloatArray.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * FloatBytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(this@toFloatBuffer)
                position(0)
            }
}

private const val ConstellationFrameIntervalMs = 42L
private const val ConstellationLineVertexCount = 22
private const val ConstellationPointCount = 12
private const val FloatBytes = 4
private const val EglOpenGlEs2Bit = 4
private const val EglContextClientVersion = 0x3098

private const val ConstellationVertexShader = """
attribute vec2 aPosition;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

private const val StarFieldFragmentShader = """
precision highp float;

uniform vec2 uResolution;
uniform float uTime;

float hash21(vec2 p) {
    vec2 q = fract(p * vec2(127.13, 311.71));
    q += dot(q, q.yx + 23.37);
    return fract(q.x * q.y);
}

vec2 hash22(vec2 p) {
    float a = hash21(p);
    float b = hash21(p + vec2(19.19 + a, 7.73 - a));
    return vec2(a, b);
}

vec3 starLayer(vec2 frag, float cellPx, float density, float seed) {
    vec2 grid = frag / cellPx;
    vec2 id = floor(grid);
    vec2 local = fract(grid) - 0.5;

    vec2 jitter = (hash22(id + seed) - 0.5) * 0.74;
    float presenceRandom = hash21(id + seed * 2.91);
    float presence = step(presenceRandom, density);
    float shapeRandom = hash21(id + seed * 5.43 + 11.0);

    float d = length(local - jitter);
    float radius = mix(0.014, 0.050, shapeRandom);
    float core = 1.0 - smoothstep(radius, radius * 2.1, d);
    float halo = 1.0 - smoothstep(radius * 1.7, radius * 5.8, d);

    float phase = hash21(id + seed * 9.17) * 6.2831853;
    float speed = mix(5.0, 11.0, shapeRandom);
    float fast = sin(uTime * speed + phase) * 0.5 + 0.5;

    float burstSpeed = mix(0.24, 0.52, presenceRandom);
    float burst = sin(uTime * burstSpeed + phase * 1.73) * 0.5 + 0.5;
    float gate = smoothstep(0.91, 0.995, burst);

    float intensity = mix(0.90, mix(0.34, 1.0, fast), gate);
    vec3 warm = vec3(1.00, 0.84, 0.68);
    vec3 cool = vec3(0.70, 0.86, 1.00);
    vec3 tint = mix(warm, cool, hash21(id + seed * 13.7));

    float energy = presence * (core + halo * 0.18) * intensity;
    energy *= mix(0.55, 1.15, shapeRandom);
    return tint * energy;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution.xy;

    vec3 top = vec3(0.010, 0.020, 0.052);
    vec3 bottom = vec3(0.025, 0.072, 0.120);
    float vertical = smoothstep(0.0, 1.0, 1.0 - uv.y);
    vec3 col = mix(top, bottom, vertical * 0.78);

    vec2 centered = uv - 0.5;
    centered.x *= uResolution.x / uResolution.y;
    float softGlow = exp(-dot(centered - vec2(0.03, 0.06), centered - vec2(0.03, 0.06)) * 7.0);
    col += vec3(0.020, 0.038, 0.075) * softGlow;

    col += starLayer(gl_FragCoord.xy, 92.0, 0.43, 1.7);
    col += starLayer(gl_FragCoord.xy, 56.0, 0.13, 8.2) * 0.82;
    col += starLayer(gl_FragCoord.xy, 34.0, 0.030, 17.4) * 0.62;

    float vignette = 1.0 - smoothstep(0.24, 0.88, length(centered * vec2(1.30, 0.92)));
    col *= mix(0.72, 1.0, vignette);

    gl_FragColor = vec4(col, 1.0);
}
"""

private const val ConstellationLineVertexShader = """
attribute vec2 aPosition;
uniform float uTime;

void main() {
    vec2 drift = vec2(
        sin(uTime * 0.045) * 0.004,
        cos(uTime * 0.038) * 0.003
    );
    gl_Position = vec4(aPosition + drift, 0.0, 1.0);
}
"""

private const val ConstellationLineFragmentShader = """
precision mediump float;

uniform float uTime;

void main() {
    float pulse = sin(uTime * 0.58) * 0.5 + 0.5;
    vec3 lineColor = mix(vec3(0.48, 0.66, 0.90), vec3(0.66, 0.82, 1.00), pulse);
    float alpha = mix(0.13, 0.20, pulse);
    gl_FragColor = vec4(lineColor, alpha);
}
"""

private const val ConstellationPointVertexShader = """
attribute vec2 aPosition;
attribute float aSeed;
uniform float uTime;

varying float vBrightness;
varying float vTint;

void main() {
    vec2 drift = vec2(
        sin(uTime * 0.045) * 0.004,
        cos(uTime * 0.038) * 0.003
    );
    gl_Position = vec4(aPosition + drift, 0.0, 1.0);

    float phase = aSeed * 2.731;
    float fast = sin(uTime * (5.5 + mod(aSeed * 1.91, 6.5)) + phase) * 0.5 + 0.5;
    float burst = sin(uTime * (0.22 + mod(aSeed * 0.071, 0.24)) + phase * 1.37) * 0.5 + 0.5;
    float gate = smoothstep(0.90, 0.995, burst);
    vBrightness = mix(0.92, mix(0.34, 1.0, fast), gate);
    vTint = fract(aSeed * 0.6180339);
    gl_PointSize = mix(6.0, 11.0, fract(aSeed * 0.371));
}
"""

private const val ConstellationPointFragmentShader = """
precision mediump float;

varying float vBrightness;
varying float vTint;

void main() {
    vec2 p = gl_PointCoord - 0.5;
    float d = length(p);
    float alpha = 1.0 - smoothstep(0.18, 0.50, d);
    if (alpha <= 0.0) discard;

    float core = 1.0 - smoothstep(0.0, 0.20, d);
    vec3 warm = vec3(1.00, 0.85, 0.70);
    vec3 cool = vec3(0.72, 0.88, 1.00);
    vec3 color = mix(warm, cool, vTint);
    color *= (0.72 + core * 0.58) * vBrightness;
    gl_FragColor = vec4(color, alpha * vBrightness);
}
"""
