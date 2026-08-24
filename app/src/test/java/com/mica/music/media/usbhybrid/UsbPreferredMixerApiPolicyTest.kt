package com.mica.music.media.usbhybrid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPreferredMixerApiPolicyTest {
    @Test
    fun attributedDeviceQueryRequiresAndroid13() {
        assertFalse(supportsAttributedAudioDeviceQuery(32))
        assertTrue(supportsAttributedAudioDeviceQuery(33))
    }

    @Test
    fun preferredMixerAttributesRequireAndroid14() {
        assertFalse(supportsPreferredMixerAttributes(33))
        assertTrue(supportsPreferredMixerAttributes(34))
    }
}
