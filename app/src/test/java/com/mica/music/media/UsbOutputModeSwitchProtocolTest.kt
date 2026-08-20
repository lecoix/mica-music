package com.mica.music.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbOutputModeSwitchProtocolTest {
    @Test
    fun failedRetirementDoesNotMintRequestEpoch() {
        var requestCalled = false

        val result = runCatching {
            UsbOutputModeSwitchProtocol.retireThenRequest(
                capture = { "queue@position" },
                retire = { error("release timed out") },
                request = {
                    requestCalled = true
                    8L
                },
            )
        }

        assertTrue(result.isFailure)
        assertFalse(requestCalled)
    }

    @Test
    fun requestEpochIsNotMintedUntilOldStackRetirementReturns() {
        val retirementEntered = CountDownLatch(1)
        val allowRetirement = CountDownLatch(1)
        var oldStackCanWrite = true
        var requestCalled = false
        val executor = Executors.newSingleThreadExecutor()

        val result = executor.submit<Pair<String?, Long>> {
            UsbOutputModeSwitchProtocol.retireThenRequest(
                capture = { "queue@position" },
                retire = {
                    retirementEntered.countDown()
                    assertTrue(allowRetirement.await(5, TimeUnit.SECONDS))
                    oldStackCanWrite = false
                },
                request = {
                    requestCalled = true
                    assertFalse("new epoch must not precede old write retirement", oldStackCanWrite)
                    7L
                },
            )
        }

        assertTrue(retirementEntered.await(5, TimeUnit.SECONDS))
        assertFalse(requestCalled)
        assertTrue(oldStackCanWrite)
        allowRetirement.countDown()
        assertEquals("queue@position" to 7L, result.get(5, TimeUnit.SECONDS))
        assertTrue(requestCalled)
        assertFalse(oldStackCanWrite)
        executor.shutdownNow()
    }
}
