package com.mica.music.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
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
import com.mica.music.util.DiagnosticLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

internal class DynamicLightGlBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var renderThread: DynamicLightRenderThread? = null
    private var coverBitmap: Bitmap? = null
    private var fallbackColor: Int = 0xff202020.toInt()

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    fun setCover(bitmap: Bitmap?, fallbackColor: Int) {
        coverBitmap = bitmap
        this.fallbackColor = fallbackColor
        renderThread?.setCover(bitmap, fallbackColor)
    }

    fun release() {
        renderThread?.requestStopAndJoin()
        renderThread = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        release()
        renderThread = DynamicLightRenderThread(surface, width, height).also { thread ->
            thread.start()
            thread.setCover(coverBitmap, fallbackColor)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}

private data class PendingDynamicLightCover(
    val bitmap: Bitmap?,
    val fallbackColor: Int,
)

private class DynamicLightRenderThread(
    surfaceTexture: SurfaceTexture,
    initialWidth: Int,
    initialHeight: Int,
) : Thread("mica-dynamic-light-gl") {

    private val surface = Surface(surfaceTexture)
    private val lock = Object()
    private val renderer = DynamicLightRenderer()

    @Volatile
    private var running = true
    private var pendingCover: PendingDynamicLightCover? = null
    private var width = initialWidth
    private var height = initialHeight
    private var sizeChanged = true

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun setCover(bitmap: Bitmap?, fallbackColor: Int) {
        synchronized(lock) {
            pendingCover = PendingDynamicLightCover(bitmap, fallbackColor)
            lock.notifyAll()
        }
    }

    fun resize(newWidth: Int, newHeight: Int) {
        synchronized(lock) {
            width = newWidth
            height = newHeight
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
            if (!initEgl()) return
            renderer.onSurfaceCreated()
            var nextFrameAt = SystemClock.uptimeMillis()
            while (running) {
                var cover: PendingDynamicLightCover?
                var nextWidth: Int
                var nextHeight: Int
                var applySize: Boolean
                synchronized(lock) {
                    cover = pendingCover
                    pendingCover = null
                    nextWidth = width
                    nextHeight = height
                    applySize = sizeChanged
                    sizeChanged = false
                }
                if (applySize) {
                    GLES20.glViewport(0, 0, nextWidth.coerceAtLeast(1), nextHeight.coerceAtLeast(1))
                    renderer.onSurfaceChanged(nextWidth.coerceAtLeast(1), nextHeight.coerceAtLeast(1))
                }
                cover?.let { renderer.setCover(it.bitmap, it.fallbackColor) }
                renderer.render()
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                nextFrameAt += FrameIntervalMs
                val sleepMs = nextFrameAt - SystemClock.uptimeMillis()
                if (sleepMs > 1L) {
                    runCatching { sleep(sleepMs) }
                } else {
                    nextFrameAt = SystemClock.uptimeMillis()
                }
            }
        } catch (throwable: Throwable) {
            DiagnosticLog.event("DynamicLightGl", "renderer stopped", throwable)
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
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
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
            intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
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

    companion object {
        private const val FrameIntervalMs = 33L
        private const val EGL_OPENGL_ES2_BIT = 4
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    }
}

private class DynamicLightRenderer {
    private var width = 1
    private var height = 1
    private var program = 0
    private var texture = 0
    private var startMs = SystemClock.uptimeMillis()
    private var fallbackColor = 0xff202020.toInt()

    private val quadBuffer: FloatBuffer = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f,
    ).toFloatBuffer()

    fun onSurfaceCreated() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = createProgram(VertexShader, FragmentShader)
        setCover(null, fallbackColor)
        startMs = SystemClock.uptimeMillis()
    }

    fun onSurfaceChanged(newWidth: Int, newHeight: Int) {
        width = newWidth.coerceAtLeast(1)
        height = newHeight.coerceAtLeast(1)
    }

    fun setCover(bitmap: Bitmap?, fallbackColor: Int) {
        this.fallbackColor = fallbackColor
        if (texture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
        texture = createTexture(bitmap, fallbackColor)
    }

    fun render() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uTime"),
            (SystemClock.uptimeMillis() - startMs) / 1000f,
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uResolution"),
            width.toFloat(),
            height.toFloat(),
        )

        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
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

    fun release() {
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        texture = 0
        program = 0
    }

    private fun createTexture(bitmap: Bitmap?, fallbackColor: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        val uploadBitmap = bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            ?.toUploadBitmap()
        if (uploadBitmap != null) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, uploadBitmap, 0)
            if (uploadBitmap !== bitmap) uploadBitmap.recycle()
        } else {
            uploadFallbackColor(fallbackColor)
        }
        return id
    }

    private fun Bitmap.toUploadBitmap(): Bitmap? =
        runCatching {
            val source = if (config == Bitmap.Config.ARGB_8888) {
                this
            } else {
                copy(Bitmap.Config.ARGB_8888, false)
            } ?: return@runCatching null
            if (source.width == TexturePx && source.height == TexturePx) {
                return@runCatching source
            }
            val output = Bitmap.createBitmap(TexturePx, TexturePx, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(
                source,
                null,
                android.graphics.Rect(0, 0, TexturePx, TexturePx),
                BitmapPaint,
            )
            if (source !== this) source.recycle()
            output
        }.getOrNull()

    private fun uploadFallbackColor(color: Int) {
        val buffer = ByteBuffer.allocateDirect(TexturePx * TexturePx * 4)
        repeat(TexturePx * TexturePx) {
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
            TexturePx,
            TexturePx,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            buffer,
        )
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
        check(status[0] == GLES20.GL_TRUE) { "Dynamic light GL program link failed: $log" }
        return nextProgram
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val log = GLES20.glGetShaderInfoLog(shader)
        check(status[0] == GLES20.GL_TRUE) { "Dynamic light GL shader compile failed: $log" }
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

    companion object {
        private const val TexturePx = 8
        private const val FloatBytes = 4
        private const val QuadStrideBytes = 4 * FloatBytes
        private val BitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    }
}

private const val VertexShader = """
attribute vec2 aPosition;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

private const val FragmentShader = """
precision mediump float;
uniform sampler2D uTexture;
uniform vec2 uResolution;
uniform float uTime;
varying vec2 vUv;

float saturate(float x) {
    return clamp(x, 0.0, 1.0);
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec3 paletteColor(float index) {
    return texture2D(uTexture, vec2((index + 0.5) * 0.125, 0.5)).rgb;
}

vec3 movingPaletteColor(float phase) {
    float wrapped = mod(phase, 7.0);
    float low = floor(wrapped);
    float high = mod(low + 1.0, 7.0);
    float blend = fract(wrapped);
    blend = blend * blend * (3.0 - 2.0 * blend);
    return mix(paletteColor(low + 1.0), paletteColor(high + 1.0), blend);
}

float roundBlob(vec2 uv, vec2 center, float radius, float feather, float aspect) {
    vec2 delta = uv - center;
    delta.x *= aspect;
    float dist = length(delta);
    float inner = max(radius * 0.18, radius - feather);
    float outer = radius + feather;
    return 1.0 - smoothstep(inner, outer, dist);
}

vec2 randomTarget(float segment, float seed, vec2 center, vec2 range) {
    vec2 random = vec2(
        hash21(vec2(segment, seed)),
        hash21(vec2(seed, segment + 17.0))
    );
    return center + (random * 2.0 - 1.0) * range;
}

vec2 driftingCenter(float t, float seed, vec2 center, vec2 range, float duration) {
    float segment = floor(t / duration);
    float phase = fract(t / duration);
    phase = phase * phase * (3.0 - 2.0 * phase);
    vec2 fromTarget = randomTarget(segment, seed, center, range);
    vec2 toTarget = randomTarget(segment + 1.0, seed, center, range);
    return mix(fromTarget, toTarget, phase);
}

vec3 paletteAt(vec2 uv, float t) {
    float aspect = uResolution.x / max(uResolution.y, 1.0);
    float n1 = valueNoise(uv * 2.0 + vec2(t * 0.045, -t * 0.034));
    float n2 = valueNoise(uv * 3.4 + vec2(-t * 0.032, t * 0.041));
    vec2 fieldUv = uv + vec2(
        0.060 * sin(uv.y * 6.283 + t * 0.18 + n1 * 2.2),
        0.060 * cos(uv.x * 6.283 - t * 0.16 + n2 * 2.2)
    );

    vec2 center0 = driftingCenter(t, 11.0, vec2(0.34, 0.24), vec2(0.14, 0.12), 7.5);
    vec2 center1 = driftingCenter(t, 23.0, vec2(0.66, 0.74), vec2(0.14, 0.12), 8.2);
    vec2 center2 = driftingCenter(t, 37.0, vec2(0.74, 0.50), vec2(0.11, 0.09), 6.8);
    vec2 center3 = driftingCenter(t, 53.0, vec2(0.42, 0.52), vec2(0.10, 0.11), 9.0);

    float r0 = clamp(0.27 + 0.10 * sin(t * 0.13 + 1.0) + 0.035 * sin(t * 0.30 + 2.4), 0.15, 0.38);
    float r1 = clamp(0.26 + 0.095 * sin(t * 0.12 + 2.1) + 0.035 * cos(t * 0.28 + 0.8), 0.14, 0.36);
    float r2 = clamp(0.12 + 0.055 * sin(t * 0.16 + 4.0) + 0.025 * cos(t * 0.34 + 1.5), 0.05, 0.20);
    float r3 = clamp(0.34 + 0.08 * sin(t * 0.09 + 0.3) + 0.035 * cos(t * 0.21 + 1.9), 0.22, 0.48);
    float w0 = roundBlob(fieldUv, center0, r0, 0.14, aspect);
    float w1 = roundBlob(fieldUv, center1, r1, 0.14, aspect);
    float w2 = roundBlob(fieldUv, center2, r2, 0.10, aspect);
    float w3 = roundBlob(fieldUv, center3, r3, 0.22, aspect);
    float blobTotal = max(w0 + w1 + w2 + w3, 0.0001);
    float o0 = smoothstep(0.28, 0.86, w0 / blobTotal);
    float o1 = smoothstep(0.28, 0.86, w1 / blobTotal);
    float o2 = smoothstep(0.28, 0.86, w2 / blobTotal);
    float o3 = smoothstep(0.20, 0.78, w3 / blobTotal);
    w0 *= mix(0.34, 1.0, o0);
    w1 *= mix(0.34, 1.0, o1);
    w2 *= mix(0.34, 1.0, o2);
    w3 *= mix(0.55, 1.0, o3);

    vec3 base = paletteColor(0.0);
    vec3 c0 = movingPaletteColor(t * 0.10 + center0.x * 2.2 + center0.y * 1.4);
    vec3 c1 = movingPaletteColor(t * 0.09 + center1.x * 1.7 + center1.y * 2.0 + 2.1);
    vec3 c2 = movingPaletteColor(t * 0.12 + center2.x * 2.4 + center2.y * 1.3 + 4.2);
    vec3 c3 = paletteColor(0.0);
    float baseWeight = 0.05;
    vec3 color = (base * baseWeight + c0 * w0 + c1 * w1 + c2 * w2 + c3 * w3) /
        max(baseWeight + w0 + w1 + w2 + w3, 0.0001);

    vec2 p = uv - 0.5;
    p.x *= aspect;
    float radius = length(p);
    float glow = smoothstep(0.88, 0.08, radius);
    color += glow * max(color, vec3(0.08)) * 0.24;
    color *= 0.66 + 0.22 * smoothstep(0.82, 0.08, radius);
    color = pow(max(color, vec3(0.001)), vec3(0.96));
    return color;
}

void main() {
    vec2 uv = vUv;
    vec3 color = paletteAt(uv, uTime * 0.36);
    float vignette = smoothstep(0.92, 0.18, length((uv - 0.5) * vec2(0.82, 1.0)));
    color *= 0.78 + 0.22 * vignette;
    color = mix(color, color * color * 1.22, 0.18);
    gl_FragColor = vec4(color, 1.0);
}
"""
