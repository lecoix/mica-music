package com.mica.music.media

import android.content.Context
import android.os.Handler
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackOrderAcknowledgementTest {
    @Test fun acknowledgementWaitsForActualApplicationAndRejectsSupersededPost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val handler = mockk<Handler>()
        val posted = slot<Runnable>()
        every { handler.post(capture(posted)) } returns true
        val owner = ServicePlaybackOrderOwner()
        val ids = listOf("a", "b", "c")
        val callback = MicaMediaSessionCallback(context, handler, mockk(relaxed = true),
            { null }, { null }, { _, item -> item },
            { owner.accept(it, ids, "a") != null }, {}, { owner.snapshot(ids) })
        val info = mockk<MediaSession.ControllerInfo>(relaxed = true)
        every { info.packageName } returns context.packageName
        val args = checkNotNull(PlaybackShuffleSessionCommand.encodeOrder(ids, ids.reversed(), ids,
            true, PlaybackShuffleSessionCommand.invalidateRequests()))
        val result = callback.onCustomCommand(mockk(relaxed = true), info,
            PlaybackShuffleSessionCommand.command, args)
        assertFalse(result.isDone)
        assertNull(owner.project(ids))
        PlaybackShuffleSessionCommand.invalidateRequests()
        posted.captured.run()
        assertTrue(result.isDone)
        assertNotEquals(SessionResult.RESULT_SUCCESS, result.get().resultCode)
        assertNull(owner.project(ids))

        val newest = checkNotNull(PlaybackShuffleSessionCommand.encodeOrder(ids, ids.reversed(), ids,
            true, PlaybackShuffleSessionCommand.invalidateRequests()))
        val accepted = callback.onCustomCommand(mockk(relaxed = true), info,
            PlaybackShuffleSessionCommand.command, newest)
        assertFalse(accepted.isDone)
        posted.captured.run()
        assertEquals(SessionResult.RESULT_SUCCESS, accepted.get().resultCode)
        assertEquals(ids.reversed(), owner.project(ids)?.playbackIds)
    }
}
