package com.mica.music.media.usbprototype

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/** Debug-only ADB entrypoint for the bounded SK02 DoP-idle staircase. */
class UsbDoPIdleProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            action(context) -> begin(context)
            permissionAction(context) -> {
                val device = intent.usbDeviceExtra()
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (!granted || device == null) {
                    event("dopIdleProbe=result status=FAIL stage=permission detail=denied")
                } else {
                    launch(context.applicationContext, device)
                }
            }
        }
    }

    private fun begin(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java)
        val target = manager.deviceList.values
            .filter { it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID }
            .singleOrNull()
        if (target == null) {
            event("dopIdleProbe=result status=FAIL stage=target detail=expected_single_sk02")
            return
        }
        if (manager.hasPermission(target)) {
            launch(context.applicationContext, target)
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            context,
            19,
            Intent(context, UsbDoPIdleProbeReceiver::class.java).setAction(permissionAction(context)),
            flags,
        )
        event(
            "dopIdleProbe=result status=USER_ACTION_REQUIRED stage=permission " +
                "deviceId=${target.deviceId} vendorId=${target.vendorId} productId=${target.productId}",
        )
        manager.requestPermission(target, pending)
    }

    private fun launch(context: Context, device: UsbDevice) {
        val pending = goAsync()
        Thread(
            {
                try {
                    UsbDoPIdleProbeRunner.run(context, device, ::event)
                } catch (error: Throwable) {
                    event(
                        "dopIdleProbe=result status=FAIL stage=exception " +
                            "type=${error.javaClass.simpleName} detail=${sanitize(error.message)}",
                    )
                    Log.e(TAG, "DoP idle probe failed", error)
                } finally {
                    pending.finish()
                }
            },
            "MicaUsbDoPIdleProbe",
        ).start()
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    companion object {
        private const val TAG = "MicaUsbDoPIdle"
        private const val TARGET_VENDOR_ID = 0x262a
        private const val TARGET_PRODUCT_ID = 0x0001

        fun action(context: Context): String = "${context.packageName}.debug.USB_DOP_IDLE_PROBE"
        private fun permissionAction(context: Context): String =
            "${context.packageName}.debug.USB_DOP_IDLE_PERMISSION"

        private fun event(message: String) = Log.i(TAG, message)
        private fun sanitize(value: String?): String =
            value.orEmpty().replace('\n', ' ').replace('\r', ' ').take(500)
    }
}
