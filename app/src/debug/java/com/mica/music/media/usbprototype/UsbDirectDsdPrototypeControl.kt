package com.mica.music.media.usbprototype

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.Renderer
import com.mica.music.media.dsd.DirectDsdMedia3Renderer
import com.mica.music.media.dsd.DirectDsdSystemMonotonicClock
import com.mica.music.media.dsd.DirectDsdTrackTransitionCoordinator
import com.mica.music.media.dsd.ManualNavigationTransitionBridge
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackAdapter
import java.io.File

object UsbDirectDsdPrototypeControl {
    @Volatile
    private var enabled: Boolean = false

    @JvmStatic
    fun setEnabled(context: Context, value: Boolean) {
        if (value) UsbDirectDsdPrototypeEvidence.reset(context)
        enabled = value
        UsbDirectDsdPrototypeEvidence.record(context, "directDsd=gate enabled=$value")
    }

    internal fun isEnabled(): Boolean = enabled

    @JvmStatic
    fun isEnabledForMain(): Boolean = enabled
}

object UsbDirectDsdPrototypeRendererFactory {
    @JvmStatic
    fun create(
        context: Context,
        transitionCoordinator: DirectDsdTrackTransitionCoordinator,
        manualNavigationTransitionBridge: ManualNavigationTransitionBridge,
        playbackAdapter: Any?,
    ): Renderer? {
        if (!UsbDirectDsdPrototypeControl.isEnabled()) return null
        val appContext = context.applicationContext
        val publish: (String) -> Unit = { UsbDirectDsdPrototypeEvidence.record(appContext, it) }
        val clock = DirectDsdSystemMonotonicClock
        publish("directDsd=renderer-created")
        return DirectDsdMedia3Renderer(
            sessionFactory = UsbDirectDsdTransportSessionFactory(appContext, publish, clock),
            milestone = publish,
            monotonicClock = clock,
            transitionCoordinator = transitionCoordinator,
            manualNavigationTransitionBridge = manualNavigationTransitionBridge,
        ).also { it.installUsbExclusivePlaybackAdapter(playbackAdapter as? UsbExclusivePlaybackAdapter) }
    }
}

internal object UsbDirectDsdPrototypeEvidence {
    private const val TAG = "MicaDirectDsd"
    private const val FILE_NAME = "direct-dsd-prototype-evidence.txt"

    @Synchronized
    fun reset(context: Context) {
        evidenceFile(context).apply {
            parentFile?.mkdirs()
            writeText("directDsd=evidence-reset elapsedMs=${SystemClock.elapsedRealtime()}\n")
        }
    }

    @Synchronized
    fun record(context: Context, message: String) {
        Log.i(TAG, message)
        evidenceFile(context).apply {
            parentFile?.mkdirs()
            appendText("${SystemClock.elapsedRealtime()} $message\n")
        }
    }

    private fun evidenceFile(context: Context): File =
        File(context.filesDir, "debug-usb/$FILE_NAME")
}
