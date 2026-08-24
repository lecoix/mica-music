package com.mica.music.media.usbprototype

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPrototypeGenerationGateTest {
    @Test
    fun oldRequestPausedAtUsbSideEffectCannotWriteAfterNewRequestWins() {
        val gate = UsbPrototypeGenerationGate()
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val oldAtSideEffectBoundary = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val newAttemptedTransport = CountDownLatch(1)
        val old = gate.beginRequest()

        val oldThread = thread(name = "old-usb-prototype") {
            gate.withTransport(old) { lease ->
                oldAtSideEffectBoundary.countDown()
                assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
                if (lease.isCurrent()) effects += "old-usb-submit"
            }
        }
        assertTrue(oldAtSideEffectBoundary.await(5, TimeUnit.SECONDS))

        val newer = gate.beginRequest()
        val newThread = thread(name = "new-usb-prototype") {
            newAttemptedTransport.countDown()
            gate.withTransport(newer) { lease ->
                if (lease.isCurrent()) effects += "new-usb-submit"
            }
        }
        assertTrue(newAttemptedTransport.await(5, TimeUnit.SECONDS))
        releaseOld.countDown()
        oldThread.join(5_000)
        newThread.join(5_000)

        assertEquals(listOf("new-usb-submit"), effects)
    }
}
