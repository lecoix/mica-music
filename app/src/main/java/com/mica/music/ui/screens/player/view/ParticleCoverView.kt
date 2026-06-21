package com.mica.music.ui.screens.player.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.util.DiagnosticLog

internal class ParticleCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var renderThread: ParticleCoverRenderThread? = null
    private var coverId: String? = null
    private var coverBitmap: Bitmap? = null
    private var fallbackColor: Int = 0xff202020.toInt()
    private var motionEnabled: Boolean = true
    private var tuning: ParticleCoverTuning = ParticleCoverTuning()

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setCover(songId: String, bitmap: Bitmap?) {
        coverId = songId
        coverBitmap = bitmap
        renderThread?.setCover(songId, bitmap, fallbackColor, motionEnabled)
    }

    fun setFallbackColor(color: Int) {
        fallbackColor = color
        coverId?.let { renderThread?.setCover(it, coverBitmap, fallbackColor, motionEnabled) }
    }

    fun setMotionEnabled(enabled: Boolean) {
        motionEnabled = enabled
        coverId?.let { renderThread?.setCover(it, coverBitmap, fallbackColor, motionEnabled) }
    }

    fun setTuning(next: ParticleCoverTuning) {
        tuning = next
        renderThread?.setTuning(next)
    }

    fun release() {
        renderThread?.requestStopAndJoin()
        renderThread = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        release()
        renderThread = ParticleCoverRenderThread(
            appContext = context.applicationContext,
            surfaceTexture = surface,
            initialWidth = width,
            initialHeight = height,
        ).also { thread ->
            thread.start()
            thread.setTuning(tuning)
            coverId?.let { thread.setCover(it, coverBitmap, fallbackColor, motionEnabled) }
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

private data class PendingParticleCover(
    val songId: String,
    val bitmap: Bitmap?,
    val fallbackColor: Int,
    val motionEnabled: Boolean,
)

private class ParticleCoverRenderThread(
    private val appContext: Context,
    surfaceTexture: SurfaceTexture,
    initialWidth: Int,
    initialHeight: Int,
) : Thread("mica-particle-cover-gl") {

    private val surface = Surface(surfaceTexture)
    private val lock = Object()

    @Volatile
    private var running = true
    private var pendingCover: PendingParticleCover? = null
    private var pendingTuning: ParticleCoverTuning? = null
    private var width = initialWidth
    private var height = initialHeight
    private var sizeChanged = true

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private val renderer = ParticleCoverRenderer(appContext)

    fun setCover(songId: String, bitmap: Bitmap?, fallbackColor: Int, motionEnabled: Boolean) {
        synchronized(lock) {
            pendingCover = PendingParticleCover(songId, bitmap, fallbackColor, motionEnabled)
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

    fun setTuning(tuning: ParticleCoverTuning) {
        synchronized(lock) {
            pendingTuning = tuning
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
            while (running) {
                var cover: PendingParticleCover?
                var tuning: ParticleCoverTuning?
                var nextWidth: Int
                var nextHeight: Int
                var applySize: Boolean
                synchronized(lock) {
                    cover = pendingCover
                    pendingCover = null
                    tuning = pendingTuning
                    pendingTuning = null
                    nextWidth = width
                    nextHeight = height
                    applySize = sizeChanged
                    sizeChanged = false
                }
                if (applySize) {
                    GLES20.glViewport(0, 0, nextWidth.coerceAtLeast(1), nextHeight.coerceAtLeast(1))
                    renderer.onSurfaceChanged(nextWidth.coerceAtLeast(1), nextHeight.coerceAtLeast(1))
                }
                cover?.let {
                    renderer.setCover(
                        songId = it.songId,
                        bitmap = it.bitmap,
                        fallbackColor = it.fallbackColor,
                        motionEnabled = it.motionEnabled,
                    )
                }
                tuning?.let { renderer.setTuning(it) }
                renderer.render()
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                sleepFrame(renderer.isAnimating())
            }
        } catch (throwable: Throwable) {
            DiagnosticLog.event("ParticleCover", "renderer stopped", throwable)
        } finally {
            renderer.release()
            releaseEgl()
            surface.release()
        }
    }

    private fun sleepFrame(animating: Boolean) {
        runCatching {
            sleep(if (animating) 16L else 33L)
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
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)) return false
        val config = configs[0] ?: return false
        eglConfig = config

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
        private const val EGL_OPENGL_ES2_BIT = 4
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    }
}
