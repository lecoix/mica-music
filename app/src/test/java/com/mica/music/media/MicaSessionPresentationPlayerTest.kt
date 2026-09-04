package com.mica.music.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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