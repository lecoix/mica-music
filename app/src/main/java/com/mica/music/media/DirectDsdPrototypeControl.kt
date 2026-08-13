package com.mica.music.media

import android.content.Context
import androidx.media3.exoplayer.Renderer
import com.mica.music.BuildConfig

internal object DirectDsdPrototypeControl {
    private const val CONTROL_CLASS =
        "com.mica.music.media.usbprototype.UsbDirectDsdPrototypeControl"

    fun setEnabled(context: Context, enabled: Boolean) {
        check(BuildConfig.DEBUG) { "Direct DSD prototype control is debug-only" }
        runCatching {
            val control = Class.forName(CONTROL_CLASS)
            val method = control.getDeclaredMethod(
                "setEnabled",
                Context::class.java,
                Boolean::class.javaPrimitiveType,
            )
            method.invoke(null, context, enabled)
        }.getOrElse { error ->
            throw IllegalStateException("Debug Direct DSD prototype control is unavailable", error)
        }
    }

    fun isEnabled(context: Context): Boolean {
        check(BuildConfig.DEBUG) { "Direct DSD prototype control is debug-only" }
        return runCatching {
            val control = Class.forName(CONTROL_CLASS)
            val method = control.getDeclaredMethod("isEnabledForMain")
            method.invoke(null) as Boolean
        }.getOrElse { error ->
            throw IllegalStateException("Debug Direct DSD prototype control is unavailable", error)
        }
    }
}
