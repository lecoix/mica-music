package com.mica.music.media.usbhybrid

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbSharedQuiescencePolicyResolverTest {
    private val sk02 = UsbStableIdentity(0x262a, 0x0001, 0x0100, "sk02")

    @Test fun qualifiedRedmiSk02UsesLongerSettle() {
        assertEquals(800L, UsbSharedQuiescencePolicyResolver.resolve("Xiaomi", "22081212C", sk02).settleMs)
    }

    @Test fun unknownHostUsesConservativeDefault() {
        assertEquals(600L, UsbSharedQuiescencePolicyResolver.resolve("Google", "Pixel", sk02).settleMs)
    }
}
