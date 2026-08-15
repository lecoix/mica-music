package com.mica.music.media.usb.shadow

import androidx.media3.common.Format
import androidx.media3.exoplayer.source.MediaSource
import com.mica.music.media.dsd.ManualNavigationPlaybackIdentity
import com.mica.music.media.usb.protocol.PlaybackOccurrence

internal object UsbExclusiveShadowMedia3Facts {
    fun occurrence(mediaPeriodId: MediaSource.MediaPeriodId): PlaybackOccurrence =
        PlaybackOccurrence(mediaPeriodId.periodUid, mediaPeriodId.windowSequenceNumber)

    fun occurrence(identity: ManualNavigationPlaybackIdentity?): PlaybackOccurrence? =
        identity?.let { PlaybackOccurrence(it.periodUid, it.windowSequenceNumber) }

    fun audio(format: Format?, role: String): String {
        if (format == null) return "role=$role;format=unknown"
        return buildString {
            append("role=").append(role)
            append(";mime=").append(format.sampleMimeType ?: "unknown")
            append(";sr=").append(format.sampleRate)
            append(";ch=").append(format.channelCount)
            append(";pcm=").append(format.pcmEncoding)
            if (format.codecs != null) append(";codec=").append(format.codecs)
        }
    }
}
