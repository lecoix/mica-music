package com.mica.music.media.usbprototype

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/** Process-local single owner for debug-only kernel-driver reconnect side effects. */
internal object UsbKernelDriverReconnectOwner {
    private const val TAG = "MicaUsbPrototype"
    private const val TARGET_VENDOR_ID = 0x262a
    private const val TARGET_PRODUCT_ID = 0x0001
    private const val AUDIO_CONTROL_INTERFACE_ID = 1
    private const val AUDIO_STREAMING_INTERFACE_ID = 2

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Mica USB kernel reconnect").apply { isDaemon = true }
    }

    fun submit(context: Context, publishState: (String) -> Unit) {
        val appContext = context.applicationContext
        val token = UsbPrototypeGenerationOwner.gate.beginRequest()
        executor.execute {
            UsbPrototypeGenerationOwner.gate.withTransport(token) { lease ->
                reconnectLocked(appContext, token, lease, publishState)
            }
        }
    }

    private fun reconnectLocked(
        context: Context,
        token: UsbPrototypeGenerationGate.Token,
        lease: UsbPrototypeGenerationGate.Lease,
        publishState: (String) -> Unit,
    ) {
        if (!lease.isCurrent()) return
        val manager = context.getSystemService(UsbManager::class.java)
        val target = manager.deviceList.values.firstOrNull {
            it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
        }
        if (!lease.isCurrent()) return
        if (target == null || !manager.hasPermission(target)) {
            if (lease.isCurrent()) {
                publishState("reconnect=unavailable request=${token.value} targetFound=${target != null}")
            }
            return
        }
        if (!lease.isCurrent()) return
        val connection = manager.openDevice(target)
        if (!lease.isCurrent()) {
            connection?.close()
            return
        }
        if (connection == null) {
            publishState("reconnect=open_failed request=${token.value}")
            return
        }

        try {
            val fd = connection.fileDescriptor
            val result = reconnectKernelDriversByInterface(
                isCurrent = lease::isCurrent,
                driversAreBound = {
                    if (!lease.isCurrent()) return@reconnectKernelDriversByInterface false
                    val control = UsbSk02NativePrototype.queryInterfaceDriver(
                        fd,
                        AUDIO_CONTROL_INTERFACE_ID,
                    )
                    if (!lease.isCurrent()) return@reconnectKernelDriversByInterface false
                    val streaming = UsbSk02NativePrototype.queryInterfaceDriver(
                        fd,
                        AUDIO_STREAMING_INTERFACE_ID,
                    )
                    if (!lease.isCurrent()) return@reconnectKernelDriversByInterface false
                    control.contains("driver=snd-usb-audio") &&
                        streaming.contains("driver=snd-usb-audio")
                },
                connectInterface = { interfaceNumber ->
                    if (!lease.isCurrent()) return@reconnectKernelDriversByInterface STALE_ERRNO
                    val startedNanos = SystemClock.elapsedRealtimeNanos()
                    Log.i(
                        TAG,
                        "reconnectConnect=begin request=${token.value} interface=$interfaceNumber",
                    )
                    val errno = UsbSk02NativePrototype.connectKernelDriver(fd, interfaceNumber)
                    if (lease.isCurrent()) {
                        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000
                        Log.i(
                            TAG,
                            "reconnectConnect=end request=${token.value} interface=$interfaceNumber " +
                                "errno=$errno elapsedMs=$elapsedMs",
                        )
                    }
                    errno
                },
                controlInterface = AUDIO_CONTROL_INTERFACE_ID,
                streamingInterface = AUDIO_STREAMING_INTERFACE_ID,
            )
            if (result != null && lease.isCurrent()) {
                publishState(
                    "reconnect=complete request=${token.value} errno=${result.errno} " +
                        "interface=${result.connectedInterface ?: "already_bound"}",
                )
            }
        } finally {
            connection.close()
        }
    }

    private const val STALE_ERRNO = 125
}
