package com.mica.music.media.usbhybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UsbStableIdentityDigestTest {
    @Test
    fun digestIsStableForTheSameCanonicalDescriptorModel() {
        val model = UsbDescriptorModel(
            vendorId = 0x262a,
            productId = 0x0001,
            version = "1.00",
            configurations = listOf("c1:i1:a0:e01:1024", "c1:i1:a1:e01:1024"),
        )

        assertEquals(
            UsbStableIdentityDigest.sha256(model),
            UsbStableIdentityDigest.sha256(model.copy()),
        )
    }

    @Test
    fun digestChangesWhenEndpointTopologyChanges() {
        val before = UsbDescriptorModel(0x262a, 0x0001, "1.00", listOf("e01:1024"))
        val after = before.copy(configurations = listOf("e01:512"))

        assertNotEquals(
            UsbStableIdentityDigest.sha256(before),
            UsbStableIdentityDigest.sha256(after),
        )
    }
}
