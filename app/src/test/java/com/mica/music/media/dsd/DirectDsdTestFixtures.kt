package com.mica.music.media.dsd

import com.mica.music.media.usb.protocol.OutputTarget
import com.mica.music.media.usb.protocol.AdapterInstanceId
import com.mica.music.media.usb.protocol.CommitDisposition
import com.mica.music.media.usb.protocol.FamilyOwnership
import com.mica.music.media.usb.protocol.MutationId
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.RuntimeIdentity
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackAdapter
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackAdapterKind
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackCoordinator
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackStack
import com.mica.music.media.usb.protocol.installOwnedFamilyForModel

/** Required protocol adapter for renderer tests; no test renderer is constructed without one. */
internal fun testDirectPlaybackAdapter(): UsbExclusivePlaybackAdapter =
    UsbExclusivePlaybackCoordinator()
        .createStack(OutputTarget.SharedPcm)
        .newAdapter(UsbExclusivePlaybackAdapterKind.DIRECT_DOP)

internal fun testPcmPlaybackAdapter(): UsbExclusivePlaybackAdapter =
    UsbExclusivePlaybackCoordinator()
        .createStack(OutputTarget.SharedPcm)
        .newAdapter(UsbExclusivePlaybackAdapterKind.FFMPEG_PCM)

/** Pure protocol harness for renderer/sink tests; no transport or USB side effect is involved. */
internal class TestProtocolHarness private constructor(
    val stack: UsbExclusivePlaybackStack,
    val directAdapter: UsbExclusivePlaybackAdapter,
    val pcmAdapter: UsbExclusivePlaybackAdapter,
    private val sourceOccurrence: PlaybackOccurrence,
    private val sourceRuntime: RuntimeIdentity,
) {
    fun beginDestination(
        mediaId: String,
        occurrence: PlaybackOccurrence,
        family: PlaybackFamily,
        facts: String,
        playing: Boolean,
    ) {
        check(stack.publishSemanticIntent(playing))
        stack.observeTimelinePeriod(mediaId, occurrence.periodUid)
        stack.observeApplicationMedia(mediaId)
        check(stack.beginManualNavigation(mediaId, "test-destination") != null)
        val adapter = if (family == PlaybackFamily.PCM) pcmAdapter else directAdapter
        adapter.observeStream(occurrence, family, facts, stack.currentTopologyToken())
        stack.observeCurrentPlayerOccurrence(mediaId, occurrence)
        val mutation = stack.snapshot().mutation
        check(mutation?.destinationBound == true)
        check(mutation.targetOccurrence == occurrence)
    }

    fun releaseDirectSource() {
        val owned = stack.snapshot().familyOwnership as FamilyOwnership.DopOwned
        directAdapter.observeDirectRuntimeReleased(
            sourceOccurrence,
            sourceRuntime,
            "test-source-release",
        )
        check(owned.runtimeIdentity == sourceRuntime)
        check(stack.snapshot().familyOwnership is FamilyOwnership.None)
    }

    companion object {
        fun create(): TestProtocolHarness {
            val stack = UsbExclusivePlaybackCoordinator().createStack(OutputTarget.SharedPcm)
            val directAdapter = stack.newAdapter(UsbExclusivePlaybackAdapterKind.DIRECT_DOP)
            val pcmAdapter = stack.newAdapter(UsbExclusivePlaybackAdapterKind.FFMPEG_PCM)
            val sourceOccurrence = PlaybackOccurrence("period-A", 1L)
            val sourceRuntime = RuntimeIdentity("test-direct-runtime")
            val disposition = stack.protocol.installOwnedFamilyForModel(
                family = PlaybackFamily.DOP,
                mutationId = MutationId(1),
                adapterInstanceId = AdapterInstanceId(1),
                occurrence = sourceOccurrence,
                runtimeIdentity = sourceRuntime,
                facts = "dop-source",
            )
            check(disposition is CommitDisposition.CurrentPlaying || disposition is CommitDisposition.CurrentPaused)
            stack.protocol.updateApplicationCurrent("A", sourceOccurrence.periodUid, sourceOccurrence)
            return TestProtocolHarness(stack, directAdapter, pcmAdapter, sourceOccurrence, sourceRuntime)
        }
    }
}
