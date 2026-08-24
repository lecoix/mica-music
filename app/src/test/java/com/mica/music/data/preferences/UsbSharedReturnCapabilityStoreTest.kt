package com.mica.music.data.preferences

import com.mica.music.media.usbhybrid.UsbStableIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSharedReturnCapabilityStoreTest {
    private val identity = UsbStableIdentity(
        vendorId = 0x262a,
        productId = 0x0001,
        bcdDevice = 0x0100,
        descriptorDigest = "abc123",
    )

    @Test
    fun onlyKnownReconnectRequiredSkipsAutomaticSharedRecoveryProbe() {
        assertFalse(UsbSharedReturnPolicy.requiresPhysicalReconnect(UsbSharedReturnCapability.Unknown))
        assertTrue(UsbSharedReturnPolicy.requiresPhysicalReconnect(UsbSharedReturnCapability.ReconnectRequired))
        assertFalse(UsbSharedReturnPolicy.requiresPhysicalReconnect(UsbSharedReturnCapability.HotSwitchVerified))
    }

    @Test
    fun storageKeyIncludesAndroidEnvironmentAndDacIdentity() {
        val envA = UsbSharedReturnCapabilityStore.environmentKey("Xiaomi", "A", 34, "fp-a")
        val envB = UsbSharedReturnCapabilityStore.environmentKey("Google", "B", 34, "fp-b")
        val keyA1 = UsbSharedReturnCapabilityStore.storageKey(envA, identity)
        val keyA2 = UsbSharedReturnCapabilityStore.storageKey(envA, identity)
        val keyB = UsbSharedReturnCapabilityStore.storageKey(envB, identity)
        val otherDac = identity.copy(productId = 0x0002)

        assertEquals(keyA1, keyA2)
        assertNotEquals(keyA1, keyB)
        assertNotEquals(keyA1, UsbSharedReturnCapabilityStore.storageKey(envA, otherDac))
    }
}
