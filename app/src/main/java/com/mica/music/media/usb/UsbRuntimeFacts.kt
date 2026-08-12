package com.mica.music.media.usb

import java.security.MessageDigest

/** Authoritative non-RT facts captured from one opened Android USB enumeration. */
internal data class UsbRuntimeDescriptorFacts(
    val identity: UsbAudioDeviceIdentity,
    val runtimeHandle: UsbAudioRuntimeHandle,
    val descriptorSet: UsbRawAudioDescriptorSet,
)

internal enum class UsbRuntimeFactRejectionCode {
    RAW_DESCRIPTORS_UNAVAILABLE,
    BUS_SPEED_UNAVAILABLE,
    DEVICE_IDENTITY_MISMATCH,
}

internal data class UsbRuntimeFactRejection(
    val code: UsbRuntimeFactRejectionCode,
    val detail: String,
)

internal sealed interface UsbRuntimeFactsResult {
    data class Ready(val facts: UsbRuntimeDescriptorFacts) : UsbRuntimeFactsResult
    data class Rejected(val rejection: UsbRuntimeFactRejection) : UsbRuntimeFactsResult
}

/** Maps the Linux usb_device_speed value returned by USBDEVFS_GET_SPEED. */
internal object UsbKernelBusSpeedMapper {
    fun map(kernelSpeed: Int): UsbBusSpeed? = when (kernelSpeed) {
        USB_SPEED_FULL -> UsbBusSpeed.FULL
        USB_SPEED_HIGH -> UsbBusSpeed.HIGH
        USB_SPEED_SUPER, USB_SPEED_SUPER_PLUS -> UsbBusSpeed.SUPER
        else -> null
    }

    private const val USB_SPEED_FULL = 2
    private const val USB_SPEED_HIGH = 3
    private const val USB_SPEED_SUPER = 5
    private const val USB_SPEED_SUPER_PLUS = 6
}

/** Pure assembler: no Android enumeration id is allowed to become stable identity evidence. */
internal object UsbRuntimeDescriptorFactsAssembler {
    fun assemble(
        runtimeVendorId: Int,
        runtimeProductId: Int,
        runtimeDeviceId: Int,
        rawDescriptors: ByteArray,
        busSpeed: UsbBusSpeed,
        serialNumber: String?,
    ): UsbRuntimeFactsResult {
        if (rawDescriptors.isEmpty()) {
            return rejected(
                UsbRuntimeFactRejectionCode.RAW_DESCRIPTORS_UNAVAILABLE,
                "opened USB connection returned no raw descriptors",
            )
        }
        if (busSpeed == UsbBusSpeed.UNKNOWN) {
            return rejected(
                UsbRuntimeFactRejectionCode.BUS_SPEED_UNAVAILABLE,
                "authoritative USB bus speed is unavailable",
            )
        }
        val descriptor = StandardUsbDeviceDescriptorParser.parse(rawDescriptors)
            ?: return rejected(
                UsbRuntimeFactRejectionCode.RAW_DESCRIPTORS_UNAVAILABLE,
                "standard USB device descriptor is missing or malformed",
            )
        if (descriptor.vendorId != runtimeVendorId || descriptor.productId != runtimeProductId) {
            return rejected(
                UsbRuntimeFactRejectionCode.DEVICE_IDENTITY_MISMATCH,
                "runtime VID/PID=${runtimeVendorId.hex4()}:${runtimeProductId.hex4()} " +
                    "raw=${descriptor.vendorId.hex4()}:${descriptor.productId.hex4()}",
            )
        }
        val stableSerial = serialNumber?.trim()?.takeIf { it.isNotEmpty() }
        val identity = UsbAudioDeviceIdentity(
            vendorId = descriptor.vendorId,
            productId = descriptor.productId,
            descriptorFingerprint = "sha256:${rawDescriptors.sha256Hex()}",
            serialNumber = stableSerial,
            topologyHint = null,
            bcdDevice = descriptor.bcdDevice,
        )
        return UsbRuntimeFactsResult.Ready(
            UsbRuntimeDescriptorFacts(
                identity = identity,
                runtimeHandle = UsbAudioRuntimeHandle(runtimeDeviceId),
                descriptorSet = UsbRawAudioDescriptorSet(
                    bytes = rawDescriptors.copyOf(),
                    busSpeed = busSpeed,
                ),
            ),
        )
    }

    private fun rejected(code: UsbRuntimeFactRejectionCode, detail: String) =
        UsbRuntimeFactsResult.Rejected(UsbRuntimeFactRejection(code, detail))

    private fun Int.hex4(): String = toString(16).padStart(4, '0')

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
