package com.mica.music.ui.theme

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RainGlassShaderContractTest {

    @Test
    fun rainGlassShadersCompileAndLinkOnDeviceGles2Driver() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue("Unable to obtain EGL display", display != EGL14.EGL_NO_DISPLAY)

        val version = IntArray(2)
        assertTrue("Unable to initialize EGL", EGL14.eglInitialize(display, version, 0, version, 1))

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val configAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        assertTrue(
            "Unable to choose EGL2 config",
            EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) &&
                count[0] > 0,
        )
        val config = requireNotNull(configs[0])

        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(0x3098, 2, EGL14.EGL_NONE), // EGL_CONTEXT_CLIENT_VERSION
            0,
        )
        assertTrue("Unable to create EGL2 context", context != EGL14.EGL_NO_CONTEXT)

        val surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(
                EGL14.EGL_WIDTH, 4,
                EGL14.EGL_HEIGHT, 4,
                EGL14.EGL_NONE,
            ),
            0,
        )
        assertTrue("Unable to create EGL pbuffer", surface != EGL14.EGL_NO_SURFACE)

        try {
            assertTrue(
                "Unable to make EGL context current",
                EGL14.eglMakeCurrent(display, surface, surface, context),
            )
            assertTrue(
                "Wet trails must cut fog exponentially like Heartfelt, not by a few pixels",
                RainGlassFragmentShader.contains("exp2(-1.7 * clamp(rain.y"),
            )
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, RainGlassVertexShader)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, RainGlassFragmentShader)
            try {
                val program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, vertex)
                GLES20.glAttachShader(program, fragment)
                GLES20.glLinkProgram(program)
                val status = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                assertTrue("Rain Glass program link failed: $log", status[0] == GLES20.GL_TRUE)
            } finally {
                GLES20.glDeleteShader(vertex)
                GLES20.glDeleteShader(fragment)
            }
        } finally {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val log = GLES20.glGetShaderInfoLog(shader)
        assertTrue("Rain Glass shader compile failed: $log", status[0] == GLES20.GL_TRUE)
        return shader
    }
}
