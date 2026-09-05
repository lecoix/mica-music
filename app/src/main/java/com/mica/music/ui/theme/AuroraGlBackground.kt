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
import kotlin.math.roundToInt

/**
 * Experimental port of "Auroras" by nimitz (2017), Shadertoy XtGGRt.
 *
 * Original shader license: Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported.
 * Source: https://www.shadertoy.com/view/XtGGRt
 *
 * Keep this implementation experimental until project-level license compatibility is reviewed.
 */
@Composable
internal fun AuroraGlBackground(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(AuroraBackgroundSurface),
    ) {
        if (!MicaScreenshotGoldenMode.enabled) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> AuroraGlBackgroundView(context) },
                onRelease = { view -> view.release() },
            )
        }
    }
}

internal val AuroraBackgroundSurface = Color(0xFF07111F)
internal val AuroraForegroundAccent = Color(0xFF78E6B7)

private class AuroraGlBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var renderThread: AuroraRenderThread? = null

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
        renderThread = AuroraRenderThread(
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

private class AuroraRenderThread(
    surfaceTexture: SurfaceTexture,
    initialWidth: Int,
    initialHeight: Int,
) : Thread("mica-aurora-gl") {

    private val surface = Surface(surfaceTexture)
    private val lock = Object()
    private val renderer = AuroraRenderer()

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
                DiagnosticLog.event("AuroraGl", "egl-init-failed")
                return
            }
            renderer.onSurfaceCreated()
            var nextFrameAt = SystemClock.uptimeMillis()
            while (running) {
                var nextWidth: Int
                var nextHeight: Int
                var applySize: Boolean
                synchronized(lock) {
                    nextWidth = width
                    nextHeight = height
                    applySize = sizeChanged
                    sizeChanged = false
                }
                if (applySize) {
                    renderer.onSurfaceChanged(nextWidth, nextHeight)
                }
                renderer.render()
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    DiagnosticLog.event("AuroraGl", "egl-swap-failed error=${EGL14.eglGetError()}")
                    break
                }

                nextFrameAt += AuroraFrameIntervalMs
                val sleepMs = nextFrameAt - SystemClock.uptimeMillis()
                if (sleepMs > 1L) {
                    runCatching { sleep(sleepMs) }
                } else {
                    nextFrameAt = SystemClock.uptimeMillis()
                }
            }
        } catch (throwable: Throwable) {
            DiagnosticLog.event("AuroraGl", "renderer-stopped", throwable)
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
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)) return false
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

private class AuroraRenderer {
    private var width = 1
    private var height = 1
    private var lowWidth = 1
    private var lowHeight = 1

    private var auroraProgram = 0
    private var auroraTimeLocation = -1
    private var auroraResolutionLocation = -1
    private var auroraPositionLocation = -1

    private var compositeProgram = 0
    private var compositeTimeLocation = -1
    private var compositeResolutionLocation = -1
    private var compositePositionLocation = -1
    private var compositeTextureLocation = -1

    private var framebuffer = 0
    private var lowResolutionTexture = 0
    private var startMs = SystemClock.uptimeMillis()

