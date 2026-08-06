package com.mica.music.media

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Handler
import com.mica.music.util.BluetoothAudioDiagnostics
import com.mica.music.util.DiagnosticLog

/**
 * Observes media output route changes and notifies the playback owner to flush/reconfigure the sink.
 *
 * Uses a short debounce so BT connect bursts (A2DP + SCO + LE) collapse into one flush.
 */
internal class PlaybackRouteMonitor(
    private val context: Context,
    private val mainHandler: Handler,
    private val onRouteChanged: (previous: AudioRouteSnapshot, current: AudioRouteSnapshot, event: String) -> Unit,
) {
    private val lock = Any()
    private var generation = 0
    private var installed = false
    private var currentRoute: AudioRouteSnapshot? = null
    private var pendingCheck: Runnable? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.isEmpty()) return
            scheduleRouteCheck("added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.isEmpty()) return
            scheduleRouteCheck("removed")
        }
    }

    fun install() {
        synchronized(lock) {
            if (installed) return
            val manager = context.getSystemService(android.media.AudioManager::class.java) ?: return
            currentRoute = AudioOutputCapabilities.route(context)
            manager.registerAudioDeviceCallback(deviceCallback, mainHandler)
            installed = true
            generation++
        }
        BluetoothAudioDiagnostics.logPlaybackRoute(
            reason = "monitor-install",
            extra = "route=${currentRoute?.deviceName}",
        )
    }

    fun release() {
        synchronized(lock) {
            if (!installed) return
            generation++
            pendingCheck?.let { mainHandler.removeCallbacks(it) }
            pendingCheck = null
            val manager = context.getSystemService(android.media.AudioManager::class.java)
            runCatching { manager?.unregisterAudioDeviceCallback(deviceCallback) }
            installed = false
            currentRoute = null
        }
    }

    private fun scheduleRouteCheck(event: String) {
        val gen = synchronized(lock) {
            if (!installed) return
            pendingCheck?.let { mainHandler.removeCallbacks(it) }
            generation
        }
        val check = Runnable { evaluateRouteChange(gen, event) }
        synchronized(lock) {
            if (!installed) return
            pendingCheck = check
        }
        mainHandler.postDelayed(check, ROUTE_DEBOUNCE_MS)
    }

    private fun evaluateRouteChange(generationAtSchedule: Int, event: String) {
        synchronized(lock) {
            if (!installed || generationAtSchedule != generation) return
            pendingCheck = null
        }
        val nextRoute = AudioOutputCapabilities.route(context)
        val previousRoute = synchronized(lock) {
            if (!installed || generationAtSchedule != generation) return
            val prev = currentRoute ?: run {
                currentRoute = nextRoute
                return
            }
            if (prev == nextRoute) return
            currentRoute = nextRoute
            prev
        }
        DiagnosticLog.event(
            "AudioRoute",
            "route-changed event=$event " +
                "prev=${previousRoute.deviceName}/type=${previousRoute.deviceType} " +
                "next=${nextRoute.deviceName}/type=${nextRoute.deviceType}",
        )
        BluetoothAudioDiagnostics.logPlaybackRoute(
            reason = "route-changed",
            extra = "event=$event prev=${previousRoute.deviceName} next=${nextRoute.deviceName}",
        )
        onRouteChanged(previousRoute, nextRoute, event)
    }

    internal companion object {
        const val ROUTE_DEBOUNCE_MS = 150L
    }
}
