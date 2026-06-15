package com.mica.music.media

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.Test

class ServicePlaybackStateCoordinatorTest {

    @Test
    fun restorePreparesCurrentItemWithoutAutoPlay() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("one", "two"),
            currentIndex = 1,
            positionMs = 12_345L,
            repeatMode = Player.REPEAT_MODE_ALL,
            shuffleEnabled = true,
            playWhenReady = true,
            qualityMode = AudioQualityMode.HIFI,
        )

        every { store.load() } returns snapshot
        every { player.mediaItemCount } returns 2
        every { player.getMediaItemAt(0) } returns MediaItem.Builder().setMediaId("one").build()
        every { player.getMediaItemAt(1) } returns MediaItem.Builder().setMediaId("two").build()
        every { player.currentMediaItemIndex } returns 1
        every { player.currentPosition } returns 12_345L

        ServicePlaybackStateCoordinator(
            player = player,
            store = store,
            handler = handler,
            initialQualityMode = AudioQualityMode.HIFI,
        ).start()

        verifyOrder {
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.shuffleModeEnabled = true
            player.playWhenReady = false
            player.seekTo(1, 12_345L)
            player.prepare()
        }
    }
}
