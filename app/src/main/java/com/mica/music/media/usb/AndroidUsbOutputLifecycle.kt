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

/** Manifest receiver for Android permission results and physical detach events. */
class UsbOutputLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            permissionAction(context) -> handlePermissionResult(intent)
            UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDetach(intent)
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
        DiagnosticLog.event(
            "UsbOutputLifecycle",
            "permissionResult accepted=$accepted generation=$generation " +
                "runtimeDeviceId=$runtimeDeviceId",
        )
    }

    private fun handleDetach(intent: Intent) {
        val device = intent.usbDeviceExtra() ?: return
        val released = UsbOutputRuntime.owner.deviceDetached(
            UsbAudioRuntimeHandle(device.deviceId),
        )
        DiagnosticLog.event(
            "UsbOutputLifecycle",
            "detached released=$released runtimeDeviceId=${device.deviceId}",
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
