package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRuntimeFactsTest {
    @Test
    fun mapsOnlyAuthoritativeSupportedKernelBusSpeeds() {
        assertNull(UsbKernelBusSpeedMapper.map(0))
        assertNull(UsbKernelBusSpeedMapper.map(1))
        assertEquals(UsbBusSpeed.FULL, UsbKernelBusSpeedMapper.map(2))
        assertEquals(UsbBusSpeed.HIGH, UsbKernelBusSpeedMapper.map(3))
        assertNull(UsbKernelBusSpeedMapper.map(4))
        assertEquals(UsbBusSpeed.SUPER, UsbKernelBusSpeedMapper.map(5))
        assertEquals(UsbBusSpeed.SUPER, UsbKernelBusSpeedMapper.map(6))
        assertNull(UsbKernelBusSpeedMapper.map(-25))
    }

    @Test
    fun realSk02DeviceDescriptorCarriesExactBcdDeviceWithoutRuntimeIdInStableIdentity() {
        val result = UsbRuntimeDescriptorFactsAssembler.assemble(
            runtimeVendorId = 0x262a,
            runtimeProductId = 0x0001,
            runtimeDeviceId = 73,
            rawDescriptors = sk02RawDescriptors(),
            busSpeed = UsbBusSpeed.HIGH,
            serialNumber = "  ",
        ) as UsbRuntimeFactsResult.Ready

        assertEquals(0x262a, result.facts.identity.vendorId)
        assertEquals(0x0001, result.facts.identity.productId)
        assertEquals(0x0004, result.facts.identity.bcdDevice)
        assertNull(result.facts.identity.serialNumber)
        assertNull(result.facts.identity.topologyHint)
        assertTrue(result.facts.identity.descriptorFingerprint.startsWith("sha256:"))
        assertEquals(73, result.facts.runtimeHandle.runtimeDeviceId)
        assertEquals(UsbBusSpeed.HIGH, result.facts.descriptorSet.busSpeed)
    }

    @Test
    fun unknownBusSpeedFailsClosedBeforeParserPolicy() {
        val result = UsbRuntimeDescriptorFactsAssembler.assemble(
            runtimeVendorId = 0x262a,
            runtimeProductId = 0x0001,
            runtimeDeviceId = 1,
            rawDescriptors = sk02RawDescriptors(),
            busSpeed = UsbBusSpeed.UNKNOWN,
            serialNumber = null,
        )

        assertTrue(result is UsbRuntimeFactsResult.Rejected)
        result as UsbRuntimeFactsResult.Rejected
        assertEquals(UsbRuntimeFactRejectionCode.BUS_SPEED_UNAVAILABLE, result.rejection.code)
    }

    @Test
    fun runtimeAndRawIdentityMismatchFailsClosed() {
        val result = UsbRuntimeDescriptorFactsAssembler.assemble(
            runtimeVendorId = 0x1234,
            runtimeProductId = 0x0001,
            runtimeDeviceId = 1,
            rawDescriptors = sk02RawDescriptors(),
            busSpeed = UsbBusSpeed.HIGH,
            serialNumber = null,
        )

        assertTrue(result is UsbRuntimeFactsResult.Rejected)
        result as UsbRuntimeFactsResult.Rejected
        assertEquals(UsbRuntimeFactRejectionCode.DEVICE_IDENTITY_MISMATCH, result.rejection.code)
    }
}

internal fun sk02RawDescriptors(): ByteArray {
    val text = requireNotNull(
        UsbRuntimeFactsTest::class.java.getResource("/usb/sk02/raw-descriptors.hex"),
    ).readText().trim()
    require(text.length % 2 == 0)
    return ByteArray(text.length / 2) { index ->
        text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
