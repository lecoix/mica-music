package com.mica.music.media.usbhybrid

import android.hardware.usb.UsbDevice

object AndroidUsbIdentityProbe {
    fun candidate(device: UsbDevice): UsbDeviceCandidate {
        val topology = buildList {
            for (configurationIndex in 0 until device.configurationCount) {
                val configuration = device.getConfiguration(configurationIndex)
                for (interfaceIndex in 0 until configuration.interfaceCount) {
                    val usbInterface = configuration.getInterface(interfaceIndex)
                    if (usbInterface.endpointCount == 0) {
                        add(
                            "c${configuration.id}:i${usbInterface.id}:a${usbInterface.alternateSetting}:" +
                                "class${usbInterface.interfaceClass}:none",
                        )
                    }
                    for (endpointIndex in 0 until usbInterface.endpointCount) {
                        val endpoint = usbInterface.getEndpoint(endpointIndex)
                        add(
                            "c${configuration.id}:i${usbInterface.id}:a${usbInterface.alternateSetting}:" +
                                "class${usbInterface.interfaceClass}:e${endpoint.address}:" +
                                "t${endpoint.type}:d${endpoint.direction}:m${endpoint.maxPacketSize}:" +
                                "n${endpoint.interval}",
                        )
                    }
                }
            }
        }
        val model = UsbDescriptorModel(
            vendorId = device.vendorId,
            productId = device.productId,
            version = device.version,
            configurations = topology,
        )
        return UsbDeviceCandidate(
            identity = UsbStableIdentity(
                vendorId = device.vendorId,
                productId = device.productId,
                bcdDevice = parseBcdVersion(device.version),
                descriptorDigest = UsbStableIdentityDigest.sha256(model),
            ),
            runtimeHandle = UsbRuntimeHandle(device.deviceId, device.deviceName),
        )
    }

    private fun parseBcdVersion(version: String?): Int? {
        val parts = version?.split('.') ?: return null
        if (parts.size != 2) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].padEnd(2, '0').take(2).toIntOrNull() ?: return null
        if (major !in 0..99 || minor !in 0..99) return null
        return ((major / 10) shl 12) or ((major % 10) shl 8) or
            ((minor / 10) shl 4) or (minor % 10)
    }
}
