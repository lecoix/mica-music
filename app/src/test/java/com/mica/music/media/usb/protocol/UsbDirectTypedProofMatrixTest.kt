package com.mica.music.media.usb.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class UsbDirectTypedProofMatrixTest {
    private val a = PlaybackOccurrence("uid-a", 1)
    private val b = PlaybackOccurrence("uid-b", 2)
    private val adapterA = AdapterInstanceId(10)
    private val adapterB = AdapterInstanceId(20)

    @Test
    fun t1FullReleaseGreenClearsDopOwnershipOnce() {
        val (_, protocol) = ownedDirect()
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "B",
                PlaybackFamily.DOP,
                "dop256",
                b,
                destinationAdapterInstanceId = adapterB,
            ),
        )
        assertTrue(protocol.completeOwnedDirectRelease(adapterA, RuntimeIdentity("direct-a")))
        assertEquals(FamilyOwnership.None, protocol.snapshot().familyOwnership)
        assertFalse(protocol.completeOwnedDirectRelease(adapterA, RuntimeIdentity("direct-a")))
    }

    @Test
    fun t3ForgedDirectProofsCannotMintOrAccept() {
        val (_, protocol) = ownedDirect()
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "B",
                PlaybackFamily.DOP,
                "dop256",
                b,
                destinationAdapterInstanceId = adapterB,
            ),
        )
        assertNull(
            protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapterA,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
                FamilyProof.StackReleased("closed"),
            ),
        )
        assertNull(
            protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapterA,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
                protocol.typedDirectReleased(RuntimeIdentity("forged-runtime"), a, adapterA),
            ),
        )
        assertNull(
            protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapterA,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
                protocol.typedDirectReleased(RuntimeIdentity("direct-a"), b, adapterA),
            ),
        )
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.DopOwned)
    }

    @Test
    fun t4RetainedGreenProofCommitsSuccessorBeforeOwnershipSwap() {
        val (_, protocol) = ownedDirect()
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "B",
                PlaybackFamily.DOP,
                "dop256",
                b,
                destinationAdapterInstanceId = adapterA,
            ),
        )
        val permit = requireNotNull(
            protocol.prepareRetainedDirectHandoff(
                mutation.mutationId,
                adapterA,
                a,
                b,
                RuntimeIdentity("direct-a"),
            ),
        )
        val disposition = protocol.commitRetainedDirectHandoff(
            permit,
            SideEffectReceipt.Completed(
                permit.activationId,
                ResourceIdentity("retained-b"),
                "reset",
                RuntimeIdentity("direct-a"),
            ),
        )
        assertTrue(disposition is CommitDisposition.CurrentPlaying)
        val owned = protocol.snapshot().familyOwnership as FamilyOwnership.DopOwned
        assertEquals(b, owned.occurrence)
        assertEquals(RuntimeIdentity("direct-a"), owned.runtimeIdentity)
    }

    @Test
    fun t5RetainedRedOrMissingProofDoesNotCommitSuccessor() {
        val endpoint = FakeDirectPhysicalEndpoint(retained = null)
        val (_, protocol) = ownedDirect(endpoint)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "B",
                PlaybackFamily.DOP,
                "dop256",
                b,
                destinationAdapterInstanceId = adapterA,
            ),
        )
        val permit = requireNotNull(
            protocol.prepareRetainedDirectHandoff(
                mutation.mutationId,
                adapterA,
                a,
                b,
                RuntimeIdentity("direct-a"),
            ),
        )
        assertEquals(
            CommitDisposition.StaleNoEffect,
            protocol.commitRetainedDirectHandoff(
                permit,
                SideEffectReceipt.Completed(
                    permit.activationId,
                    ResourceIdentity("retained-missing"),
                    "reset",
                    RuntimeIdentity("direct-a"),
                ),
            ),
        )
        endpoint.retained = greenDirectRetainedFacts(markerContinuityRetained = false)
        assertEquals(
            CommitDisposition.StaleNoEffect,
            protocol.commitRetainedDirectHandoff(
                permit,
                SideEffectReceipt.Completed(
                    permit.activationId,
                    ResourceIdentity("retained-red"),
                    "reset",
                    RuntimeIdentity("direct-a"),
                ),
            ),
        )
        assertEquals(a, (protocol.snapshot().familyOwnership as FamilyOwnership.DopOwned).occurrence)
    }

    @Test
    fun t6SeekPositionMatchIsPendingOnlyUntilPhysicalProof() {
        val (_, protocol) = seekSetup()
        assertTrue(protocol.notePendingDirectSeekReset(adapterA, a, 9_000_000L))
        assertTrue(protocol.snapshot().pendingDirectSeekReset)
        assertFalse(protocol.snapshot().seekCarrierBarrierSatisfied)
        val mutation = requireNotNull(protocol.snapshot().mutation)
        assertNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterA,
                a,
                DirectStage.SOURCE_ACCEPT,
                RuntimeIdentity("seek-runtime"),
            ),
        )
    }

    @Test
    fun t7TypedOldRuntimeProofSatisfiesSeekBarrierThenSourceAccept() {
        val (_, protocol) = seekSetup()
        assertTrue(protocol.notePendingDirectSeekReset(adapterA, a, 9_000_000L))
        assertTrue(protocol.completeOwnedDirectRelease(adapterA, RuntimeIdentity("direct-a")))
        assertTrue(protocol.snapshot().seekCarrierBarrierSatisfied)
        assertFalse(protocol.snapshot().pendingDirectSeekReset)
        val mutation = requireNotNull(protocol.snapshot().mutation)
        val create = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterA,
                a,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("seek-runtime"),
            ),
        )
        protocol.commitDirectStage(create, completedDirect(create, "seek-create"))
        val prefill = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterA,
                a,
                DirectStage.PREFILL,
                RuntimeIdentity("seek-runtime"),
            ),
        )
        protocol.commitDirectStage(prefill, completedDirect(prefill, "seek-prefill"))
        protocol.observeAdapterStarted(adapterA, a)
        val arm = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterA,
                a,
                DirectStage.ARM,
                RuntimeIdentity("seek-runtime"),
            ),
        )
        protocol.commitDirectStage(arm, completedDirect(arm, "seek-arm"))
        val accept = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterA,
                a,
                DirectStage.SOURCE_ACCEPT,
                RuntimeIdentity("seek-runtime"),
            ),
        )
        assertTrue(protocol.redeemDirectStage(accept) != null)
        val playing = protocol.commitDirectStage(accept, completedDirect(accept, "seek-accept"))
        assertTrue(playing is CommitDisposition.CurrentPlaying)
    }

    @Test
    fun t8PrefillPauseBeforeIoDeniesTemporalRedeem() {
        val (ledger, protocol) = freshDirectDestination()
        val mutation = requireNotNull(protocol.snapshot().mutation)
        val create = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("prefill-runtime"),
            ),
        )
        protocol.commitDirectStage(create, completedDirect(create, "create"))
        val prefill = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.PREFILL,
                RuntimeIdentity("prefill-runtime"),
            ),
        )
        assertNotNull(protocol.redeemDirectStage(prefill))
        ledger.publish(PlaybackIntent.PAUSE)
        protocol.adoptLatestIntent()
        assertNull(protocol.redeemDirectStage(prefill))
    }

    @Test
    fun t9PrefillPartialEffectRequiresCleanupBeforeRetry() {
        val (_, protocol) = freshDirectDestination()
        val mutation = requireNotNull(protocol.snapshot().mutation)
        val create = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("prefill-runtime"),
            ),
        )
        protocol.commitDirectStage(create, completedDirect(create, "create"))
        val prefill = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.PREFILL,
                RuntimeIdentity("prefill-runtime"),
            ),
        )
        val resource = ResourceIdentity("prefill-partial")
        val disposition = protocol.commitDirectStage(
            prefill,
            SideEffectReceipt.PartialNeedsCleanup(
                prefill.activationId,
                resource,
                "partial",
                prefill.runtimeIdentity,
            ),
        )
        assertTrue(disposition is CommitDisposition.CurrentCleanupRequired)
        assertTrue(protocol.snapshot().cleanupRequirements.isNotEmpty())
        assertNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.PREFILL,
                RuntimeIdentity("prefill-runtime"),
            ),
        )
    }

    @Test
    fun t10ArmPauseBeforePhysicalArmDeniesRedeemAndStaleCommitCleans() {
        val (ledger, protocol) = freshDirectDestination()
        val mutation = requireNotNull(protocol.snapshot().mutation)
        val runtime = RuntimeIdentity("arm-runtime")
        val create = requireNotNull(
            protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.CREATE_RUNTIME, runtime),
        )
        protocol.commitDirectStage(create, completedDirect(create, "create"))
        val prefill = requireNotNull(
            protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.PREFILL, runtime),
        )
        protocol.commitDirectStage(prefill, completedDirect(prefill, "prefill"))
        protocol.observeAdapterStarted(adapterB, b)
        val arm = requireNotNull(
            protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.ARM, runtime),
        )
        ledger.publish(PlaybackIntent.PAUSE)
        protocol.adoptLatestIntent()
        assertNull(protocol.redeemDirectStage(arm))
        val disposition = protocol.commitDirectStage(arm, completedDirect(arm, "arm-after-pause"))
        assertTrue(
            disposition is CommitDisposition.CurrentCleanupRequired ||
                disposition is CommitDisposition.StaleCleanupRequired,
        )
    }

    @Test
    fun t11StageRedeemDoesNotMintFamilyProofOrReplaceP2Fence() {
        val (_, protocol) = freshDirectDestination()
        val mutation = requireNotNull(protocol.snapshot().mutation)
        val create = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("p2-runtime"),
            ),
        )
        protocol.commitDirectStage(create, completedDirect(create, "create"))
        val prefill = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.PREFILL,
                RuntimeIdentity("p2-runtime"),
            ),
        )
        assertNotNull(protocol.redeemDirectStage(prefill))
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.None)
        assertNull(
            protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapterB,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
                protocol.typedDirectReleased(RuntimeIdentity("p2-runtime"), b, adapterB),
            ),
        )
        assertFalse(protocol.completeOwnedDirectRelease(adapterB, RuntimeIdentity("p2-runtime")))
    }

    @Test
    fun t12WrongRuntimeReleaseCannotSatisfySeekBarrierOrPublicMint() {
        val (_, protocol) = seekSetup()
        assertTrue(protocol.notePendingDirectSeekReset(adapterA, a, 9_000_000L))
        assertFalse(protocol.completeOwnedDirectRelease(adapterA, RuntimeIdentity("wrong-runtime")))
        assertFalse(protocol.snapshot().seekCarrierBarrierSatisfied)
        assertTrue(protocol.snapshot().pendingDirectSeekReset)
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.DopOwned)
        val mutation = requireNotNull(protocol.snapshot().mutation)
        assertNull(
            protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapterA,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
                protocol.typedDirectReleased(RuntimeIdentity("direct-a"), a, adapterA),
            ),
        )
    }

    @Test
    fun t13ReplacementEndpointCannotOverwriteBoundRuntime() {
        val (_, protocol) = ownedDirect()
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "B",
                PlaybackFamily.DOP,
                "dop256",
                b,
                destinationAdapterInstanceId = adapterB,
            ),
        )
        assertFalse(
            protocol.attachDirectPhysicalEndpoint(
                adapterA,
                RuntimeIdentity("direct-a"),
                FakeDirectPhysicalEndpoint(fullRelease = greenDirectFullReleaseFacts()),
            ),
        )
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.DopOwned)
    }

    private fun ownedDirect(
        endpoint: FakeDirectPhysicalEndpoint = FakeDirectPhysicalEndpoint(),
    ): Pair<PlaybackIntentLedger, UsbExclusivePlaybackProtocol> {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        protocol.registerAdapter(adapterB)
        protocol.installOwnedFamilyForModel(
            PlaybackFamily.DOP,
            MutationId(1),
            adapterA,
            a,
            RuntimeIdentity("direct-a"),
            facts = "dop128",
            directEndpoint = endpoint,
        )
        protocol.updateApplicationCurrent("A", a.periodUid, a)
        return ledger to protocol
    }

    private fun seekSetup(): Pair<PlaybackIntentLedger, UsbExclusivePlaybackProtocol> {
        val (ledger, protocol) = ownedDirect()
        val mutation = requireNotNull(
            protocol.beginMutation(
                MutationKind.SEEK,
                "A",
                PlaybackFamily.DOP,
                "dop128",
                a,
                causalHandleFactory = { id ->
                    MutationCausalHandle(PlaybackStackId(1), id, adapterA, a, 9_000_000L)
                },
            ),
        )
        assertEquals(MutationKind.SEEK, mutation.kind)
        return ledger to protocol
    }

    private fun freshDirectDestination(): Pair<PlaybackIntentLedger, UsbExclusivePlaybackProtocol> {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "B",
                PlaybackFamily.DOP,
                "dop128",
                b,
                destinationAdapterInstanceId = adapterB,
            ),
        )
        return ledger to protocol
    }

    private fun fresh(): Pair<PlaybackIntentLedger, UsbExclusivePlaybackProtocol> {
        val ledger = PlaybackIntentLedger()
        val protocol = UsbExclusivePlaybackProtocol(
            ledger,
            PlaybackStackId(1),
            OutputTarget.UsbBound(UsbOutputGeneration(9)),
        )
        return ledger to protocol
    }

    private fun completedDirect(permit: DirectStagePermit, resource: String): SideEffectReceipt.Completed =
        SideEffectReceipt.Completed(
            activationId = permit.activationId,
            resourceIdentity = ResourceIdentity(resource),
            facts = "completed:${permit.stage}",
            runtimeIdentity = permit.runtimeIdentity,
        )
}

