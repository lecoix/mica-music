package com.mica.music.ui.screens.player.view

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/** Reads real GLES pixels from the production renderer, including the seeded particle field. */
@RunWith(AndroidJUnit4::class)
class ParticleCoverGeometryDeviceTest {
    @Test
    fun squareCoverDoesNotBecomePortraitRectangleForEitherSongIdParity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(display != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 4,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, 1, count, 0,
        ) && count[0] > 0)
        val config = requireNotNull(configs[0])
        val eglContext = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        assertTrue(eglContext != EGL14.EGL_NO_CONTEXT)
        val surface = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, Width, EGL14.EGL_HEIGHT, Height, EGL14.EGL_NONE), 0,
        )
        assertTrue(surface != EGL14.EGL_NO_SURFACE)
        val renderer = ParticleCoverRenderer(context)
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, eglContext))
            GLES20.glViewport(0, 0, Width, Height)
            renderer.onSurfaceCreated()
            renderer.onSurfaceChanged(Width, Height)
            renderer.setPreviewOptions(ParticleCoverThemePreset)
            renderer.setLyricsProgress(0f)
            renderer.setCoverTransform(0f, 0f, CoverSide / Width, CoverSide / Height)
            val output = File(context.getExternalFilesDir(null), "particle-geometry").apply { mkdirs() }
            // These IDs exercise the formerly opposite hash-based scatter directions.
            for (songId in listOf("0", "1")) {
                renderer.setCover(songId, null, Color.WHITE, motionEnabled = false)
                for (progress in listOf(0f, 0.2f, 0.5f, 1f)) {
                    renderer.setPlaybackDisintegrationProgress(progress)
                    renderer.render()
                    val pixels = ByteBuffer.allocateDirect(Width * Height * 4)
                    GLES20.glReadPixels(0, 0, Width, Height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                    assertTrue("GLES pixel read failed", GLES20.glGetError() == GLES20.GL_NO_ERROR)
                    var left = Width
                    var right = -1
                    var bottom = Height
                    var top = -1
                    val colors = IntArray(Width * Height)
                    for (y in 0 until Height) for (x in 0 until Width) {
                        val offset = (y * Width + x) * 4
                        val alpha = pixels.get(offset + 3).toInt() and 255
                        colors[(Height - 1 - y) * Width + x] = Color.argb(
                            alpha, pixels.get(offset).toInt() and 255,
                            pixels.get(offset + 1).toInt() and 255,
                            pixels.get(offset + 2).toInt() and 255,
                        )
                        if (alpha >= 16) {
                            left = minOf(left, x)
                            right = maxOf(right, x)
                            bottom = minOf(bottom, y)
                            top = maxOf(top, y)
                        }
                    }
                    val image = Bitmap.createBitmap(colors, Width, Height, Bitmap.Config.ARGB_8888)
                    try {
                        File(output, "song-$songId-progress-$progress.png").outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        image.recycle()
                    }
                    assertTrue("Particle field did not render", right >= left && top >= bottom)
                    val fieldWidth = right - left + 1
                    val fieldHeight = top - bottom + 1
                    val aspect = fieldHeight.toFloat() / fieldWidth
                    val evidence = "id=$songId hash=${songId.hashCode()} progress=$progress " +
                        "bounds=${fieldWidth}x$fieldHeight aspect=$aspect"
                    Log.i("ParticleGeometryTest", evidence)
                    // Allow the small existing screen-space breathing and point rasterization;
                    // the old X-only reversal produces roughly 20% vertical elongation.
                    assertTrue("Particle envelope changed aspect: $evidence", abs(aspect - 1f) <= 0.08f)
                }
            }
        } finally {
            renderer.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
    }

    private companion object {
        const val Width = 256
        const val Height = 512
        const val CoverSide = 180f
    }
}
