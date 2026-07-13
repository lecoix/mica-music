package com.mica.music.media

import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.ReplayGainMode
import com.mica.music.data.ReplayGainSource
import com.mica.music.data.ReplayGainTags
import com.mica.music.data.preferences.MicaSettingsStore
import com.mica.music.data.preferences.ReplayGainPreferences
import com.mica.music.testutil.SongFixtures
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplayGainStateOwnerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        MicaSettingsStore.prefs(context).edit().clear().commit()
    }

    @Test
    fun currentStateMatchesTheFactorAppliedToThePlayer() {
        val player = mockk<MicaCompositePlayer>(relaxed = true)
        val owner = ReplayGainStateOwner(context, player)

        val attenuated = owner.apply(
            ReplayGainTags(trackGainDb = -6.0206f),
            ReplayGainMode.TRACK,
        )
        val unity = owner.apply(null, ReplayGainMode.TRACK)

        verify { player.setReplayGainVolume(match { factor -> kotlin.math.abs(factor - 0.5f) < 0.0001f }) }
        verify { player.setReplayGainVolume(1f) }
        assertEquals(unity, owner.current)
        assertEquals(0.5f, attenuated.linearFactor, 0.0001f)
        assertEquals(ReplayGainSource.MISSING_TAG, owner.current.source)
    }

    @Test
    fun startAndReleaseOwnPlayerAndPreferenceListeners() {
        val player = mockk<MicaCompositePlayer>(relaxed = true)
        every { player.currentMediaItem } returns null
        val owner = ReplayGainStateOwner(context, player)

        owner.start()
        ReplayGainPreferences.setMode(context, ReplayGainMode.ALBUM)

        assertEquals(ReplayGainMode.ALBUM, owner.current.mode)
        assertEquals(ReplayGainSource.MISSING_TAG, owner.current.source)
        owner.release()
        ReplayGainPreferences.setMode(context, ReplayGainMode.TRACK)
        assertEquals(ReplayGainMode.ALBUM, owner.current.mode)
        verify(exactly = 1) { player.addListener(any()) }
        verify(exactly = 1) { player.removeListener(any()) }
    }

    @Test
    fun mediaItemTransitionRecomputesAppliedReplayGain() {
        val listener = slot<Player.Listener>()
        val player = mockk<MicaCompositePlayer>(relaxed = true)
        every { player.currentMediaItem } returns null
        every { player.addListener(capture(listener)) } just Runs
        ReplayGainPreferences.setMode(context, ReplayGainMode.TRACK)
        val owner = ReplayGainStateOwner(context, player)
        owner.start()
        val mediaItem = SongMediaItemCodec.encode(
            SongFixtures.song().copy(replayGain = ReplayGainTags(trackGainDb = -6.0206f)),
        )

        listener.captured.onMediaItemTransition(mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals(ReplayGainSource.TRACK_TAG, owner.current.source)
        assertEquals(0.5f, owner.current.linearFactor, 0.0001f)
    }
}