@RunWith(Parameterized::class)
class UsbDirectFullReleaseRedTableTest(
    private val label: String,
    private val facts: DirectFullReleaseFacts,
) {
    private val a = PlaybackOccurrence("uid-a", 1)
    private val b = PlaybackOccurrence("uid-b", 2)
    private val adapterA = AdapterInstanceId(10)
    private val adapterB = AdapterInstanceId(20)

    @Test
    fun t2RedFactCannotMintTerminalDirectProof() {
        val ledger = PlaybackIntentLedger()
        val protocol = UsbExclusivePlaybackProtocol(
            ledger,
            PlaybackStackId(1),
            OutputTarget.UsbBound(UsbOutputGeneration(9)),
        )
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        protocol.registerAdapter(adapterB)
        protocol.installOwnedFamilyForModel(
            PlaybackFamily.DOP,
            MutationId(1),
            adapterA,
            a,
            RuntimeIdentity("direct-a"),
            facts = "dop128",
            directEndpoint = FakeDirectPhysicalEndpoint(fullRelease = facts),
        )
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "B",
                PlaybackFamily.DOP,
                "dop256",
                b,
                destinationAdapterInstanceId = adapterB,
            ),
        )
        assertFalse(label, facts.isFullyGreen())
        assertFalse(
            label,
            protocol.attachDirectPhysicalEndpoint(
                adapterA,
                RuntimeIdentity("direct-a"),
                FakeDirectPhysicalEndpoint(fullRelease = greenDirectFullReleaseFacts()),
            ),
        )
        assertFalse(label, protocol.completeOwnedDirectRelease(adapterA, RuntimeIdentity("direct-a")))
        assertNull(
            protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapterA,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
                protocol.typedDirectReleased(RuntimeIdentity("direct-a"), a, adapterA, facts),
            ),
        )
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.DopOwned)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun redFacts(): List<Array<Any>> = listOf(
            arrayOf("writer", greenDirectFullReleaseFacts(writerJoined = false)),
            arrayOf("pause-worker", greenDirectFullReleaseFacts(pauseWorkerJoined = false)),
            arrayOf("feeder-staged", greenDirectFullReleaseFacts(feederStagedPendingZero = false)),
            arrayOf("feeder-upstream", greenDirectFullReleaseFacts(feederUpstreamPendingZero = false)),
            arrayOf("feeder-error", greenDirectFullReleaseFacts(feederErrorNull = false)),
            arrayOf("p5-packed", greenDirectFullReleaseFacts(p5PendingPackedZero = false)),
            arrayOf("p5-partial", greenDirectFullReleaseFacts(p5PendingPartialZero = false)),
            arrayOf("p5-half", greenDirectFullReleaseFacts(p5PendingHalfZero = false)),
            arrayOf("native", greenDirectFullReleaseFacts(nativeDestroyed = false)),
            arrayOf("alt", greenDirectFullReleaseFacts(altRestored = false)),
            arrayOf("clock", greenDirectFullReleaseFacts(clockRestored = false)),
            arrayOf("interfaces", greenDirectFullReleaseFacts(interfacesReleased = false)),
            arrayOf("drivers", greenDirectFullReleaseFacts(driversRebound = false)),
        )
    }
}
