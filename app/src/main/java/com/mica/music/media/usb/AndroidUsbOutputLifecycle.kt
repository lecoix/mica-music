package com.mica.music.media.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.mica.music.util.DiagnosticLog

/** Read-only P2 repository. It deliberately recognises only the proven SK02 contract. */
internal class AndroidUsbAudioDeviceRepository(context: Context) : UsbAudioDeviceRepository {
    private val manager = context.applicationContext.getSystemService(UsbManager::class.java)

    override fun snapshot(): List<UsbAudioDeviceSnapshot> =
        manager.deviceList.values
            .filter(::isSk02)
            .sortedBy(UsbDevice::getDeviceId)
            .map { device ->
                UsbAudioDeviceSnapshot(
                    identity = Sk02UsbContract.identity,
                    runtimeHandle = UsbAudioRuntimeHandle(device.deviceId),
                    permission = if (manager.hasPermission(device)) {
                        UsbPermissionState.GRANTED
                    } else {
                        UsbPermissionState.UNKNOWN
                    },
                )
            }
}

/**
 * Android side-effect adapter for one explicit developer-beta permission request.
 * Calling this does not enable USB output or rebuild playback; that policy remains a later P2 seam.
 */
internal object UsbOutputDeviceLifecycle {
    fun requestPermission(context: Context, request: UsbOutputRequest): UsbOutputRequestToken {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(UsbManager::class.java)
        val matches = manager.deviceList.values.filter {
            it.vendorId == request.device.vendorId && it.productId == request.device.productId
        }
        check(matches.size == 1) {
            "Expected exactly one requested USB device; found ${matches.size}"
        }
        val device = matches.single()
        val runtimeHandle = UsbAudioRuntimeHandle(device.deviceId)
        val token = UsbOutputRuntime.owner.beginPermissionRequest(request, runtimeHandle)
        if (manager.hasPermission(device)) {
            check(
                UsbOutputRuntime.owner.completePermissionRequest(
                    token,
                    runtimeHandle,
                    granted = true,
                ),
            )
            return token
        }

        val permissionIntent = PendingIntent.getBroadcast(
            appContext,
            token.uniqueRequestCode(),
            Intent(appContext, UsbOutputLifecycleReceiver::class.java)
                .setAction(permissionAction(appContext))
                .putExtra(EXTRA_GENERATION, token.value)
                .putExtra(EXTRA_RUNTIME_DEVICE_ID, runtimeHandle.runtimeDeviceId),
            PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
        )
        val dispatched = UsbOutputRuntime.owner.withTransport(token) { lease ->
            lease.io { manager.requestPermission(device, permissionIntent) }
            true
        } ?: false
        check(dispatched) { "USB permission request was superseded before dispatch" }
        return token
    }

    private fun UsbOutputRequestToken.uniqueRequestCode(): Int =
        (value xor (value ushr Int.SIZE_BITS)).toInt()
}

internal sealed interface UsbOutputLifecycleEvent {
    data class Attached(val runtimeHandle: UsbAudioRuntimeHandle, val generation: Long) : UsbOutputLifecycleEvent
    data class Detached(val runtimeHandle: UsbAudioRuntimeHandle, val generation: Long) : UsbOutputLifecycleEvent
    data class Permission(
        val runtimeHandle: UsbAudioRuntimeHandle,
        val generation: Long,
        val granted: Boolean,
    ) : UsbOutputLifecycleEvent
}

/** Process-local event seam; playback policy remains owned by the media service. */
internal object UsbOutputLifecycleRuntime {
    private val listener = java.util.concurrent.atomic.AtomicReference<((UsbOutputLifecycleEvent) -> Unit)?>(null)
    private val recovery = UsbLifecycleRecoveryCoordinator()

    fun install(onEvent: (UsbOutputLifecycleEvent) -> Unit) {
        listener.set(onEvent)
    }

    fun clear() {
        listener.set(null)
    }

    fun dispatch(event: UsbOutputLifecycleEvent) {
        listener.get()?.invoke(event)
    }

    fun beginDetach(runtimeHandle: UsbAudioRuntimeHandle): UsbLifecycleToken = recovery.beginDetach(runtimeHandle)

    fun beginAttach(runtimeHandle: UsbAudioRuntimeHandle): UsbLifecycleToken = recovery.beginAttach(runtimeHandle)

    fun bindPermissionRequest(token: UsbLifecycleToken, permissionGeneration: Long): Boolean =
        recovery.bindPermissionRequest(token, permissionGeneration)

    fun rejectPermission(runtimeHandle: UsbAudioRuntimeHandle, permissionGeneration: Long): Boolean =
        recovery.rejectPermission(runtimeHandle, permissionGeneration)

