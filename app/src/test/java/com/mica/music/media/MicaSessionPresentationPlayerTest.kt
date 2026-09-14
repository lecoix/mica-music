package com.mica.music.media

import android.content.Context
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MicaSessionPresentationPlayerTest {
    @Test
    fun lyricPresentationChangesSessionViewWithoutMutatingSourceQueue() {
        val source = newPlayer()
        try {
            val song = song("one", "Song One")
            source.setMediaItem(item(song))
            val presentation = MicaSessionPresentationPlayer(source)

            presentation.publishLyric(song, "  current lyric  ")

            assertEquals("current lyric", presentation.mediaMetadata.title?.toString())
            assertEquals("Song One - Artist", presentation.mediaMetadata.artist?.toString())
            assertEquals("Song One", source.currentMediaItem?.mediaMetadata?.title?.toString())

            presentation.clear()

            assertEquals("Song One", presentation.mediaMetadata.title?.toString())
            assertEquals("Song One", source.currentMediaItem?.mediaMetadata?.title?.toString())
        } finally {
            source.release()
        }
    }

    @Test
    fun transportCommandsRemainOwnedByWrappedPlayer() {
        val source = newPlayer()
        try {
            source.setMediaItems(
                listOf(
                    item(song("one", "One")),
                    item(song("two", "Two")),
                    item(song("three", "Three")),
                ),
            )
            val presentation = MicaSessionPresentationPlayer(source)

            assertSame(source, source.takeIf(presentation::wraps))
            assertEquals(0, source.currentMediaItemIndex)

            presentation.seekToNextMediaItem()
            assertEquals(1, source.currentMediaItemIndex)

            presentation.seekToPreviousMediaItem()
            assertEquals(0, source.currentMediaItemIndex)
        } finally {
            source.release()
        }
    }

    @Test
    fun loadingSafePlayerClearsLoadingWhileIdleOrEnded() {
        val inner = newPlayer()
        try {
            val alwaysLoading = object : ForwardingPlayer(inner) {
                override fun isLoading(): Boolean = true
            }
            val safe = LoadingSafePlayer(alwaysLoading)

            // Fresh ExoPlayer is IDLE; loading must be coerced off for State validity.
            assertEquals(Player.STATE_IDLE, safe.playbackState)
            assertTrue(alwaysLoading.isLoading)
            assertFalse(safe.isLoading)

            inner.setMediaItem(item(song("one", "One")))
            inner.prepare()
            shadowOf(Looper.getMainLooper()).idle()
            // While buffering/ready, passthrough remains true.
            if (safe.playbackState != Player.STATE_IDLE && safe.playbackState != Player.STATE_ENDED) {
                assertTrue(safe.isLoading)
            }
        } finally {
            inner.release()
        }
    }

    @Test
    fun presentationGetStateSurvivesEndedWhileSourceReportsLoading() {
        // Repro for crash-20260915-001447: shuffle/mode changes at STATE_ENDED can leave
        // ExoPlayer.isLoading=true; ForwardingSimpleBasePlayer must not build illegal State.
        val inner = newPlayer()
        try {
            val endedAndLoading = object : ForwardingPlayer(inner) {
                override fun getPlaybackState(): Int = Player.STATE_ENDED
                override fun isLoading(): Boolean = true
            }
            val presentation = MicaSessionPresentationPlayer(endedAndLoading)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(Player.STATE_ENDED, presentation.playbackState)
            assertFalse(presentation.isLoading)

            // Touch metadata/state paths that call getState() (same as MediaSession invalidate).
            presentation.mediaMetadata
            presentation.publishLyric(song("one", "One"), "line")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Player.STATE_ENDED, presentation.playbackState)
            assertFalse(presentation.isLoading)
        } finally {
            inner.release()
        }
    }

    private fun newPlayer(): ExoPlayer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ExoPlayer.Builder(context).build()
    }

    private fun item(song: Song): MediaItem =
        MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(song.mediaUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .build(),
            )
            .build()

    private fun song(id: String, title: String): Song =
        Song(
            id = id,
            title = title,
            artist = "Artist",
            album = "Album",
            durationSec = 180,
            metadata = TrackMetadata(
                containerName = "TEST",
                sampleRateHz = 44_100,
                bitsPerSample = 16,
                bitrateKbps = 320,
                channelCount = 2,
                playbackMimeType = "audio/mpeg",
            ),
            albumArtUri = null,
            coverColorArgb = 0,
            mediaUri = "content://test/$id",
        )
}