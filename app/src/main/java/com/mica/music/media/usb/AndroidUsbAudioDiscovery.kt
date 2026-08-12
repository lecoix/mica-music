package com.mica.music.media.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

internal object AndroidUsbAudioDiscovery {
    fun discover(manager: UsbManager): UsbPotentialAudioDiscoveryResult =
        UsbPotentialAudioDeviceDiscovery.discover(
            manager.deviceList.values.map { device ->
                UsbAttachedDeviceDiscoveryFacts(
                    runtimeHandle = UsbAudioRuntimeHandle(device.deviceId),
                    vendorId = device.vendorId,
                    productId = device.productId,
                    permission = if (manager.hasPermission(device)) {
                        UsbPermissionState.GRANTED
                    } else {
                        UsbPermissionState.UNKNOWN
                    },
                    hasAudioInterface = device.hasAudioInterface(),
                )
            },
        )

    fun resolve(
        manager: UsbManager,
        candidate: UsbPotentialAudioDevice,
    ): UsbDevice? = manager.deviceList.values.singleOrNull { device ->
        device.deviceId == candidate.runtimeHandle.runtimeDeviceId &&
            device.vendorId == candidate.vendorId &&
            device.productId == candidate.productId &&
            device.hasAudioInterface()
    }

    private fun UsbDevice.hasAudioInterface(): Boolean =
        (0 until interfaceCount).any { index ->
            getInterface(index).interfaceClass == UsbConstants.USB_CLASS_AUDIO
        }
}
