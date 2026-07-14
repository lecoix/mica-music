package com.mica.music.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.testutil.ContractTestSupport.await
import com.mica.music.testutil.ContractTestSupport.connectMediaService
import com.mica.music.testutil.ContractTestSupport.createSilentWav
import com.mica.music.testutil.ContractTestSupport.onMain
import com.mica.music.testutil.ContractTestSupport.stopMediaServiceAndAwaitDestruction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ServicePlaybackRestorationContractTest {
    @Test
    fun serviceRecreationRestoresCurrentSongPositionAndRepeatWithoutAutoPlay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServicePlaybackStateStore(context)
        val files = listOf(
            createSilentWav(context.cacheDir, "restore-one", 4),
            createSilentWav(context.cacheDir, "restore-two", 4),
        )
        val items = files.mapIndexed { index, file ->
            SongMediaItemCodec.encode(testSong("restore-$index", file))
        }
        var first: MediaController? = null
        var restored: MediaController? = null
        try {
            stopMediaServiceAndAwaitDestruction(context)
            store.clear(sync = true)

            val firstController = connectMediaService(context)
            first = firstController
            onMain {
                firstController.setMediaItems(items)
                firstController.repeatMode = Player.REPEAT_MODE_ALL
                firstController.seekTo(1, RESTORED_POSITION_MS)
                firstController.prepare()
                firstController.pause()
            }
            await("service snapshot") {
                store.load()?.let { snapshot ->
                    snapshot.currentSongId == "restore-1" &&
                        snapshot.repeatMode == Player.REPEAT_MODE_ALL &&
                        snapshot.positionMs >= RESTORED_POSITION_MS
                } == true
            }

            onMain { firstController.release() }
            first = null
            stopMediaServiceAndAwaitDestruction(context)

            restored = connectMediaService(context)
            onMain { restored.setMediaItems(items) }
            await("restored service state") {
                onMain {
                    restored.mediaItemCount == 2 &&
                        restored.currentMediaItem?.mediaId == "restore-1" &&
                        restored.currentPosition >= RESTORED_POSITION_MS &&
                        restored.repeatMode == Player.REPEAT_MODE_ALL
                }
            }

            onMain {
                assertEquals(1, restored.currentMediaItemIndex)
                assertFalse(restored.playWhenReady)
                assertFalse(restored.isPlaying)
            }
        } finally {
            first?.let { onMain { it.release() } }
            restored?.let { onMain { it.release() } }
            stopMediaServiceAndAwaitDestruction(context)
            store.clear(sync = true)
            files.forEach(File::delete)
        }
    }

    private fun testSong(id: String, file: File) = Song(
        id = id,
        title = id,
        artist = "Mica",
        album = "Contract",
        durationSec = 4,
        metadata = TrackMetadata(
            containerName = "WAV",
            sampleRateHz = 8_000,
            bitsPerSample = 16,
            bitrateKbps = 128,
            channelCount = 1,
            playbackMimeType = "audio/wav",
        ),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = Uri.fromFile(file).toString(),
        fileName = file.name,
    )

    private companion object {
        const val RESTORED_POSITION_MS = 1_250L
    }
}
