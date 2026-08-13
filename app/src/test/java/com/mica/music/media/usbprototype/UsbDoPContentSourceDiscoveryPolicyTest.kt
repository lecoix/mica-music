package com.mica.music.media.usbprototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDoPContentSourceDiscoveryPolicyTest {
    @Test
    fun explicitDsfMimeAcceptsWrappedOuterDsdName() {
        assertTrue(
            UsbDoPContentSourceDiscoveryPolicy.isCandidate(
                displayName = "track.dsf.dsd",
                mimeType = "audio/x-dsf",
            ),
        )
    }

    @Test
    fun filenameFallbackAcceptsFinalDsfAndKnownDsfWrapper() {
        assertTrue(UsbDoPContentSourceDiscoveryPolicy.isCandidate("track.dsf", "audio/dsd"))
        assertTrue(UsbDoPContentSourceDiscoveryPolicy.isCandidate("track.dsf.dsd", "audio/dsd"))
    }

    @Test
    fun genericDsdWithAmbiguousOuterDsdNameStaysFailClosed() {
        assertFalse(UsbDoPContentSourceDiscoveryPolicy.isCandidate("track.dsd", "audio/dsd"))
    }

    @Test
    fun explicitDffMimeWinsOverMisleadingDsfName() {
        assertFalse(UsbDoPContentSourceDiscoveryPolicy.isCandidate("misleading.dsf", "audio/x-dff"))
        assertFalse(UsbDoPContentSourceDiscoveryPolicy.isCandidate("misleading.dsf.dsd", "audio/x-dsdiff"))
    }

    @Test
    fun preferredLengthDsd128RanksAheadOfDsd64() {
        val durationUs = 60_000_000L
        val dsd64 = UsbDoPContentSourceDiscoveryPolicy.score(2_822_400, durationUs)
        val dsd128 = UsbDoPContentSourceDiscoveryPolicy.score(5_644_800, durationUs)

        assertTrue(dsd128 > dsd64)
    }
}
