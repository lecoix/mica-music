package com.mica.music.data.remote.smb

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmbOptionalIoTest {
    @After
    fun tearDown() {
        SmbOptionalIo.currentMediaId = null
        SmbOptionalIo.bufferingSourceId = null
    }

    @Test
    fun deferredPlaybackGateRetriesUntilRequestedSongIsCurrentAndPlaybackIsReady() = runTest {
        SmbOptionalIo.currentMediaId = "old-song"
        SmbOptionalIo.bufferingSourceId = "smb-1"
        var blockCalls = 0

        val result = async {
            SmbOptionalIo.runWhenPlaybackReady(
                sourceId = "smb-1",
                mediaId = "new-song",
                retryDelayMs = 100,
                maxAttempts = 5,
            ) {
                blockCalls++
                "lyrics"
            }
        }

        runCurrent()
        assertFalse(result.isCompleted)

        SmbOptionalIo.currentMediaId = "new-song"
        advanceTimeBy(100)
        runCurrent()
        assertFalse(result.isCompleted)

        SmbOptionalIo.bufferingSourceId = null
        advanceTimeBy(100)
        runCurrent()

        assertEquals("lyrics", result.await())
        assertEquals(1, blockCalls)
    }

    @Test
    fun realIoFailureIsNotRetriedAsPlaybackDeferral() = runTest {
        SmbOptionalIo.currentMediaId = "song"
        SmbOptionalIo.bufferingSourceId = null
        var blockCalls = 0

        val failure = try {
            SmbOptionalIo.runWhenPlaybackReady(
                sourceId = "smb-1",
                mediaId = "song",
                retryDelayMs = 100,
                maxAttempts = 5,
            ) {
                blockCalls++
                throw IOException("network failed")
            }
            null
        } catch (error: IOException) {
            error
        }

        assertEquals("network failed", failure?.message)
        assertEquals(1, blockCalls)
    }

    @Test
    fun visibleArtworkWaitsForSourceBufferingButDoesNotRequireCurrentSong() = runTest {
        SmbOptionalIo.currentMediaId = "some-other-song"
        SmbOptionalIo.bufferingSourceId = "smb-1"
        var blockCalls = 0

        val result = async {
            SmbOptionalIo.runWhenSourceReady(
                sourceId = "smb-1",
                retryDelayMs = 100,
                maxAttempts = 3,
            ) {
                blockCalls++
                "artwork"
            }
        }

        runCurrent()
        assertFalse(result.isCompleted)
        SmbOptionalIo.bufferingSourceId = null
        advanceTimeBy(100)
        runCurrent()

        assertEquals("artwork", result.await())
        assertEquals(1, blockCalls)
    }
}
