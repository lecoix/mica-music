package com.mica.music.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MusicVideoPreferenceOwnerTest {
    @Test
    fun settingChangeRewritesOnlyNonCurrentAndTransitionRefreshesOldItem() {
        val first = musicVideoItem("first")
        val second = musicVideoItem("second")
        var currentIndex = 0
        val items = mutableListOf(first, second)
        val listener = slot<Player.Listener>()
        val player = mockk<Player>(relaxed = true) {
            every { mediaItemCount } answers { items.size }
            every { currentMediaItemIndex } answers { currentIndex }
            every { getMediaItemAt(any()) } answers { items[firstArg()] }
            every { replaceMediaItem(any(), any()) } answers {
                items[firstArg()] = secondArg()
            }
            every { addListener(capture(listener)) } returns Unit
        }
        val owner = MusicVideoPreferenceOwner(initialRequested = false)
        owner.attach(player)

        owner.updateRequested(true)

        assertFalse(MusicVideoPlaybackPolicyCodec.isEnabled(items[0]))
        assertTrue(MusicVideoPlaybackPolicyCodec.isEnabled(items[1]))
        verify(exactly = 0) { player.replaceMediaItem(0, any()) }

        currentIndex = 1
        listener.captured.onMediaItemTransition(items[1], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertTrue(MusicVideoPlaybackPolicyCodec.isEnabled(items[0]))
        assertTrue(MusicVideoPlaybackPolicyCodec.isEnabled(items[1]))
    }

    @Test
    fun audioOnlyQueueIsNeverReplacedByMusicVideoPreference() {
        val item = MediaItem.Builder().setMediaId("audio").setUri("content://audio").build()
        val listener = slot<Player.Listener>()
        val player = mockk<Player>(relaxed = true) {
            every { mediaItemCount } returns 5_000
            every { currentMediaItemIndex } returns 0
            every { getMediaItemAt(any()) } returns item
            every { addListener(capture(listener)) } returns Unit
        }
        val owner = MusicVideoPreferenceOwner(initialRequested = false)
        owner.attach(player)
        listener.captured.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertSame(item, owner.decorateNew(item))
        owner.updateRequested(true)
        assertSame(item, owner.decorateNew(item))
        verify(exactly = 0) { player.replaceMediaItem(any(), any()) }
    }

    @Test
    fun staleGenerationCannotOverwriteLatestRequest() {
        val item = musicVideoItem("next")
        val items = mutableListOf(item)
        val player = mockk<Player>(relaxed = true) {
            every { mediaItemCount } returns 1
            every { currentMediaItemIndex } returns -1
            every { getMediaItemAt(0) } answers { items[0] }
            every { replaceMediaItem(0, any()) } answers { items[0] = secondArg() }
        }
        val owner = MusicVideoPreferenceOwner(initialRequested = false)
        owner.attach(player)
        owner.updateRequested(true)
        val staleGeneration = owner.generationForTests()
        owner.updateRequested(false)

        owner.refreshNonCurrent(staleGeneration)

        assertFalse(MusicVideoPlaybackPolicyCodec.isEnabled(items.single()))
    }

    private fun musicVideoItem(id: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri("content://$id")
            .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
                putString(MusicVideoMediaSourceFactory.MUSIC_VIDEO_URI_EXTRA, "content://$id/video")
            }).build())
            .build()
}
