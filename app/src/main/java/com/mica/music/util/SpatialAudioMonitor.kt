package com.mica.music.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * Read-only view of the platform spatializer.
 *
 * This monitor deliberately does not enable spatial audio and does not alter the Media3 audio
 * path. [canBeSpatialized] is a conservative probe for 5.1 PCM at 48 kHz; it is not a claim that
 * every track (especially stereo) will be spatialized.
 */
data class SpatialAudioState(
    val apiSupported: Boolean,
    val supported: Boolean,
    val available: Boolean,
    val enabled: Boolean,
    val canBeSpatialized: Boolean?,
    val headTrackerAvailable: Boolean,
) {
    companion object {
        fun unsupported(): SpatialAudioState = SpatialAudioState(
            apiSupported = false,
            supported = false,
            available = false,
            enabled = false,
            canBeSpatialized = null,
            headTrackerAvailable = false,
        )
    }

    fun summary(): String {
        if (!apiSupported) return "Unsupported (requires Android 12L / API 32+)"
        if (!supported) return "Device does not support immersive spatial audio"
        return buildString {
            append(if (enabled) "Enabled" else "Supported, not enabled")
            append("; output=")
            append(if (available) "available" else "unavailable")
            append("; 5.1 PCM=")
            append(canBeSpatialized?.let { if (it) "spatializable" else "not spatializable" } ?: "unknown")
            append("; head tracking=")
            append(if (headTrackerAvailable) "available" else "unavailable")
        }
    }

    fun toLogMessage(): String = buildString {
        append("apiSupported=").append(apiSupported)
        append("; supported=").append(supported)
        append("; available=").append(available)
        append("; enabled=").append(enabled)
        append("; canBeSpatialized5_1Pcm=")
            .append(canBeSpatialized?.toString() ?: "unknown")
        append("; headTrackerAvailable=").append(headTrackerAvailable)
    }
}

object SpatialAudioMonitor {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(SpatialAudioState.unsupported())

    @Volatile
    private var audioManager: AudioManager? = null

    @Volatile
    private var api32Hooks: Api32SpatialAudioHooks? = null

    val state: StateFlow<SpatialAudioState> = mutableState.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refresh()
        }
    }

    fun install(context: Context) {
        synchronized(lock) {
            if (audioManager != null) return
            val manager = context.applicationContext
                .getSystemService(AudioManager::class.java) ?: return
            audioManager = manager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
                api32Hooks = Api32SpatialAudioHooks(manager, ::refresh).also { it.register() }
            }
            manager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        }
        refresh()
    }

    /** Returns the current state even when [install] has not been called yet. */
    fun snapshot(context: Context): SpatialAudioState {
        val manager = context.applicationContext.getSystemService(AudioManager::class.java)
            ?: return SpatialAudioState.unsupported()
        return readState(manager)
    }

    private fun refresh() {
        val manager = audioManager ?: return
        mutableState.value = readState(manager)
    }

    private fun readState(manager: AudioManager): SpatialAudioState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
            return SpatialAudioState.unsupported()
        }
        return api32Hooks?.readState() ?: Api32SpatialAudioHooks.readState(manager)
    }
}

/**
 * Kept in a separately loaded API 32 holder. Android 12 (API 31) must never initialize this class:
 * its listener interfaces and AudioManager#getSpatializer do not exist there.
 */
@RequiresApi(Build.VERSION_CODES.S_V2)
@Suppress("DEPRECATION", "NewApi")
private class Api32SpatialAudioHooks(
    private val manager: AudioManager,
    private val onChanged: () -> Unit,
) {
    private val mainExecutor = Executor { command ->
        Handler(Looper.getMainLooper()).post(command)
    }
    private val spatializer: Spatializer? = runCatching { manager.getSpatializer() }.getOrNull()

    private val spatializerStateListener = object : Spatializer.OnSpatializerStateChangedListener {
        override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) {
            onChanged()
        }

        override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) {
            onChanged()
        }
    }

    private val headTrackerListener = object : Spatializer.OnHeadTrackerAvailableListener {
        override fun onHeadTrackerAvailableChanged(spatializer: Spatializer, available: Boolean) {
            onChanged()
        }
    }

    fun register() {
        spatializer?.let { platformSpatializer ->
            runCatching {
                platformSpatializer.addOnSpatializerStateChangedListener(
                    mainExecutor,
                    spatializerStateListener,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    platformSpatializer.addOnHeadTrackerAvailableListener(
                        mainExecutor,
                        headTrackerListener,
                    )
                }
            }
        }
    }

    fun readState(): SpatialAudioState = readState(manager, spatializer)

    companion object {
        fun readState(manager: AudioManager): SpatialAudioState =
            readState(manager, runCatching { manager.getSpatializer() }.getOrNull())

        private fun readState(
            manager: AudioManager,
            platformSpatializer: Spatializer?,
        ): SpatialAudioState {
            if (platformSpatializer == null) {
                return SpatialAudioState(
                    apiSupported = true,
                    supported = false,
                    available = false,
                    enabled = false,
                    canBeSpatialized = null,
                    headTrackerAvailable = false,
                )
            }
            val supported = runCatching {
                platformSpatializer.immersiveAudioLevel !=
                    Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
            }.getOrDefault(false)
            val available = runCatching { platformSpatializer.isAvailable }.getOrDefault(false)
            val enabled = runCatching { platformSpatializer.isEnabled }.getOrDefault(false)
            val canBeSpatialized = if (supported && available) {
                runCatching {
                    platformSpatializer.canBeSpatialized(probeAudioAttributes, probeAudioFormat)
                }.getOrNull()
            } else {
                null
            }
            val headTrackerAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { platformSpatializer.isHeadTrackerAvailable }.getOrDefault(false)
            } else {
                false
            }
            return SpatialAudioState(
                apiSupported = true,
                supported = supported,
                available = available,
                enabled = enabled,
                canBeSpatialized = canBeSpatialized,
                headTrackerAvailable = headTrackerAvailable,
            )
        }

        private val probeAudioAttributes: AudioAttributes by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        }

        private val probeAudioFormat: AudioFormat by lazy {
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                .build()
        }
    }
}
