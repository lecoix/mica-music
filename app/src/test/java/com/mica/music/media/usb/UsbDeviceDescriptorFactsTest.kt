package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbDeviceDescriptorFactsTest {
    @Test
    fun stableIdentityKeepsUnknownDeviceRevisionExplicitlyUnknown() {
        val identity = UsbAudioDeviceIdentity(
            vendorId = 0x1234,
            productId = 0x5678,
            descriptorFingerprint = "fixture",
        )

        assertNull(identity.bcdDevice)
    }

    @Test
    fun parsesExactBcdDeviceFromStandardDeviceDescriptor() {
        val raw = byteArrayOf(
            18, 0x01,
            0x00, 0x02,
            0x00, 0x00, 0x00, 64,
            0x2a, 0x26,
            0x01, 0x00,
            0x04, 0x00,
            1, 2, 3, 1,
        )

        assertEquals(
            UsbDeviceDescriptorFacts(
                vendorId = 0x262a,
                productId = 0x0001,
                bcdDevice = 0x0004,
            ),
            StandardUsbDeviceDescriptorParser.parse(raw),
        )
    }

    @Test
    fun nonDeviceDescriptorDoesNotInventRevision() {
        val raw = byteArrayOf(9, 0x04, 0, 0, 0, 1, 1, 0x20, 0)

        assertNull(StandardUsbDeviceDescriptorParser.parse(raw))
    }
}
