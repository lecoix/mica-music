package com.mica.music.media.usbhybrid

import org.junit.Assert.assertEquals
import org.junit.Test

class Sk02TargetSelectorTest {
    @Test
    fun selectsOnlyTheSingleBuiltInSk02Candidate() {
        val sk02 = candidate(0x262a, 0x0001, 7, "a")
        val unrelated = candidate(0x1234, 0xabcd, 8, "b")

        assertEquals(Sk02Selection.Selected(sk02), Sk02TargetSelector.select(listOf(unrelated, sk02)))
    }

    @Test
    fun multipleSk02CandidatesFailClosed() {
        val first = candidate(0x262a, 0x0001, 7, "a")
        val second = candidate(0x262a, 0x0001, 9, "b")

        assertEquals(Sk02Selection.Ambiguous(2), Sk02TargetSelector.select(listOf(first, second)))
    }

    @Test
    fun unknownDacIsNotASelectableTarget() {
        assertEquals(
            Sk02Selection.NotFound,
            Sk02TargetSelector.select(listOf(candidate(0x1234, 0xabcd, 8, "b"))),
        )
    }

    @Test
    fun sameVidPidRevisionCollisionIsNotSk02() {
        val collision = candidate(0x262a, 0x0001, 8, "b", "Douk Audio", "K5")
        assertEquals(Sk02Selection.NotFound, Sk02TargetSelector.select(listOf(collision)))
    }

    private fun candidate(
        vid: Int,
        pid: Int,
        id: Int,
        digest: String,
        manufacturer: String = "Speed Dragon",
        product: String = "Fosi Audio SK02",
    ) = UsbDeviceCandidate(
        UsbStableIdentity(vid, pid, 0x0004, digest),
        UsbRuntimeHandle(id, "/dev/bus/usb/001/$id"),
        manufacturer,
        product,
    )
}
