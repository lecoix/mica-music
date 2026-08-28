package com.mica.music.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackShuffleSessionCommandTest {
    @Test
    fun enabledRequestRoundTripsSeed() {
        val args = PlaybackShuffleSessionCommand.encode(enabled = true, seed = 42L)

        assertEquals(
            PlaybackShuffleRequest(enabled = true, seed = 42L),
            PlaybackShuffleSessionCommand.decode(PlaybackShuffleSessionCommand.command, args),
        )
    }

    @Test
    fun disabledRequestClearsSeed() {
        val args = PlaybackShuffleSessionCommand.encode(enabled = false, seed = null)

        assertEquals(
            PlaybackShuffleRequest(enabled = false, seed = null),
            PlaybackShuffleSessionCommand.decode(PlaybackShuffleSessionCommand.command, args),
        )
    }

    @Test
    fun enabledRequestWithoutSeedIsRejected() {
        val args = PlaybackShuffleSessionCommand.encode(enabled = true, seed = null)

        assertNull(PlaybackShuffleSessionCommand.decode(PlaybackShuffleSessionCommand.command, args))
    }
}
