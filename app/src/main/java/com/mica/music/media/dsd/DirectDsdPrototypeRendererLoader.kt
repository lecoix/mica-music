package com.mica.music.media.dsd

import android.content.Context
import androidx.media3.exoplayer.Renderer
import com.mica.music.BuildConfig

internal object DirectDsdPrototypeRendererLoader {
    private const val FACTORY_CLASS =
        "com.mica.music.media.usbprototype.UsbDirectDsdPrototypeRendererFactory"

    fun create(
        context: Context,
        transitionCoordinator: DirectDsdTrackTransitionCoordinator,
    ): Renderer? {
        if (!BuildConfig.DEBUG) return null
        return runCatching {
            val factory = Class.forName(FACTORY_CLASS)
            val method = factory.getDeclaredMethod(
                "create",
                Context::class.java,
                DirectDsdTrackTransitionCoordinator::class.java,
            )
            method.invoke(null, context, transitionCoordinator) as? Renderer
        }.getOrElse { error ->
            throw IllegalStateException("Debug Direct DSD renderer factory is unavailable", error)
        }
    }
}
