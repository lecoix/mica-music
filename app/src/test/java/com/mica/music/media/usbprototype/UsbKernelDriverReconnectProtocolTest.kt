package com.mica.music.media.usbprototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbKernelDriverReconnectProtocolTest {
    @Test
    fun successfulControlConnectDoesNotIssueRedundantStreamingConnect() {
        val connected = mutableListOf<Int>()

        val result = reconnectKernelDriversByInterface(
            isCurrent = { true },
            driversAreBound = { false },
            connectInterface = { interfaceNumber ->
                connected += interfaceNumber
                0
            },
            controlInterface = 1,
            streamingInterface = 2,
        )

        assertEquals(UsbKernelDriverReconnectResult(errno = 0, connectedInterface = 1), result)
        assertEquals(listOf(1), connected)
    }

    @Test
    fun invalidatedRequestCannotPerformSecondSideEffectOrPublishResult() {
        var current = true
        val connected = mutableListOf<Int>()

        val result = reconnectKernelDriversByInterface(
            isCurrent = { current },
            driversAreBound = { false },
            connectInterface = { interfaceNumber ->
                connected += interfaceNumber
                current = false
                5
            },
            controlInterface = 1,
            streamingInterface = 2,
        )

        assertNull(result)
        assertEquals(listOf(1), connected)
    }

    @Test
    fun streamingConnectIsFallbackOnlyAfterControlFailure() {
        val connected = mutableListOf<Int>()

        val result = reconnectKernelDriversByInterface(
            isCurrent = { true },
            driversAreBound = { false },
            connectInterface = { interfaceNumber ->
                connected += interfaceNumber
                if (interfaceNumber == 1) 5 else 0
            },
            controlInterface = 1,
            streamingInterface = 2,
        )

        assertEquals(UsbKernelDriverReconnectResult(errno = 0, connectedInterface = 2), result)
        assertEquals(listOf(1, 2), connected)
    }
}
