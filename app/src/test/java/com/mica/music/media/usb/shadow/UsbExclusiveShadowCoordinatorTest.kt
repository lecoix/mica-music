package com.mica.music.media.usb.shadow

import com.mica.music.media.usb.protocol.DirectStage
import com.mica.music.media.usb.protocol.FamilyOwnership
import com.mica.music.media.usb.protocol.MutationKind
import com.mica.music.media.usb.protocol.OutputTarget
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.protocol.PlaybackIntent
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.ProtocolLifecycle
import com.mica.music.media.usb.protocol.RuntimeIdentity
import com.mica.music.media.usb.UsbOutputGenerationObserverFanout
import com.mica.music.media.usb.protocol.UsbOutputGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveShadowCoordinatorTest {
    private val a = PlaybackOccurrence("period-a", 1)
    private val b = PlaybackOccurrence("period-b", 2)
    private val c = PlaybackOccurrence("period-c", 3)

    @Test
    fun serviceLedgerSurvivesStackRetirementAndNewStackAdoptsLatestSemanticIntent() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val old = coordinator.createStack()
        assertEquals(PlaybackIntent.PLAY, old.snapshot().adoptedIntent.desired)

        // A technical rebuild may retire the old stack, but only the application semantic seam
        // changes the service-lifetime ledger.
        coordinator.publishSemanticIntent(false)
        coordinator.retireStack(old)
        val replacement = coordinator.createStack()

        assertEquals(PlaybackIntent.PAUSE, replacement.snapshot().adoptedIntent.desired)
        assertTrue(old.snapshot().lifecycle !is ProtocolLifecycle.Active)
        assertNotEquals(old.snapshot().stackId, replacement.snapshot().stackId)
    }

    @Test
    fun manualMutationMintsUnboundBeforeDestinationAndCannotActivateUntilRawStreamBinds() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack()
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.PLATFORM_PCM)

        stack.observeApplicationMedia("B")
        stack.observeTimelinePeriod("B", b.periodUid)
        stack.observeManualNavigation("B", "test-select")
        val unbound = requireNotNull(stack.snapshot().mutation)
        assertEquals(MutationKind.MANUAL, unbound.kind)
        assertFalse(unbound.destinationBound)
        assertNull(
            stack.protocol.preparePcmConfigure(
                unbound.mutationId,
                adapter.id,
                b,
                "pcm96",
            ),
        )

        stack.observeCurrentPlayerOccurrence("B", b)
        adapter.observeStream(b, PlaybackFamily.PCM, "pcm96")
        val bound = requireNotNull(stack.snapshot().mutation)
        assertEquals(unbound.mutationId, bound.mutationId)
        assertTrue(bound.destinationBound)
        assertEquals(PlaybackFamily.PCM, bound.targetFamily)
        assertEquals("pcm96", bound.targetFacts)
        assertEquals(b, bound.targetOccurrence)
    }

    @Test
    fun supersedingUnboundManualMutationInvalidatesOlderEpochWithoutRelabelingIt() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack()
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.FFMPEG_PCM)

        stack.observeManualNavigation("B", "first")
        val first = requireNotNull(stack.snapshot().mutation)
        stack.observeManualNavigation("C", "second")
        val second = requireNotNull(stack.snapshot().mutation)
        assertNotEquals(first.mutationId, second.mutationId)
        assertFalse(second.destinationBound)

        adapter.observeStream(b, PlaybackFamily.PCM, "pcm-b")
        assertFalse(requireNotNull(stack.snapshot().mutation).destinationBound)
        stack.observeTimelinePeriod("C", c.periodUid)
        adapter.observeStream(c, PlaybackFamily.PCM, "pcm-c")
        val current = requireNotNull(stack.snapshot().mutation)
        assertEquals(second.mutationId, current.mutationId)
        assertEquals(c, current.targetOccurrence)
        assertEquals("pcm-c", current.targetFacts)
    }

    @Test
    fun readAheadIsCandidateOnlyUntilApplicationAndExactPlayerOccurrenceAgree() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack()
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.FFMPEG_DSD_PCM)

        stack.observeTimelinePeriod("B", b.periodUid)
        stack.observeTimelinePeriod("C", c.periodUid)
        adapter.observeStream(b, PlaybackFamily.PCM, "pcm-b")
        assertNull(stack.snapshot().mutation)
        assertEquals(1, stack.snapshot().candidates.size)

        stack.observeApplicationMedia("B")
        stack.observeCurrentPlayerOccurrence("B", b)
        val bMutation = requireNotNull(stack.snapshot().mutation)
        assertEquals(MutationKind.AUTO_NEXT, bMutation.kind)
        assertEquals(b, bMutation.targetOccurrence)

        adapter.observeStream(c, PlaybackFamily.PCM, "pcm-c")
        assertEquals(bMutation.mutationId, requireNotNull(stack.snapshot().mutation).mutationId)
        stack.observeApplicationMedia("C")
        stack.observeCurrentPlayerOccurrence("C", c)
        val cMutation = requireNotNull(stack.snapshot().mutation)
        assertNotEquals(bMutation.mutationId, cMutation.mutationId)
        assertEquals(c, cMutation.targetOccurrence)
    }

    @Test
    fun actualRendererRolesReceiveDistinctAdapterIdentities() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        val stack = coordinator.createStack()
        val adapters = listOf(
            stack.newAdapter(UsbExclusiveShadowAdapterKind.PLATFORM_PCM),
            stack.newAdapter(UsbExclusiveShadowAdapterKind.FFMPEG_PCM),
            stack.newAdapter(UsbExclusiveShadowAdapterKind.FFMPEG_DSD_PCM),
            stack.newAdapter(UsbExclusiveShadowAdapterKind.DIRECT_DOP),
        )
        assertEquals(4, adapters.map { it.id }.toSet().size)
    }

    @Test
    fun pcmLegacyCompletionOnlyAdvancesAnExactPreexistingShadowActivation() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack()
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.PLATFORM_PCM)
        stack.observeTimelinePeriod("B", b.periodUid)
        stack.observeApplicationMedia("B")
        stack.observeManualNavigation("B", "select")
        stack.observeCurrentPlayerOccurrence("B", b)
        adapter.observeStream(b, PlaybackFamily.PCM, "pcm96")

        adapter.observePcmConfigureAttempt(b, "raw-sink-format")
        assertEquals(1, stack.snapshot().inFlightActivations.size)
        adapter.observePcmConfigureCompleted(b)
        assertTrue(stack.snapshot().familyOwnership is FamilyOwnership.PcmOwned)

        val noActivationStack = coordinator.createStack()
        val noActivationAdapter = noActivationStack.newAdapter(UsbExclusiveShadowAdapterKind.PLATFORM_PCM)
        noActivationAdapter.observePcmConfigureCompleted(a)
        assertTrue(noActivationStack.snapshot().familyOwnership is FamilyOwnership.None)
        assertTrue(
            coordinator.diagnosticsSnapshot().any {
                it.rawEventKind == "PCM_CONFIGURE_COMPLETED" &&
                    it.decision == UsbExclusiveShadowDecision.DIVERGENCE
            },
        )
    }

    @Test
    fun candidateOutputIsLocalUntilPublicationAndRealUsbGenerationDoesNotRewriteSharedStack() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        val publishedShared = coordinator.createStack(OutputTarget.SharedPcm)
        coordinator.publishStack(publishedShared)
        val usbCandidate = coordinator.createStack(OutputTarget.Unavailable)

        assertEquals(OutputTarget.SharedPcm, publishedShared.snapshot().outputTarget)
        assertEquals(OutputTarget.Unavailable, usbCandidate.snapshot().outputTarget)

        coordinator.observeUsbGeneration(42)
        assertEquals(OutputTarget.SharedPcm, publishedShared.snapshot().outputTarget)
        assertEquals(OutputTarget.UsbBound(UsbOutputGeneration(42)), usbCandidate.snapshot().outputTarget)
        assertEquals(OutputTarget.SharedPcm, coordinator.createStack().snapshot().outputTarget)

        coordinator.retireStack(publishedShared)
        coordinator.publishStack(usbCandidate)
        assertEquals(
            OutputTarget.UsbBound(UsbOutputGeneration(42)),
            coordinator.createStack().snapshot().outputTarget,
        )
    }

    @Test
    fun outputGenerationUsesObservedP2ValueAndRetiringStackRejectsNewAuthority() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val old = coordinator.createStack(OutputTarget.Unavailable)
        coordinator.publishStack(old)
        coordinator.observeUsbGeneration(37)
        assertEquals(OutputTarget.UsbBound(UsbOutputGeneration(37)), old.snapshot().outputTarget)

        coordinator.retireStack(old)
        old.observeManualNavigation("B", "after-retire")
        assertNull(old.snapshot().mutation)

        val replacement = coordinator.createStack()
        assertEquals(OutputTarget.UsbBound(UsbOutputGeneration(37)), replacement.snapshot().outputTarget)
        assertEquals(PlaybackIntent.PLAY, replacement.snapshot().adoptedIntent.desired)
    }

    @Test
    fun directSeekSourceAcceptRequiresExactRawResetBarrierAndObservedRuntimeRelease() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack(OutputTarget.UsbBound(UsbOutputGeneration(5)))
        coordinator.publishStack(stack)
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.DIRECT_DOP)
        val sourceRuntime = RuntimeIdentity("direct-source")

        stack.observeTimelinePeriod("A", a.periodUid)
        stack.observeApplicationMedia("A")
        stack.observeManualNavigation("A", "seed-direct")
        stack.observeCurrentPlayerOccurrence("A", a)
        adapter.observeStream(a, PlaybackFamily.DOP, "dop128")
        adapter.observeDirectStage(a, DirectStage.CREATE_RUNTIME, sourceRuntime, completed = true)
        adapter.observeDirectStage(a, DirectStage.PREFILL, sourceRuntime, completed = true)
        adapter.observeDirectStarted(a)
        adapter.observeDirectStage(a, DirectStage.ARM, sourceRuntime, completed = true)
        adapter.observeDirectStage(a, DirectStage.SOURCE_ACCEPT, sourceRuntime, completed = true)
        assertTrue(stack.snapshot().familyOwnership is FamilyOwnership.DopOwned)

        stack.observeSeekDispatch(123_000L)
        val seek = requireNotNull(stack.snapshot().mutation)
        assertEquals(MutationKind.SEEK, seek.kind)
        adapter.observeDirectPositionReset(a, 124_000L)
        adapter.observeDirectRuntimeReleased(a, sourceRuntime, "position-reset")
        assertNotNull(stack.snapshot().mutation?.sourceRetirement)

        val seekRuntime = RuntimeIdentity("direct-seek")
        adapter.observeDirectStage(a, DirectStage.CREATE_RUNTIME, seekRuntime, completed = true)
        adapter.observeDirectStage(a, DirectStage.PREFILL, seekRuntime, completed = true)
        adapter.observeDirectStarted(a)
        adapter.observeDirectStage(a, DirectStage.ARM, seekRuntime, completed = true)
        adapter.observeDirectStage(a, DirectStage.SOURCE_ACCEPT, seekRuntime, completed = true)
        assertTrue(stack.snapshot().familyOwnership is FamilyOwnership.None)
        assertTrue(
            coordinator.diagnosticsSnapshot().any {
                it.rawEventKind == "DIRECT_SOURCE_ACCEPT" &&
                    it.decision == UsbExclusiveShadowDecision.WOULD_DEFER
            },
        )

        adapter.observeDirectPositionReset(a, 123_000L)
        adapter.observeDirectStage(a, DirectStage.SOURCE_ACCEPT, seekRuntime, completed = true)
        assertTrue(stack.snapshot().familyOwnership is FamilyOwnership.DopOwned)
    }

    @Test
    fun generationFanoutPublishesNativeExactlyOnceBeforeObserversAndSurvivesObserverFailure() {
        val order = mutableListOf<String>()
        val failures = mutableListOf<Long>()
        val fanout = UsbOutputGenerationObserverFanout { generation, _ -> failures += generation }
        fanout.installPublisher { generation -> order += "native:$generation" }
        fanout.installObserver { generation ->
            order += "observer-a:$generation"
            error("observer-a-failure")
        }
        fanout.installObserver { generation -> order += "observer-b:$generation" }

        fanout.publish(41)

        assertEquals(listOf("native:41", "observer-a:41", "observer-b:41"), order)
        assertEquals(listOf(41L), failures)
        assertEquals(1, order.count { it == "native:41" })
    }

    @Test
    fun diagnosticSinkFailureNeverEscapesAnyObservationBoundary() {
        val coordinator = UsbExclusiveShadowCoordinator { error("diagnostic-failure") }
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack()
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.PLATFORM_PCM)
        stack.observeManualNavigation("B", "exception-isolation")
        stack.observeTimelinePeriod("B", b.periodUid)
        stack.observeApplicationMedia("B")
        stack.observeCurrentPlayerOccurrence("B", b)
        adapter.observeStream(b, PlaybackFamily.PCM, "pcm96")
        assertNotNull(stack.snapshot().mutation)
    }
}
