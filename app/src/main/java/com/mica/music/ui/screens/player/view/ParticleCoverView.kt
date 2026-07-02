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
    private var playbackDisintegrationProgress: Float? = null
    private var lyricsProgress: Float = 0f
    private var coverTransform: ParticleCoverTransform = ParticleCoverTransform()
    private var previewOptions: ParticleCoverPreviewOptions = ParticleCoverPreviewOptions()
    private var musicEnergy: Float = 0f
    private var musicBands: ParticleCoverMusicBands = ParticleCoverMusicBands()

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

    fun setPreviewOptions(options: ParticleCoverPreviewOptions) {
        previewOptions = options
        renderThread?.setPreviewOptions(options)
    }

    fun setPlaybackDisintegrationProgress(progress: Float?) {
        playbackDisintegrationProgress = progress
        renderThread?.setPlaybackDisintegrationProgress(progress)
    }

    fun setMusicEnergy(energy: Float) {
        musicEnergy = energy.coerceIn(0f, 1f)
        renderThread?.setMusicEnergy(musicEnergy)
    }

    fun setMusicBands(bands: ParticleCoverMusicBands) {
        musicBands = bands.coerced()
        renderThread?.setMusicBands(musicBands)
    }

    fun setLyricsProgress(progress: Float) {
        lyricsProgress = progress
        renderThread?.setLyricsProgress(progress)
    }

    fun setCoverTransform(centerX: Float, centerY: Float, halfWidth: Float, halfHeight: Float) {
        coverTransform = ParticleCoverTransform(
            centerX = centerX,
            centerY = centerY,
            halfWidth = halfWidth,
            halfHeight = halfHeight,
        )
        renderThread?.setCoverTransform(coverTransform)
    }

    fun release() {
        renderThread?.requestStopAndJoin()
        renderThread = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        DiagnosticLog.event(
            "ParticleCover",
            "surface-available diag=surface size=${width}x$height opaque=$isOpaque",
        )
        release()
        renderThread = ParticleCoverRenderThread(
            appContext = context.applicationContext,
            surfaceTexture = surface,
            initialWidth = width,
            initialHeight = height,
        ).also { thread ->
            thread.start()
            thread.setTuning(tuning)
            thread.setPreviewOptions(previewOptions)
            thread.setPlaybackDisintegrationProgress(playbackDisintegrationProgress)
            thread.setMusicEnergy(musicEnergy)
            thread.setMusicBands(musicBands)
            thread.setLyricsProgress(lyricsProgress)
            thread.setCoverTransform(coverTransform)
            coverId?.let { thread.setCover(it, coverBitmap, fallbackColor, motionEnabled) }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        DiagnosticLog.event("ParticleCover", "surface-size diag=surface size=${width}x$height")
        renderThread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        DiagnosticLog.event("ParticleCover", "surface-destroyed diag=surface")
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

private data class ParticleCoverTransform(
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    val halfWidth: Float = 1f,
    val halfHeight: Float = 1f,
)

private fun ParticleCoverMusicBands.coerced(): ParticleCoverMusicBands =
    ParticleCoverMusicBands(
        bass = bass.coerceIn(0f, 1f),
        mid = mid.coerceIn(0f, 1f),
        treble = treble.coerceIn(0f, 1f),
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
    private var pendingPreviewOptions: ParticleCoverPreviewOptions? = null
    private var pendingPlaybackDisintegrationProgress: Float? = null
    private var playbackProgressChanged = false
    private var pendingMusicEnergy = 0f
    private var musicEnergyChanged = false
    private var pendingMusicBands = ParticleCoverMusicBands()
    private var musicBandsChanged = false
    private var pendingLyricsProgress = 0f
    private var lyricsProgressChanged = false
    private var pendingCoverTransform = ParticleCoverTransform()
    private var coverTransformChanged = false
    private var width = initialWidth
    private var height = initialHeight
    private var sizeChanged = true

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var firstSwapLogged = false
    private var swapFailureLogged = false

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

    fun setPreviewOptions(options: ParticleCoverPreviewOptions) {
        synchronized(lock) {
            pendingPreviewOptions = options
            lock.notifyAll()
        }
    }

    fun setPlaybackDisintegrationProgress(progress: Float?) {
        synchronized(lock) {
            pendingPlaybackDisintegrationProgress = progress
            playbackProgressChanged = true
            lock.notifyAll()
        }
    }

    fun setMusicEnergy(energy: Float) {
        synchronized(lock) {
            pendingMusicEnergy = energy.coerceIn(0f, 1f)
            musicEnergyChanged = true
            lock.notifyAll()
        }
    }

    fun setMusicBands(bands: ParticleCoverMusicBands) {
        synchronized(lock) {
            pendingMusicBands = bands.coerced()
            musicBandsChanged = true
            lock.notifyAll()
        }
    }

    fun setLyricsProgress(progress: Float) {
        synchronized(lock) {
            pendingLyricsProgress = progress.coerceIn(0f, 1f)
            lyricsProgressChanged = true
            lock.notifyAll()
        }
    }

    fun setCoverTransform(transform: ParticleCoverTransform) {
        synchronized(lock) {
            pendingCoverTransform = transform
            coverTransformChanged = true
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
                "ParticleCover",
                "render-thread-start diag=thread initialSize=${width}x$height",
            )
            if (!initEgl()) {
                DiagnosticLog.event("ParticleCover", "render-thread-stop diag=egl-init-failed")
                return
            }
            renderer.onSurfaceCreated()
            while (running) {
                var cover: PendingParticleCover?
                var tuning: ParticleCoverTuning?
                var previewOptions: ParticleCoverPreviewOptions?
                var playbackProgress: Float?
                var applyPlaybackProgress: Boolean
                var musicEnergy: Float
                var applyMusicEnergy: Boolean
                var musicBands: ParticleCoverMusicBands
                var applyMusicBands: Boolean
                var lyricsProgress: Float
                var applyLyricsProgress: Boolean
                var coverTransform: ParticleCoverTransform
                var applyCoverTransform: Boolean
                var nextWidth: Int
                var nextHeight: Int
                var applySize: Boolean
                synchronized(lock) {
                    cover = pendingCover
                    pendingCover = null
                    tuning = pendingTuning
                    pendingTuning = null
                    previewOptions = pendingPreviewOptions
                    pendingPreviewOptions = null
                    playbackProgress = pendingPlaybackDisintegrationProgress
                    applyPlaybackProgress = playbackProgressChanged
                    playbackProgressChanged = false
                    musicEnergy = pendingMusicEnergy
                    applyMusicEnergy = musicEnergyChanged
                    musicEnergyChanged = false
                    musicBands = pendingMusicBands
                    applyMusicBands = musicBandsChanged
                    musicBandsChanged = false
                    lyricsProgress = pendingLyricsProgress
                    applyLyricsProgress = lyricsProgressChanged
                    lyricsProgressChanged = false
                    coverTransform = pendingCoverTransform
                    applyCoverTransform = coverTransformChanged
                    coverTransformChanged = false
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
                previewOptions?.let { renderer.setPreviewOptions(it) }
                if (applyPlaybackProgress) {
                    renderer.setPlaybackDisintegrationProgress(playbackProgress)
                }
                if (applyMusicEnergy) {
                    renderer.setMusicEnergy(musicEnergy)
                }
                if (applyMusicBands) {
                    renderer.setMusicBands(musicBands)
                }
                if (applyLyricsProgress) {
                    renderer.setLyricsProgress(lyricsProgress)
                }
                if (applyCoverTransform) {
                    renderer.setCoverTransform(
                        centerX = coverTransform.centerX,
                        centerY = coverTransform.centerY,
                        halfWidth = coverTransform.halfWidth,
                        halfHeight = coverTransform.halfHeight,
                    )
                }
                renderer.render()
                val swapped = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                if (swapped && !firstSwapLogged) {
                    firstSwapLogged = true
                    DiagnosticLog.event("ParticleCover", "first-swap diag=egl ok=true")
                } else if (!swapped && !swapFailureLogged) {
                    swapFailureLogged = true
                    DiagnosticLog.event(
                        "ParticleCover",
                        "swap-failed diag=egl error=${eglErrorHex()}",
                    )
                }
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
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            DiagnosticLog.event("ParticleCover", "egl-get-display-failed diag=egl error=${eglErrorHex()}")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            DiagnosticLog.event("ParticleCover", "egl-initialize-failed diag=egl error=${eglErrorHex()}")
            return false
        }

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
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)) {
            DiagnosticLog.event("ParticleCover", "egl-choose-config-failed diag=egl error=${eglErrorHex()}")
            return false
        }
        val config = configs[0]
        if (config == null) {
            DiagnosticLog.event(
                "ParticleCover",
                "egl-choose-config-empty diag=egl count=${numConfigs[0]} error=${eglErrorHex()}",
            )
            return false
        }
        eglConfig = config

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            DiagnosticLog.event("ParticleCover", "egl-create-context-failed diag=egl error=${eglErrorHex()}")
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
            DiagnosticLog.event("ParticleCover", "egl-create-window-surface-failed diag=egl error=${eglErrorHex()}")
            return false
        }
        val current = EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        if (!current) {
            DiagnosticLog.event("ParticleCover", "egl-make-current-failed diag=egl error=${eglErrorHex()}")
            return false
        }
        DiagnosticLog.event(
            "ParticleCover",
            "egl-ready diag=egl version=${version[0]}.${version[1]} configs=${numConfigs[0]} alpha=8",
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

    companion object {
        private const val EGL_OPENGL_ES2_BIT = 4
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    }
}