    fun rememberInterruptedPlayback(
        token: UsbLifecycleToken,
        resumePlaybackRequested: Boolean,
        reason: String,
    ): Boolean = recovery.rememberInterruptedPlayback(token, resumePlaybackRequested, reason)

    fun hasInterruptedPlayback(token: UsbLifecycleToken): Boolean =
        recovery.hasInterruptedPlayback(token)

    fun isCurrent(token: UsbLifecycleToken): Boolean = recovery.isCurrent(token)

    fun clearIfCurrent(token: UsbLifecycleToken): Boolean = recovery.clearIfCurrent(token)

    fun publishIfCurrent(token: UsbLifecycleToken, effect: () -> Unit): Boolean =
        recovery.publishIfCurrent(token, effect)

    fun clearRecovery() = recovery.clear()

    fun hasInterruptedUsbIntent(): Boolean = recovery.hasInterruptedUsbIntent

    fun publishGrantedPermission(
        runtimeHandle: UsbAudioRuntimeHandle,
        permissionGeneration: Long,
        effect: (UsbInterruptedPlaybackIntent) -> Boolean,
    ): Boolean = recovery.publishGrantedPermission(runtimeHandle, permissionGeneration, effect)
}

/** Manifest receiver for Android permission results and physical detach events. */
class UsbOutputLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            permissionAction(context) -> handlePermissionResult(intent)
            UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDetach(intent)
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleAttach(intent)
        }
    }

    private fun handlePermissionResult(intent: Intent) {
        val generation = intent.getLongExtra(EXTRA_GENERATION, INVALID_GENERATION)
        val runtimeDeviceId = intent.getIntExtra(EXTRA_RUNTIME_DEVICE_ID, INVALID_RUNTIME_DEVICE_ID)
        val device = intent.usbDeviceExtra()
        val runtimeHandle = UsbAudioRuntimeHandle(runtimeDeviceId)
        val accepted = generation != INVALID_GENERATION &&
            runtimeDeviceId != INVALID_RUNTIME_DEVICE_ID &&
            device?.deviceId == runtimeDeviceId &&
            UsbOutputRuntime.owner.completePermissionRequest(
                UsbOutputRequestToken(generation),
                runtimeHandle,
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
            )
        if (accepted) {
            UsbOutputLifecycleRuntime.dispatch(
                UsbOutputLifecycleEvent.Permission(runtimeHandle, generation, intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)),
            )
        }
        DiagnosticLog.event(
            "UsbOutputLifecycle",
            "permissionResult accepted=$accepted generation=$generation " +
                "runtimeDeviceId=$runtimeDeviceId",
        )
    }

    private fun handleDetach(intent: Intent) {
        val device = intent.usbDeviceExtra() ?: return
        if (!isSk02(device)) return
        val runtimeHandle = UsbAudioRuntimeHandle(device.deviceId)
        val disposition = UsbOutputRuntime.owner.deviceDetached(runtimeHandle)
        if (disposition == UsbDeviceDetachDisposition.STALE_RUNTIME) {
            DiagnosticLog.event(
                "UsbOutputLifecycle",
                "detached ignored=stale-runtime runtimeDeviceId=${device.deviceId}",
            )
            return
        }
        val lifecycleToken = UsbOutputLifecycleRuntime.beginDetach(runtimeHandle)
        UsbOutputLifecycleRuntime.dispatch(
            UsbOutputLifecycleEvent.Detached(runtimeHandle, lifecycleToken.generation),
        )
        DiagnosticLog.event(
            "UsbOutputLifecycle",
            "detached disposition=$disposition runtimeDeviceId=${device.deviceId}",
        )
    }

    private fun handleAttach(intent: Intent) {
        val device = intent.usbDeviceExtra() ?: return
        if (!isSk02(device)) return
        val runtimeHandle = UsbAudioRuntimeHandle(device.deviceId)
        val lifecycleToken = UsbOutputLifecycleRuntime.beginAttach(runtimeHandle)
        UsbOutputLifecycleRuntime.dispatch(
            UsbOutputLifecycleEvent.Attached(runtimeHandle, lifecycleToken.generation),
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}

private fun isSk02(device: UsbDevice): Boolean =
    device.vendorId == Sk02UsbContract.identity.vendorId &&
        device.productId == Sk02UsbContract.identity.productId

private fun permissionAction(context: Context): String = "${context.packageName}.usb.PERMISSION"

private const val EXTRA_GENERATION = "usbOutputGeneration"
private const val EXTRA_RUNTIME_DEVICE_ID = "usbRuntimeDeviceId"
private const val INVALID_GENERATION = -1L
private const val INVALID_RUNTIME_DEVICE_ID = -1
