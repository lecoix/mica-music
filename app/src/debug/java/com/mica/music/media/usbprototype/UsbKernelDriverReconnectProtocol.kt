package com.mica.music.media.usbprototype

internal data class UsbKernelDriverReconnectResult(
    val errno: Int,
    val connectedInterface: Int?,
)

/**
 * Runs the two-interface reconnect protocol with a validity check after every USB IO boundary.
 * A successful control-interface CONNECT is terminal because snd-usb-audio binds the associated
 * SK02 control and streaming interfaces as one driver instance.
 */
internal fun reconnectKernelDriversByInterface(
    isCurrent: () -> Boolean,
    driversAreBound: () -> Boolean,
    connectInterface: (Int) -> Int,
    controlInterface: Int,
    streamingInterface: Int,
): UsbKernelDriverReconnectResult? {
    if (!isCurrent()) return null
    val initiallyBound = driversAreBound()
    if (!isCurrent()) return null
    if (initiallyBound) return UsbKernelDriverReconnectResult(errno = 0, connectedInterface = null)

    if (!isCurrent()) return null
    val controlError = connectInterface(controlInterface)
    if (!isCurrent()) return null
    if (controlError == 0) {
        return UsbKernelDriverReconnectResult(errno = 0, connectedInterface = controlInterface)
    }

    val boundAfterControl = driversAreBound()
    if (!isCurrent()) return null
    if (boundAfterControl) {
        return UsbKernelDriverReconnectResult(errno = 0, connectedInterface = controlInterface)
    }

    if (!isCurrent()) return null
    val streamingError = connectInterface(streamingInterface)
    if (!isCurrent()) return null
    if (streamingError == 0) {
        return UsbKernelDriverReconnectResult(errno = 0, connectedInterface = streamingInterface)
    }

    val finallyBound = driversAreBound()
    if (!isCurrent()) return null
    return UsbKernelDriverReconnectResult(
        errno = if (finallyBound) 0 else controlError.takeIf { it != 0 } ?: streamingError,
        connectedInterface = null,
    )
}
