package com.mica.music.media.usb.shadow

import com.mica.music.media.usb.protocol.DirectStage
import com.mica.music.media.usb.protocol.CommitDisposition
import com.mica.music.media.usb.protocol.FamilyOwnership
import com.mica.music.media.usb.protocol.MutationKind
import com.mica.music.media.usb.protocol.OutputTarget
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.protocol.PlaybackIntent
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.ProtocolLifecycle
import com.mica.music.media.usb.protocol.ResourceIdentity
import com.mica.music.media.usb.protocol.RuntimeIdentity
import com.mica.music.media.usb.protocol.SideEffectReceipt
import com.mica.music.media.usb.protocol.WriteKind
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
        assertFalse(old.publishSemanticIntent(true))
        assertEquals(PlaybackIntent.PAUSE, coordinator.ledger.snapshot().desired)
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
    fun retiringDopOwnedStackWaitsForExactDirectRuntimeReleaseBeforeRetired() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        val runtime = RuntimeIdentity("direct-retiring")
        val (stack, adapter) = committedDirectStack(coordinator, runtime)

        coordinator.retireStack(stack)

        assertTrue(stack.snapshot().lifecycle is ProtocolLifecycle.Retiring)
        assertTrue(stack.snapshot().familyOwnership is FamilyOwnership.DopOwned)

        adapter.observeDirectRuntimeReleased(a, runtime, "stack-teardown")

        assertEquals(ProtocolLifecycle.Retired, stack.snapshot().lifecycle)
        assertEquals(FamilyOwnership.None, stack.snapshot().familyOwnership)
    }

    @Test
    fun retiringDopOwnedStackUsesCommittedSourceIdentityWithDestinationBoundSuccessor() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        val runtime = RuntimeIdentity("direct-successor-source")
        val (stack, adapter) = committedDirectStack(coordinator, runtime)

        stack.observeTimelinePeriod("B", b.periodUid)
        stack.observeApplicationMedia("B")
        stack.observeManualNavigation("B", "successor")
        stack.observeCurrentPlayerOccurrence("B", b)
        adapter.observeStream(b, PlaybackFamily.DOP, "dop256")
        val successor = requireNotNull(stack.snapshot().mutation)
        assertTrue(successor.destinationBound)
        assertEquals(b, successor.targetOccurrence)

        coordinator.retireStack(stack)
        assertTrue(stack.snapshot().lifecycle is ProtocolLifecycle.Retiring)

        adapter.observeDirectRuntimeReleased(a, runtime, "successor-teardown")

        assertEquals(ProtocolLifecycle.Retired, stack.snapshot().lifecycle)
        assertEquals(FamilyOwnership.None, stack.snapshot().familyOwnership)
    }

    @Test
    fun wrongDirectRuntimeReleaseEvidenceCannotRetireDopOwnedStack() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        val runtime = RuntimeIdentity("direct-exact-source")
        val (stack, adapter) = committedDirectStack(coordinator, runtime)
        val wrongAdapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.DIRECT_DOP)

        coordinator.retireStack(stack)
        wrongAdapter.observeDirectRuntimeReleased(a, runtime, "wrong-adapter")
        adapter.observeDirectRuntimeReleased(b, runtime, "wrong-occurrence")
        adapter.observeDirectRuntimeReleased(a, RuntimeIdentity("wrong-runtime"), "wrong-runtime")

        assertTrue(stack.snapshot().lifecycle is ProtocolLifecycle.Retiring)
        assertTrue(stack.snapshot().familyOwnership is FamilyOwnership.DopOwned)
        assertTrue(
            coordinator.diagnosticsSnapshot().count {
                it.rawEventKind == "DIRECT_RUNTIME_RELEASED" &&
                    it.decision == UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE
            } >= 3,
        )

        adapter.observeDirectRuntimeReleased(a, runtime, "exact-release")

        assertEquals(ProtocolLifecycle.Retired, stack.snapshot().lifecycle)
    }

    @Test
    fun exactDirectReleaseBeforeLeaseDrainWaitsAndThenRetires() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        val runtime = RuntimeIdentity("direct-release-before-drain")
        val (stack, adapter) = committedDirectStack(coordinator, runtime)
        val owned = stack.snapshot().familyOwnership as FamilyOwnership.DopOwned
        assertTrue(
            owned.writeLease.tryEnter(
                owned.occurrence,
                owned.mutationId,
                owned.adapterInstanceId,
                WriteKind.DOP_CONTENT,
            ),
        )

        coordinator.retireStack(stack)
        adapter.observeDirectRuntimeReleased(a, runtime, "release-before-drain")

        assertTrue(stack.snapshot().lifecycle is ProtocolLifecycle.Retiring)
        assertTrue(stack.snapshot().familyOwnership is FamilyOwnership.DopOwned)

        owned.writeLease.exit()

        assertEquals(ProtocolLifecycle.Retired, stack.snapshot().lifecycle)
        assertEquals(FamilyOwnership.None, stack.snapshot().familyOwnership)
    }

    @Test
    fun productionRetainedDirectHandoffRequiresExactSourceAndCommitsOneSuccessorLease() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack(OutputTarget.UsbBound(UsbOutputGeneration(8)))
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.DIRECT_DOP)
        val wrongAdapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.DIRECT_DOP)
        val runtime = RuntimeIdentity("direct-retained-production")

        bindManualDirectDestination(stack, adapter, "A", a, "dop128", runtime)
        val sourceOwnership = stack.snapshot().familyOwnership as FamilyOwnership.DopOwned

        stack.observeTimelinePeriod("B", b.periodUid)
        stack.observeApplicationMedia("B")
        requireNotNull(stack.beginManualNavigation("B", "production-retained"))
        stack.observeCurrentPlayerOccurrence("B", b)
        adapter.observeStream(b, PlaybackFamily.DOP, "dop256")

        assertNull(adapter.prepareRetainedDirectHandoff(b, b, runtime))
        assertNull(wrongAdapter.prepareRetainedDirectHandoff(a, b, runtime))
        assertNull(adapter.prepareRetainedDirectHandoff(a, b, RuntimeIdentity("wrong-runtime")))

        val permit = requireNotNull(adapter.prepareRetainedDirectHandoff(a, b, runtime))
        assertTrue(sourceOwnership.writeLease.isRevoked())
        val disposition = adapter.commitRetainedDirectHandoff(
            permit,
            SideEffectReceipt.Completed(
                permit.activationId,
                ResourceIdentity("direct-retained-reset-b"),
                "test-retained-reset",
                runtime,
            ),
        )

        assertTrue(disposition is CommitDisposition.CurrentPlaying)
        val successor = stack.snapshot().familyOwnership as FamilyOwnership.DopOwned
        assertEquals(b, successor.occurrence)
        assertEquals(adapter.id, successor.adapterInstanceId)
        assertEquals(runtime, successor.runtimeIdentity)
        assertNotEquals(sourceOwnership.ownershipId, successor.ownershipId)
        assertTrue(successor.writeLease.tryEnter(b, successor.mutationId, adapter.id, WriteKind.DOP_CONTENT))
        successor.writeLease.exit()
    }

    @Test
    fun productionRetainedDirectPermitBecomesStaleAfterBSupersedeAndCUsesItsOwnExactHandoff() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack(OutputTarget.UsbBound(UsbOutputGeneration(9)))
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.DIRECT_DOP)
        val runtime = RuntimeIdentity("direct-retained-superseded")

        bindManualDirectDestination(stack, adapter, "A", a, "dop128", runtime)
        stack.observeTimelinePeriod("B", b.periodUid)
        stack.observeApplicationMedia("B")
        requireNotNull(stack.beginManualNavigation("B", "B"))
        stack.observeCurrentPlayerOccurrence("B", b)
        adapter.observeStream(b, PlaybackFamily.DOP, "dop256")
        val bPermit = requireNotNull(adapter.prepareRetainedDirectHandoff(a, b, runtime))

        stack.observeTimelinePeriod("C", c.periodUid)
        stack.observeApplicationMedia("C")
        requireNotNull(stack.beginManualNavigation("C", "C"))
        stack.observeCurrentPlayerOccurrence("C", c)
        adapter.observeStream(c, PlaybackFamily.DOP, "dop512")

        assertEquals(
            CommitDisposition.StaleNoEffect,
            adapter.commitRetainedDirectHandoff(
                bPermit,
                SideEffectReceipt.Completed(
                    bPermit.activationId,
                    ResourceIdentity("direct-retained-reset-b-stale"),
                    "test-stale-B",
                    runtime,
                ),
            ),
        )
        assertTrue(stack.snapshot().familyOwnership is FamilyOwnership.DopOwned)
        assertEquals(a, (stack.snapshot().familyOwnership as FamilyOwnership.DopOwned).occurrence)
        val cPermit = requireNotNull(adapter.prepareRetainedDirectHandoff(a, c, runtime))
        assertNotEquals(bPermit.activationId, cPermit.activationId)
        assertTrue(
            adapter.commitRetainedDirectHandoff(
                cPermit,
                SideEffectReceipt.Completed(
                    cPermit.activationId,
                    ResourceIdentity("direct-retained-reset-c"),
                    "test-C",
                    runtime,
                ),
            ) is CommitDisposition.CurrentPlaying,
        )
        assertEquals(c, (stack.snapshot().familyOwnership as FamilyOwnership.DopOwned).occurrence)
    }

    @Test
    fun productionRetainedPcmHandoffRequiresRawTargetProofAndOldLeaseDrain() {
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack()
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.FFMPEG_PCM)

        stack.observeTimelinePeriod("A", a.periodUid)
        stack.observeApplicationMedia("A")
        requireNotNull(stack.beginManualNavigation("A", "pcm-seed"))
        stack.observeCurrentPlayerOccurrence("A", a)
        adapter.observeStream(a, PlaybackFamily.PCM, "pcm96")
        val configure = requireNotNull(adapter.preparePcmConfigure(a, "sink-format"))
        assertTrue(
            adapter.commitPcmConfigure(
                configure,
                a,
                ResourceIdentity("pcm-runtime"),
                "sink-configured",
            ) is CommitDisposition.CurrentPlaying,
        )
        val source = stack.snapshot().familyOwnership as FamilyOwnership.PcmOwned
        assertTrue(source.writeLease.tryEnter(a, source.mutationId, adapter.id, WriteKind.PCM_DATA))

        stack.observeTimelinePeriod("B", b.periodUid)
        stack.observeApplicationMedia("B")
        requireNotNull(stack.beginManualNavigation("B", "pcm-retained"))
        stack.observeCurrentPlayerOccurrence("B", b)
        adapter.observeStream(b, PlaybackFamily.PCM, "pcm96")
        assertNull(adapter.prepareRetainedPcmHandoff(c))
        assertNull(adapter.prepareRetainedPcmHandoff(b))
        assertTrue(source.writeLease.isRevoked())

        source.writeLease.exit()
        val permit = requireNotNull(adapter.prepareRetainedPcmHandoff(b))
        val disposition = adapter.commitRetainedPcmHandoff(permit)
        assertTrue(disposition is CommitDisposition.CurrentPlaying)
        val successor = stack.snapshot().familyOwnership as FamilyOwnership.PcmOwned
        assertEquals(b, successor.occurrence)
        assertNotEquals(source.ownershipId, successor.ownershipId)
        assertTrue(successor.writeLease.tryEnter(b, successor.mutationId, adapter.id, WriteKind.PCM_DATA))
        successor.writeLease.exit()
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

    private fun committedDirectStack(
        coordinator: UsbExclusiveShadowCoordinator,
        runtime: RuntimeIdentity,
    ): Pair<UsbExclusiveShadowStack, UsbExclusiveShadowAdapter> {
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack(OutputTarget.UsbBound(UsbOutputGeneration(5)))
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.DIRECT_DOP)

        stack.observeTimelinePeriod("A", a.periodUid)
        stack.observeApplicationMedia("A")
        stack.observeManualNavigation("A", "seed-direct")
        stack.observeCurrentPlayerOccurrence("A", a)
        adapter.observeStream(a, PlaybackFamily.DOP, "dop128")
        adapter.observeDirectStage(a, DirectStage.CREATE_RUNTIME, runtime, completed = true)
        adapter.observeDirectStage(a, DirectStage.PREFILL, runtime, completed = true)
        adapter.observeDirectStarted(a)
        adapter.observeDirectStage(a, DirectStage.ARM, runtime, completed = true)
        adapter.observeDirectStage(a, DirectStage.SOURCE_ACCEPT, runtime, completed = true)
        check(stack.snapshot().familyOwnership is FamilyOwnership.DopOwned)
        return stack to adapter
    }

    private fun bindManualDirectDestination(
        stack: UsbExclusiveShadowStack,
        adapter: UsbExclusiveShadowAdapter,
        mediaId: String,
        occurrence: PlaybackOccurrence,
        facts: String,
        runtime: RuntimeIdentity,
    ) {
        stack.observeTimelinePeriod(mediaId, occurrence.periodUid)
        stack.observeApplicationMedia(mediaId)
        requireNotNull(stack.beginManualNavigation(mediaId, "production-seed"))
        stack.observeCurrentPlayerOccurrence(mediaId, occurrence)
        adapter.observeStream(occurrence, PlaybackFamily.DOP, facts)
        for (stage in listOf(DirectStage.CREATE_RUNTIME, DirectStage.PREFILL)) {
            val permit = requireNotNull(adapter.prepareDirectStage(occurrence, stage, runtime))
            assertNull(
                adapter.commitDirectStage(
                    permit,
                    SideEffectReceipt.Completed(
                        permit.activationId,
                        ResourceIdentity("$runtime-${stage.name.lowercase()}"),
                        "test-${stage.name.lowercase()}",
                        runtime,
                    ),
                ),
            )
        }
        assertTrue(adapter.acceptDirectStarted(occurrence))
        val arm = requireNotNull(adapter.prepareDirectStage(occurrence, DirectStage.ARM, runtime))
        assertNull(
            adapter.commitDirectStage(
                arm,
                SideEffectReceipt.Completed(
                    arm.activationId,
                    ResourceIdentity("$runtime-arm"),
                    "test-arm",
                    runtime,
                ),
            ),
        )
        val source = requireNotNull(adapter.prepareDirectStage(occurrence, DirectStage.SOURCE_ACCEPT, runtime))
        assertTrue(
            adapter.commitDirectStage(
                source,
                SideEffectReceipt.Completed(
                    source.activationId,
                    ResourceIdentity("$runtime-source"),
                    "test-source",
                    runtime,
                ),
            ) is CommitDisposition.CurrentPlaying,
        )
    }
}
