package com.mica.music.media

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.preferences.PlaybackUiPreferences
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MicaMediaServiceNoisyReceiverTest {
    @Test
    fun serviceRegistersNoisyReceiverPausesOnceAndUnregistersOnDestroy() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val previousAudioFocus = PlaybackUiPreferences.audioFocusEnabled(application)
        val receiversBefore = noisyReceiverCount(application)
        val serviceController = Robolectric.buildService(MicaMediaService::class.java)

        try {
            PlaybackUiPreferences.setAudioFocusEnabled(application, true)
            val service = serviceController.create().get()
            assertEquals(receiversBefore + 1, noisyReceiverCount(application))

            val player = service.onGetSession(mockk(relaxed = true))!!.player
            player.setMediaItem(
                MediaItem.Builder()
                    .setMediaId("robolectric-noisy")
                    .setUri("file:///robolectric-noisy.wav")
                    .build(),
            )
            player.playWhenReady = true
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(player.playWhenReady)

            val pauseChanges = AtomicInteger()
            player.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) pauseChanges.incrementAndGet()
                }
            })

            application.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(player.playWhenReady)
            assertEquals(1, pauseChanges.get())

            application.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, pauseChanges.get())

            serviceController.destroy()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(receiversBefore, noisyReceiverCount(application))
        } finally {
            PlaybackUiPreferences.setAudioFocusEnabled(application, previousAudioFocus)
        }
    }

    private fun noisyReceiverCount(application: Application): Int =
        shadowOf(application).registeredReceivers.count { wrapper ->
            wrapper.intentFilter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
}
