package com.mica.music.imaging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverLoadCoordinatorTest {

    @Test
    fun concurrentLoadsForTheSameKeyShareOneLoader() = runTest {
        val coordinator = CoverLoadCoordinator(this)
        val releaseLoad = CompletableDeferred<Unit>()
        var loadCount = 0

        val first = async {
            coordinator.execute("cover:target") {
                loadCount++
                releaseLoad.await()
                true
            }
        }
        val second = async {
            coordinator.execute("cover:target") {
                loadCount++
                false
            }
        }
        testScheduler.runCurrent()
        releaseLoad.complete(Unit)

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, loadCount)
    }

    @Test
    fun failedLoadIsRemovedSoTheNextCallerCanRetry() = runTest {
        val coordinator = CoverLoadCoordinator(this)
        var loadCount = 0

        var failure: Throwable? = null
        try {
            coordinator.execute("cover:target") {
                loadCount++
                if (loadCount == 1) error("decode failed")
                true
            }
        } catch (caught: Throwable) {
            failure = caught
        }

        assertTrue(failure is IllegalStateException)
        assertTrue(
            coordinator.execute("cover:target") {
                loadCount++
                true
            },
        )
        assertEquals(2, loadCount)
    }
}
