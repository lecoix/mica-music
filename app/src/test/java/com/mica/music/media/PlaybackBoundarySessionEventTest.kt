package com.mica.music.media

import android.os.Bundle
import androidx.media3.session.SessionCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackBoundarySessionEventTest {

    @Test
    fun boundaryRoundTripsThroughSessionCommandArguments() {
        val boundary = ConfirmedPlaybackBoundary(
            oldSongId = "old",
            newSongId = "new",
            oldPositionMs = 59_900L,
            newPositionMs = 0L,
        )

        assertEquals(
            boundary,
            PlaybackBoundarySessionEvent.decode(
                PlaybackBoundarySessionEvent.command,
                PlaybackBoundarySessionEvent.encode(boundary),
            ),
        )
    }

    @Test
    fun unrelatedCustomCommandIsIgnored() {
        assertNull(
            PlaybackBoundarySessionEvent.decode(
                SessionCommand("unrelated", Bundle.EMPTY),
                Bundle.EMPTY,
            ),
        )
    }
}
