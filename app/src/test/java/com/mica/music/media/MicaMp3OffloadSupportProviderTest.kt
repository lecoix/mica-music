package com.mica.music.media

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MicaMp3OffloadSupportProviderTest {

    @Test
    fun rejectsMp3Offload() {
        val provider = MicaMp3OffloadSupportProvider(RuntimeEnvironment.getApplication())
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MPEG)
            .setSampleRate(44_100)
            .setChannelCount(2)
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        assertFalse(provider.getAudioOffloadSupport(format, audioAttributes).isFormatSupported)
    }
}
