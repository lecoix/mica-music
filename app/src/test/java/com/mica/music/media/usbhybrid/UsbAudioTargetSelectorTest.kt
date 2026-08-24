package com.mica.music.media.usbhybrid

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAudioTargetSelectorTest {
    @Test fun selectsSingleAudioOutputRegardlessOfVendor() {
        val dac = candidate(0x1234, 0xabcd, 8, true)
        val nonAudio = candidate(0x262a, 0x0001, 7, false)
        assertEquals(UsbAudioSelection.Selected(dac), UsbAudioTargetSelector.select(listOf(nonAudio, dac)))
    }

    @Test fun multipleAudioOutputsFailClosed() {
        assertEquals(UsbAudioSelection.Ambiguous(2), UsbAudioTargetSelector.select(listOf(
            candidate(0x1234, 1, 7, true), candidate(0x5678, 2, 8, true))))
    }

    @Test fun noAudioOutputIsNotFound() {
        assertEquals(UsbAudioSelection.NotFound, UsbAudioTargetSelector.select(listOf(candidate(1, 2, 3, false))))
    }

    private fun candidate(vid:Int,pid:Int,id:Int,audio:Boolean)=UsbDeviceCandidate(
        UsbStableIdentity(vid,pid,0x0100,"digest-$id"), UsbRuntimeHandle(id,"/dev/bus/usb/001/$id"),
        "Vendor$id","DAC$id",audio)
}
