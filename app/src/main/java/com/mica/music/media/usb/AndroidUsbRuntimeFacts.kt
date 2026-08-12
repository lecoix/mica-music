package com.mica.music.media.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/** Narrow JNI query for usbfs facts that Android's Java USB API does not expose. */
internal object UsbRuntimeNativeBridge {
    init {
        System.loadLibrary("usb_sk02_prototype")
    }

    /** Returns Linux usb_device_speed enum value, or a negative errno. */
    external fun queryBusSpeed(fd: Int): Int
}

internal object AndroidUsbRuntimeFactsProvider {
    fun acquire(
        device: UsbDevice,
        connection: UsbDeviceConnection,
    ): UsbRuntimeFactsResult {
        val fd = connection.fileDescriptor
        if (fd < 0) {
            return UsbRuntimeFactsResult.Rejected(
                UsbRuntimeFactRejection(
                    UsbRuntimeFactRejectionCode.BUS_SPEED_UNAVAILABLE,
                    "opened USB connection has no native file descriptor",
                ),
            )
        }
        val raw = connection.rawDescriptors ?: ByteArray(0)
        if (raw.isEmpty()) {
            return UsbRuntimeFactsResult.Rejected(
                UsbRuntimeFactRejection(
                    UsbRuntimeFactRejectionCode.RAW_DESCRIPTORS_UNAVAILABLE,
                    "opened USB connection returned no raw descriptors",
                ),
            )
        }
        val kernelSpeed = UsbRuntimeNativeBridge.queryBusSpeed(fd)
        val busSpeed = UsbKernelBusSpeedMapper.map(kernelSpeed)
            ?: return UsbRuntimeFactsResult.Rejected(
                UsbRuntimeFactRejection(
                    UsbRuntimeFactRejectionCode.BUS_SPEED_UNAVAILABLE,
                    "USBDEVFS_GET_SPEED returned unsupported/unavailable value=$kernelSpeed",
                ),
            )
        val serial = runCatching { connection.serial }.getOrNull()
        return UsbRuntimeDescriptorFactsAssembler.assemble(
            runtimeVendorId = device.vendorId,
            runtimeProductId = device.productId,
            runtimeDeviceId = device.deviceId,
            rawDescriptors = raw,
            busSpeed = busSpeed,
            serialNumber = serial,
        )
    }
}

/** Android implementation of the existing P3 class-control request contract. */
internal class AndroidUsbAudioControlIo(
    private val connection: UsbDeviceConnection,
    private val executeIo: ((() -> Int) -> Int) = { block -> block() },
    private val timeoutMs: Int = DEFAULT_CONTROL_TIMEOUT_MS,
) : UsbAudioControlIo {
    override fun execute(request: UsbControlRequest): UsbControlIoResult {
        val requestType = USB_TYPE_CLASS or when (request.recipient) {
            UsbControlRecipient.INTERFACE -> USB_RECIP_INTERFACE
            UsbControlRecipient.ENDPOINT -> USB_RECIP_ENDPOINT
        } or when (request.direction) {
            UsbControlDirection.IN -> USB_DIR_IN
            UsbControlDirection.OUT -> USB_DIR_OUT
        }
        val isRead = request.direction == UsbControlDirection.IN
        val buffer = if (isRead) ByteArray(request.readLength) else request.payload.copyOf()
        val length = if (isRead) request.readLength else request.payload.size
        if (length < 0) return UsbControlIoResult.Failure("negative USB control length=$length")
        val transferred = runCatching {
            executeIo {
                connection.controlTransfer(
                    requestType,
                    request.request,
                    request.value,
                    request.index,
                    buffer,
                    length,
                    timeoutMs,
                )
            }
        }.getOrElse { error ->
            return UsbControlIoResult.Failure("USB control exception=${error::class.java.simpleName}")
        }
        if (transferred < 0) {
            return UsbControlIoResult.Failure(
                "USB control failed request=0x${request.request.toString(16)} transferred=$transferred",
            )
        }
        val data = if (isRead) buffer.copyOf(transferred.coerceAtMost(buffer.size)) else ByteArray(0)
        return UsbControlIoResult.Success(transferredBytes = transferred, data = data)
    }

    private companion object {
        const val USB_DIR_OUT = 0x00
        const val USB_DIR_IN = 0x80
        const val USB_TYPE_CLASS = 0x20
        const val USB_RECIP_INTERFACE = 0x01
        const val USB_RECIP_ENDPOINT = 0x02
        const val DEFAULT_CONTROL_TIMEOUT_MS = 1_000
    }
}
