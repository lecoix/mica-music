package com.mica.music.media.usbhybrid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Sk02ExperimentalNativeEvidenceTest {
    private val identity = UsbStableIdentity(0x262a, 0x0001, 0x0004, "digest")

    @Test
    fun exactSk02RevisionAndProductStringsAreExperimentalCandidate() {
        assertTrue(
            Sk02ExperimentalNativeEvidence.matches(identity, "Speed Dragon", "Fosi Audio SK02"),
        )
    }

    @Test
    fun knownSameVidPidRevisionCollisionIsRejected() {
        assertFalse(Sk02ExperimentalNativeEvidence.matches(identity, "Douk Audio", "K5"))
    }

    @Test
    fun anotherRevisionIsRejected() {
        assertFalse(
            Sk02ExperimentalNativeEvidence.matches(
                identity.copy(bcdDevice = 0x0003),
                "Speed Dragon",
                "Fosi Audio SK02",
            ),
        )
    }
}
