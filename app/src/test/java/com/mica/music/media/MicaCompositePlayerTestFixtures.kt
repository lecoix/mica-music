package com.mica.music.media

import com.mica.music.media.usb.protocol.OutputTarget
import com.mica.music.media.usb.protocol.AdapterInstanceId
import com.mica.music.media.usb.protocol.CommitDisposition
import com.mica.music.media.usb.protocol.MutationId
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.RuntimeIdentity
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackCoordinator
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackStack
import com.mica.music.media.usb.protocol.installOwnedFamilyForModel

/** Test-only required dependency with a real committed owner for SEEK/order assertions. */
internal fun testPlaybackStack(): UsbExclusivePlaybackStack {
    val stack = UsbExclusivePlaybackCoordinator().createStack(OutputTarget.SharedPcm)
    val adapterId = AdapterInstanceId(1)
    val occurrence = PlaybackOccurrence("test-period", 1L)
    val disposition = stack.protocol.installOwnedFamilyForModel(
        family = PlaybackFamily.PCM,
        mutationId = MutationId(1),
        adapterInstanceId = adapterId,
        occurrence = occurrence,
        runtimeIdentity = RuntimeIdentity("pcm:test-runtime"),
    )
    check(disposition is CommitDisposition.CurrentPlaying || disposition is CommitDisposition.CurrentPaused)
    stack.protocol.updateApplicationCurrent("test-current", occurrence.periodUid, occurrence)
    return stack
}