    private val quadBuffer: FloatBuffer = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    ).toFloatBuffer()

    fun onSurfaceCreated() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0.027f, 0.067f, 0.122f, 1f)

        auroraProgram = createProgram(AuroraVertexShader, AuroraFragmentShader)
        auroraPositionLocation = GLES20.glGetAttribLocation(auroraProgram, "aPosition")
        auroraTimeLocation = GLES20.glGetUniformLocation(auroraProgram, "uTime")
        auroraResolutionLocation = GLES20.glGetUniformLocation(auroraProgram, "uResolution")

        compositeProgram = createProgram(AuroraVertexShader, AuroraCompositeFragmentShader)
        compositePositionLocation = GLES20.glGetAttribLocation(compositeProgram, "aPosition")
        compositeTimeLocation = GLES20.glGetUniformLocation(compositeProgram, "uTime")
        compositeResolutionLocation = GLES20.glGetUniformLocation(compositeProgram, "uResolution")
        compositeTextureLocation = GLES20.glGetUniformLocation(compositeProgram, "uAuroraTexture")

        startMs = SystemClock.uptimeMillis()

        DiagnosticLog.event(
            "AuroraGl",
            "surface-created vendor=${GLES20.glGetString(GLES20.GL_VENDOR)} " +
                "renderer=${GLES20.glGetString(GLES20.GL_RENDERER)}",
        )
    }

    fun onSurfaceChanged(newWidth: Int, newHeight: Int) {
        width = newWidth.coerceAtLeast(1)
        height = newHeight.coerceAtLeast(1)
        val nextLowWidth = (width * AuroraRenderScale).roundToInt().coerceAtLeast(1)
        val nextLowHeight = (height * AuroraRenderScale).roundToInt().coerceAtLeast(1)
        if (nextLowWidth != lowWidth || nextLowHeight != lowHeight || framebuffer == 0) {
            lowWidth = nextLowWidth
            lowHeight = nextLowHeight
            recreateLowResolutionTarget()
        }
    }

    fun render() {
        if (auroraProgram == 0 || compositeProgram == 0 || framebuffer == 0) return
        val timeSeconds =
            ((SystemClock.uptimeMillis() - startMs) / 1000f) * AuroraTimeScale

        // Expensive aurora + lake field stays at half resolution.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, lowWidth, lowHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(auroraProgram)
        GLES20.glUniform1f(auroraTimeLocation, timeSeconds)
        GLES20.glUniform2f(auroraResolutionLocation, lowWidth.toFloat(), lowHeight.toFloat())
        drawQuad(auroraPositionLocation)

        // Composite at native resolution so point-like stars are never upscaled/blurred.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(compositeProgram)
        GLES20.glUniform1f(compositeTimeLocation, timeSeconds)
        GLES20.glUniform2f(compositeResolutionLocation, width.toFloat(), height.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lowResolutionTexture)
        GLES20.glUniform1i(compositeTextureLocation, 0)
        drawQuad(compositePositionLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() {
        releaseLowResolutionTarget()
        if (auroraProgram != 0) GLES20.glDeleteProgram(auroraProgram)
        if (compositeProgram != 0) GLES20.glDeleteProgram(compositeProgram)
        auroraProgram = 0
        compositeProgram = 0
    }

    private fun recreateLowResolutionTarget() {
        releaseLowResolutionTarget()

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        lowResolutionTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lowResolutionTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            lowWidth,
            lowHeight,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )

        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        framebuffer = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            lowResolutionTexture,
            0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Aurora framebuffer incomplete: 0x${status.toString(16)}"
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun releaseLowResolutionTarget() {
        if (framebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            framebuffer = 0
        }
        if (lowResolutionTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lowResolutionTexture), 0)
            lowResolutionTexture = 0
        }
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
        check(status[0] == GLES20.GL_TRUE) { "Aurora GL program link failed: $log" }
        return nextProgram
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val log = GLES20.glGetShaderInfoLog(shader)
        check(status[0] == GLES20.GL_TRUE) { "Aurora GL shader compile failed: $log" }
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
// Match the wewa built-in preset used by the reference article.
private const val AuroraRenderScale = 0.5f
private const val AuroraTimeScale = 0.2f
private const val AuroraFrameIntervalMs = 50L
private const val FloatBytes = 4
private const val EglOpenGlEs2Bit = 4
private const val EglContextClientVersion = 0x3098

private const val AuroraVertexShader = """
attribute vec2 aPosition;
varying vec2 vUv;

void main() {
    vUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

/*
 * The aurora field, camera, reflection and emission equations intentionally stay close to
 * nimitz/XtGGRt. The uint star hash from the current Shadertoy source is replaced by the
 * float hash used by a published GLES/Unity port so this remains OpenGL ES 2.0 compatible.
 */
private const val AuroraFragmentShader = """
precision highp float;

uniform vec2 uResolution;
uniform float uTime;

mat2 mm2(float a) {
    float c = cos(a);
    float s = sin(a);
    return mat2(c, s, -s, c);
}

mat2 m2 = mat2(0.95534, 0.29552, -0.29552, 0.95534);

float tri(float x) {
    return clamp(abs(fract(x) - 0.5), 0.01, 0.49);
}

vec2 tri2(vec2 p) {
    return vec2(tri(p.x) + tri(p.y), tri(p.y + tri(p.x)));
}

float triNoise2d(vec2 p, float spd) {
    float z = 1.8;
    float z2 = 2.5;
    float rz = 0.0;
    p *= mm2(p.x * 0.06);
    vec2 bp = p;

    for (int j = 0; j < 4; ++j) {
        vec2 dg = tri2(bp * 1.85) * 0.75;
        dg *= mm2(uTime * spd);
        p -= dg / z2;

        bp *= 1.3;
        z2 *= 0.45;
        z *= 0.42;
        p *= 1.21 + (rz - 1.0) * 0.02;

        rz += tri(p.x + tri(p.y)) * z;
        p *= -m2;
    }

    return clamp(1.0 / pow(rz * 29.0, 1.3), 0.0, 0.55);
}

float hash21(vec2 n) {
    return fract(sin(dot(n, vec2(12.9898, 4.1414))) * 43758.5453);
}

vec4 aurora(vec3 ro, vec3 rd) {
    vec4 col = vec4(0.0);
    vec4 avgCol = vec4(0.0);

    for (int j = 0; j < 32; ++j) {
        float i = float(j);
        float of = 0.006 * hash21(gl_FragCoord.xy) * smoothstep(0.0, 15.0, i);
        float pt = ((0.8 + pow(i, 1.4) * 0.002) - ro.y) / (rd.y * 2.0 + 0.4);
        pt -= of;

        vec3 bpos = ro + pt * rd;
        float rzt = triNoise2d(bpos.zx, 0.06);
        vec4 col2 = vec4(0.0, 0.0, 0.0, rzt);
        col2.rgb = (sin(1.0 - vec3(2.15, -0.5, 1.2) + i * 0.043) * 0.5 + 0.5) * rzt;
        avgCol = mix(avgCol, col2, 0.5);
        col += avgCol * exp2(-i * 0.065 - 2.5) * smoothstep(0.0, 5.0, i);
    }

    col *= clamp(rd.y * 15.0 + 0.4, 0.0, 1.0);
    return col * 1.8;
}

vec3 hash33(vec3 p) {
    p = fract(p * vec3(443.8975, 397.2973, 491.1871));
    p += dot(p.zxy, p.yxz + 19.27);
    return fract(vec3(p.x * p.y, p.z * p.x, p.y * p.z));
}

vec3 stars(vec3 p) {
    vec3 c = vec3(0.0);
    float res = uResolution.x;

    for (int j = 0; j < 4; ++j) {
        float i = float(j);
        vec3 q = fract(p * (0.15 * res)) - 0.5;
        vec3 id = floor(p * (0.15 * res));
        vec2 rn = hash33(id).xy;
        float c2 = 1.0 - smoothstep(0.0, 0.6, length(q));
        c2 *= step(rn.x, 0.0005 + i * i * 0.001);
        c += c2 * (mix(vec3(1.0, 0.49, 0.1), vec3(0.75, 0.9, 1.0), rn.y) * 0.1 + 0.9);
        p *= 1.3;
    }

    return c * c * 0.8;
}

vec3 bg(vec3 rd) {
    float sd = dot(normalize(vec3(-0.5, -0.6, 0.9)), rd) * 0.5 + 0.5;
    sd = pow(sd, 5.0);
    vec3 col = mix(vec3(0.05, 0.1, 0.2), vec3(0.1, 0.05, 0.2), sd);
    return col * 0.63;
}

void main() {
    vec2 q = gl_FragCoord.xy / uResolution.xy;
    vec2 p = q - 0.5;
    p.x *= uResolution.x / uResolution.y;

    vec3 ro = vec3(0.0, 0.0, -6.7);
    vec3 rd = normalize(vec3(p, 1.3));

    // Original Shadertoy default when no mouse input is present.
    vec2 mo = vec2(-0.1, 0.1);
    mo.x *= uResolution.x / uResolution.y;
    rd.yz *= mm2(mo.y);
    rd.xz *= mm2(mo.x + sin(uTime * 0.05) * 0.2);

    vec3 brd = rd;
    float fade = smoothstep(0.0, 0.01, abs(brd.y)) * 0.1 + 0.9;
    vec3 col = bg(rd) * fade;
    float starVisibility;

    if (rd.y > 0.0) {
        vec4 aur = smoothstep(0.0, 1.5, aurora(ro, rd)) * fade;
        starVisibility = 1.0 - aur.a;
        col = col * starVisibility + aur.rgb;
    } else {
        rd.y = abs(rd.y);
        col = bg(rd) * fade * 0.6;
        vec4 aur = smoothstep(0.0, 2.5, aurora(ro, rd));
        starVisibility = (1.0 - aur.a) * 0.1;
        col = col * (1.0 - aur.a) + aur.rgb;

        vec3 pos = ro + ((0.5 - ro.y) / rd.y) * rd;
        float nz2 = triNoise2d(pos.xz * vec2(0.5, 0.7), 0.0);
        col += mix(
            vec3(0.2, 0.25, 0.5) * 0.08,
            vec3(0.3, 0.3, 0.5) * 0.7,
            nz2 * 0.4
        );
    }

    // Alpha is repurposed as the full-resolution star contribution mask.
    gl_FragColor = vec4(col, clamp(starVisibility, 0.0, 1.0));
}
"""

private const val AuroraCompositeFragmentShader = """
precision highp float;

uniform sampler2D uAuroraTexture;
uniform vec2 uResolution;
uniform float uTime;

varying vec2 vUv;

mat2 mm2(float a) {
    float c = cos(a);
    float s = sin(a);
    return mat2(c, s, -s, c);
}

vec3 hash33(vec3 p) {
    p = fract(p * vec3(443.8975, 397.2973, 491.1871));
    p += dot(p.zxy, p.yxz + 19.27);
    return fract(vec3(p.x * p.y, p.z * p.x, p.y * p.z));
}

vec3 stars(vec3 p) {
    vec3 c = vec3(0.0);
    float res = uResolution.x;

    for (int j = 0; j < 4; ++j) {
        float i = float(j);
        vec3 q = fract(p * (0.15 * res)) - 0.5;
        vec3 id = floor(p * (0.15 * res));
        vec3 rn = hash33(id);
        float c2 = 1.0 - smoothstep(0.0, 0.6, length(q));
        c2 *= step(rn.x, 0.0005 + i * i * 0.001);

        // Rare burst + fast twinkle: most stars stay nearly steady, but when a
        // burst arrives the brightness changes quickly and much more visibly.
        float phase = rn.y * 6.2831853 + i * 1.7;
        float speed = mix(14.0, 30.0, rn.z);
        float primary = sin(uTime * speed + phase) * 0.5 + 0.5;
        float secondary =
            sin(uTime * (speed * 2.07 + 0.51) + phase * 1.53) * 0.5 + 0.5;
        float fastTwinkle = mix(primary, secondary, 0.38);

        // uTime is already 0.2x. This slow gate gives each star a relatively
        // infrequent active window instead of making the whole sky pulse all the time.
        float burstPhase = rn.x * 6.2831853 + rn.z * 3.1;
        float burstSpeed = mix(1.1, 2.2, rn.y);
        float burst = sin(uTime * burstSpeed + burstPhase) * 0.5 + 0.5;
        float gate = smoothstep(0.84, 0.97, burst);

        // The calm state is almost steady. During a burst, pre-square intensity
        // can fall to 0.50, which becomes ~0.25 after c*c for a clearly visible flash.
        float calmIntensity = 0.90;
        float activeIntensity = mix(0.50, 1.0, fastTwinkle);
        float intensity = mix(calmIntensity, activeIntensity, gate);
        vec3 starColor =
            mix(vec3(1.0, 0.49, 0.1), vec3(0.75, 0.9, 1.0), rn.y) * 0.1 + 0.9;
        c += c2 * starColor * intensity;
        p *= 1.3;
    }

    return c * c * 0.8;
}

void main() {
    vec4 base = texture2D(uAuroraTexture, vUv);

    vec2 p = vUv - 0.5;
    p.x *= uResolution.x / uResolution.y;
    vec3 rd = normalize(vec3(p, 1.3));

    vec2 mo = vec2(-0.1, 0.1);
    mo.x *= uResolution.x / uResolution.y;
    rd.yz *= mm2(mo.y);
    rd.xz *= mm2(mo.x + sin(uTime * 0.05) * 0.2);

    // Stars are evaluated at the actual surface resolution and added only where
    // the low-resolution aurora pass says they remain visible.
    vec3 col = base.rgb + stars(rd) * base.a;
    gl_FragColor = vec4(col, 1.0);
}
"""
