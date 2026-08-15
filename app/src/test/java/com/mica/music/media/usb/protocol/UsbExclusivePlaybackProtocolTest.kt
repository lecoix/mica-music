package com.mica.music.media.usb.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusivePlaybackProtocolTest {
    private val a = PlaybackOccurrence("uid-a", 1)
    private val b = PlaybackOccurrence("uid-b", 2)
    private val c = PlaybackOccurrence("uid-c", 3)
    private val sameUid1 = PlaybackOccurrence("same", 1)
    private val sameUid2 = PlaybackOccurrence("same", 2)
    private val adapterA = AdapterInstanceId(10)
    private val adapterB = AdapterInstanceId(20)
    private val adapterC = AdapterInstanceId(30)

    @Test
    fun playPausePrepareYieldsNoPermit() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        val mutation = targetPcm(protocol, b, adapterB)
        ledger.publish(PlaybackIntent.PAUSE)

        assertNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
    }

    @Test
    fun playPermitPauseCompleteCommitsCurrentPaused() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        val mutation = targetPcm(protocol, b, adapterB)
        val permit = requireNotNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
        ledger.publish(PlaybackIntent.PAUSE)

        val disposition = protocol.commitPcmConfigure(
            permit,
            SideEffectReceipt.Completed(permit.activationId, ResourceIdentity("pcm-b"), "configured"),
        )

        assertTrue(disposition is CommitDisposition.CurrentPaused)
        val owned = protocol.snapshot().familyOwnership as FamilyOwnership.PcmOwned
        assertTrue(owned.semanticPaused)
        assertFalse(owned.writeLease.tryEnter(b, mutation.mutationId, adapterB, WriteKind.PCM_DATA))
    }

    @Test
    fun playPermitPausePlayCompleteUsesLatestIntent() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        val mutation = targetPcm(protocol, b, adapterB)
        val permit = requireNotNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
        ledger.publish(PlaybackIntent.PAUSE)
        ledger.publish(PlaybackIntent.PLAY)

        val disposition = protocol.commitPcmConfigure(
            permit,
            SideEffectReceipt.Completed(permit.activationId, ResourceIdentity("pcm-b"), "configured"),
        )

        assertTrue(disposition is CommitDisposition.CurrentPlaying)
        val lease = (disposition as CommitDisposition.CurrentPlaying).writeLease
        assertTrue(lease.tryEnter(b, mutation.mutationId, adapterB, WriteKind.PCM_DATA))
        lease.exit()
    }

    @Test
    fun partialNeedsExactCleanupBeforeRetrySameMutation() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        val mutation = targetPcm(protocol, b, adapterB)
        val permit = requireNotNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
        val resource = ResourceIdentity("partial-b")

        val disposition = protocol.commitPcmConfigure(
            permit,
            SideEffectReceipt.PartialNeedsCleanup(permit.activationId, resource, "partial"),
        )
        assertEquals(
            CommitDisposition.CurrentCleanupRequired(resource, CleanupContinuation.RETRY_SAME_MUTATION),
            disposition,
        )
        assertNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
        assertEquals(CommitDisposition.RetryPendingSameMutation, protocol.completeCleanup(resource))
        assertNotNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
    }

    @Test
    fun terminalFailureWithLiveResourceBecomesTerminalOnlyAfterCleanup() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        val mutation = targetPcm(protocol, b, adapterB)
        val permit = requireNotNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
        val resource = ResourceIdentity("terminal-live")

        assertEquals(
            CommitDisposition.CurrentCleanupRequired(resource, CleanupContinuation.TERMINAL),
            protocol.commitPcmConfigure(
                permit,
                SideEffectReceipt.TerminalFailure(permit.activationId, resource, "fatal"),
            ),
        )
        assertNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
        assertEquals(CommitDisposition.TerminalFailure, protocol.completeCleanup(resource))
    }

    @Test
    fun supersededCompletedActivationRequiresIdentityScopedStaleCleanup() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        val bMutation = targetPcm(protocol, b, adapterB)
        val permit = requireNotNull(protocol.preparePcmConfigure(bMutation.mutationId, adapterB, b, "pcm96"))
        protocol.registerAdapter(adapterC)
        protocol.updateApplicationCurrent("C", c.periodUid, c)
        val cMutation = requireNotNull(
            protocol.beginMutation(MutationKind.MANUAL, "C", PlaybackFamily.PCM, "pcm96", c),
        )
        val resource = ResourceIdentity("stale-b")

        assertEquals(
            CommitDisposition.StaleCleanupRequired(resource),
            protocol.commitPcmConfigure(
                permit,
                SideEffectReceipt.Completed(permit.activationId, resource, "configured"),
            ),
        )
        assertNull(protocol.preparePcmConfigure(cMutation.mutationId, adapterC, c, "pcm96"))
        assertEquals(CommitDisposition.StaleNoEffect, protocol.completeCleanup(resource))
        assertNotNull(protocol.preparePcmConfigure(cMutation.mutationId, adapterC, c, "pcm96"))
    }

    @Test
    fun sameOccurrenceSeekMutationMakesOlderActivationStale() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        val first = targetPcm(protocol, b, adapterB)
        val permit = requireNotNull(protocol.preparePcmConfigure(first.mutationId, adapterB, b, "pcm96"))
        val seek = requireNotNull(
            protocol.beginMutation(
                MutationKind.SEEK,
                "B",
                PlaybackFamily.PCM,
                "pcm96",
                b,
                causalHandleFactory = { id -> MutationCausalHandle(PlaybackStackId(1), id, adapterB, b, 9_000_000) },
            ),
        )
        assertNotNull(seek.causalHandle)
        val resource = ResourceIdentity("old-seek")
        assertEquals(
            CommitDisposition.StaleCleanupRequired(resource),
            protocol.commitPcmConfigure(
                permit,
                SideEffectReceipt.Completed(permit.activationId, resource, "configured"),
            ),
        )
    }

    @Test
    fun outputGenerationInvalidatesOldPermitBeforeAnyHardwareRedemption() {
        val ledger = PlaybackIntentLedger()
        val gen5 = OutputTarget.UsbBound(UsbOutputGeneration(5))
        val protocol = UsbExclusivePlaybackProtocol(ledger, PlaybackStackId(1), gen5)
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        ledger.publish(PlaybackIntent.PLAY)
        val mutation = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.PCM, "pcm96", b))
        val permit = requireNotNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))

        protocol.updateOutputTarget(OutputTarget.UsbBound(UsbOutputGeneration(6)))

        assertFalse(protocol.isOutputBindingCurrent(permit.outputTarget))
        val resource = ResourceIdentity("gen5-resource")
        assertEquals(
            CommitDisposition.StaleCleanupRequired(resource),
            protocol.commitPcmConfigure(
                permit,
                SideEffectReceipt.Completed(permit.activationId, resource, "configured"),
            ),
        )
    }

    @Test
    fun retiringKeepsProtocolReachableUntilInFlightReceiptAndCleanupFinish() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        val mutation = targetPcm(protocol, b, adapterB)
        val permit = requireNotNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))

        protocol.beginRetiring()
        assertTrue(protocol.snapshot().lifecycle is ProtocolLifecycle.Retiring)
        assertNull(protocol.beginMutation(MutationKind.MANUAL, "C", PlaybackFamily.PCM, "pcm96", c))
        val resource = ResourceIdentity("retiring")
        assertEquals(
            CommitDisposition.RetiringCleanupRequired(resource),
            protocol.commitPcmConfigure(
                permit,
                SideEffectReceipt.Completed(permit.activationId, resource, "configured"),
            ),
        )
        assertTrue(protocol.snapshot().lifecycle is ProtocolLifecycle.Retiring)
        protocol.completeCleanup(resource)
        assertEquals(ProtocolLifecycle.Retired, protocol.snapshot().lifecycle)
        assertFalse(protocol.registerAdapter(adapterC))
        assertFalse(protocol.observeCandidate(CandidateOccurrence(adapterC, "C", c, PlaybackFamily.PCM, "pcm96")))
    }

    @Test
    fun readAheadCandidateHasZeroAuthorityUntilExactApplicationAdoption() {
        val (_, protocol) = fresh()
        protocol.updateApplicationCurrent("A", a.periodUid, a)
        val candidate = CandidateOccurrence(adapterB, "B", b, PlaybackFamily.PCM, "pcm96")
        assertTrue(protocol.observeCandidate(candidate))
        assertNull(protocol.adoptAutoCandidate("B", b))
        assertEquals(FamilyOwnership.None, protocol.snapshot().familyOwnership)

        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val adopted = requireNotNull(protocol.adoptAutoCandidate("B", b))
        assertEquals(MutationKind.AUTO_NEXT, adopted.kind)
        assertEquals(b, adopted.targetOccurrence)
        assertEquals(FamilyOwnership.None, protocol.snapshot().familyOwnership)
    }

    @Test
    fun readAheadCannotBeRelabeledAfterDifferentTargetSupersedes() {
        val (_, protocol) = fresh()
        protocol.updateApplicationCurrent("A", a.periodUid, a)
        assertTrue(protocol.observeCandidate(CandidateOccurrence(adapterB, "B", b, PlaybackFamily.PCM, "same")))
        protocol.updateApplicationCurrent("C", c.periodUid, c)
        assertNull(protocol.adoptAutoCandidate("B", b))
        val cMutation = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "C", PlaybackFamily.PCM, "same", c))
        assertEquals("C", cMutation.targetMediaId)
        assertEquals(b, protocol.snapshot().candidates.single().occurrence)
    }

    @Test
    fun samePeriodUidStillRequiresExactWindowSequence() {
        val (_, protocol) = fresh()
        protocol.updateApplicationCurrent("A", sameUid1.periodUid, sameUid1)
        assertTrue(protocol.observeCandidate(CandidateOccurrence(adapterB, "A", sameUid2, PlaybackFamily.PCM, "pcm")))
        assertNull(protocol.adoptAutoCandidate("A", sameUid2))
        protocol.updateApplicationCurrent("A", sameUid2.periodUid, sameUid2)
        assertEquals(sameUid2, requireNotNull(protocol.adoptAutoCandidate("A", sameUid2)).targetOccurrence)
    }

    @Test
    fun retainedPcmHandoffRevokesAAndMintsOnlyExactBLeaseWithoutConfigure() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        val install = protocol.installOwnedFamilyForModel(PlaybackFamily.PCM, MutationId(1), adapterA, a, RuntimeIdentity("pcm-runtime"))
        val leaseA = (install as CommitDisposition.CurrentPlaying).writeLease
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(protocol.beginMutation(MutationKind.AUTO_NEXT, "B", PlaybackFamily.PCM, "pcm", b))
        val receipt = requireNotNull(
            protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapterA,
                RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED,
                FamilyProof.PcmRuntimeRetained(RuntimeIdentity("pcm-runtime"), "compatible", "tail-drained"),
            ),
        )
        assertTrue(protocol.acceptSourceRetirement(receipt))
        assertTrue(leaseA.isRevoked())
        val permit = requireNotNull(protocol.prepareRetainedPcmHandoff(mutation.mutationId, adapterB, b, RuntimeIdentity("pcm-runtime")))
        val committed = protocol.commitRetainedPcmHandoff(permit) as CommitDisposition.CurrentPlaying
        val leaseB = committed.writeLease

        assertFalse(leaseA.tryEnter(a, MutationId(1), adapterA, WriteKind.PCM_DATA))
        assertTrue(leaseB.tryEnter(b, mutation.mutationId, adapterB, WriteKind.PCM_DATA))
        leaseB.exit()
        assertFalse(leaseB.tryEnter(a, mutation.mutationId, adapterB, WriteKind.PCM_DATA))
    }

    @Test
    fun pausedRetainedPcmMayCommitOwnershipButCannotWriteUntilPlay() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        protocol.installOwnedFamilyForModel(PlaybackFamily.PCM, MutationId(1), adapterA, a, RuntimeIdentity("pcm-runtime"))
        ledger.publish(PlaybackIntent.PAUSE)
        protocol.adoptLatestIntent()
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.PCM, "pcm", b))
        val receipt = requireNotNull(
            protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapterA,
                RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED,
                FamilyProof.PcmRuntimeRetained(RuntimeIdentity("pcm-runtime"), "compatible", "tail-drained"),
            ),
        )
        assertTrue(receipt.semanticPausedAtRetirement)
        assertTrue(protocol.acceptSourceRetirement(receipt))
        val permit = requireNotNull(protocol.prepareRetainedPcmHandoff(mutation.mutationId, adapterB, b, RuntimeIdentity("pcm-runtime")))
        val disposition = protocol.commitRetainedPcmHandoff(permit) as CommitDisposition.CurrentPaused
        assertFalse(disposition.writeLease.tryEnter(b, mutation.mutationId, adapterB, WriteKind.PCM_DATA))

        ledger.publish(PlaybackIntent.PLAY)
        protocol.adoptLatestIntent()
        assertTrue(disposition.writeLease.tryEnter(b, mutation.mutationId, adapterB, WriteKind.PCM_DATA))
        disposition.writeLease.exit()
    }

    @Test
    fun retainedBPermitCannotInheritCMutation() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        protocol.installOwnedFamilyForModel(PlaybackFamily.PCM, MutationId(1), adapterA, a, RuntimeIdentity("pcm-runtime"))
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val bMutation = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.PCM, "pcm", b))
        val receipt = requireNotNull(protocol.mintRetirementReceipt(bMutation.mutationId, adapterA, RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED, FamilyProof.PcmRuntimeRetained(RuntimeIdentity("pcm-runtime"), "compatible", "tail")))
        assertTrue(protocol.acceptSourceRetirement(receipt))
        val permitB = requireNotNull(protocol.prepareRetainedPcmHandoff(bMutation.mutationId, adapterB, b, RuntimeIdentity("pcm-runtime")))
        protocol.registerAdapter(adapterC)
        protocol.updateApplicationCurrent("C", c.periodUid, c)
        protocol.beginMutation(MutationKind.MANUAL, "C", PlaybackFamily.PCM, "pcm", c)

        assertEquals(CommitDisposition.StaleNoEffect, protocol.commitRetainedPcmHandoff(permitB))
        assertFalse(permitB.occurrence == c)
    }

    @Test
    fun pausedDopToPcmDefersConfigureUntilLedgerPlay() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        protocol.installOwnedFamilyForModel(PlaybackFamily.DOP, MutationId(1), adapterA, a, RuntimeIdentity("dop-a"))
        ledger.publish(PlaybackIntent.PAUSE)
        protocol.adoptLatestIntent()
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.PCM, "pcm96", b))
        val receipt = requireNotNull(protocol.mintRetirementReceipt(mutation.mutationId, adapterA, RetirementScope.FAMILY_RUNTIME_RELEASED, FamilyProof.DirectFamilyReleased("zero-pending")))
        assertTrue(receipt.semanticPausedAtRetirement)
        assertTrue(protocol.acceptSourceRetirement(receipt))
        assertNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
        ledger.publish(PlaybackIntent.PLAY)
        assertNotNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
    }

    @Test
    fun pausedPcmToDopForbidsFreshCreateUntilPlay() {
        val (ledger, protocol) = fresh(OutputTarget.UsbBound(UsbOutputGeneration(9)))
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        protocol.installOwnedFamilyForModel(PlaybackFamily.PCM, MutationId(1), adapterA, a, RuntimeIdentity("pcm-a"))
        ledger.publish(PlaybackIntent.PAUSE)
        protocol.adoptLatestIntent()
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.DOP, "dop128", b))
        val receipt = requireNotNull(protocol.mintRetirementReceipt(mutation.mutationId, adapterA, RetirementScope.FAMILY_RUNTIME_RELEASED, FamilyProof.StackReleased("pcm-released")))
        assertTrue(protocol.acceptSourceRetirement(receipt))
        assertNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.CREATE_RUNTIME, RuntimeIdentity("dop-b")))
        ledger.publish(PlaybackIntent.PLAY)
        assertNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.CREATE_RUNTIME, RuntimeIdentity("dop-b")))
    }

    @Test
    fun samePlanDopMayRetainRuntimeButRateChangeRequiresFullRelease() {
        val (ledger, protocol) = fresh(OutputTarget.UsbBound(UsbOutputGeneration(9)))
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        protocol.installOwnedFamilyForModel(PlaybackFamily.DOP, MutationId(1), adapterA, a, RuntimeIdentity("carrier"))
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val samePlan = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.DOP, "dop128", b))
        val retained = requireNotNull(protocol.mintRetirementReceipt(samePlan.mutationId, adapterA, RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED, FamilyProof.DirectRuntimeRetained(RuntimeIdentity("carrier"), "same-plan")))
        assertTrue(protocol.acceptSourceRetirement(retained))
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.DopOwned)

        protocol.updateApplicationCurrent("C", c.periodUid, c)
        val changed = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "C", PlaybackFamily.DOP, "dop64", c))
        val released = requireNotNull(protocol.mintRetirementReceipt(changed.mutationId, adapterA, RetirementScope.FAMILY_RUNTIME_RELEASED, FamilyProof.DirectFamilyReleased("rate-change")))
        assertTrue(protocol.acceptSourceRetirement(released))
        assertEquals(FamilyOwnership.None, protocol.snapshot().familyOwnership)
    }

    @Test
    fun directFreshStagesRequireCurrentPlayAndExactStartedAdapter() {
        val (ledger, protocol) = fresh(OutputTarget.UsbBound(UsbOutputGeneration(9)))
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.DOP, "dop128", b))

        val create = requireNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.CREATE_RUNTIME, RuntimeIdentity("dop-b")))
        ledger.publish(PlaybackIntent.PAUSE)
        assertNull(protocol.commitDirectStage(create))
        ledger.publish(PlaybackIntent.PLAY)
        val create2 = requireNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.CREATE_RUNTIME, RuntimeIdentity("dop-b")))
        assertNull(protocol.commitDirectStage(create2))
        val prefill = requireNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.PREFILL, RuntimeIdentity("dop-b")))
        assertNull(protocol.commitDirectStage(prefill))
        assertNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.ARM, RuntimeIdentity("dop-b")))
        assertTrue(protocol.observeAdapterStarted(adapterB, b))
        val arm = requireNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.ARM, RuntimeIdentity("dop-b")))
        assertNull(protocol.commitDirectStage(arm))
        val accept = requireNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.SOURCE_ACCEPT, RuntimeIdentity("dop-b")))
        assertTrue(protocol.commitDirectStage(accept) is CommitDisposition.CurrentPlaying)
    }

    @Test
    fun staleStartedAdapterCannotAuthorizeReplacementSameOccurrence() {
        val (ledger, protocol) = fresh(OutputTarget.UsbBound(UsbOutputGeneration(9)))
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.DOP, "dop128", b))
        assertTrue(protocol.observeAdapterStarted(adapterA, b))
        val create = requireNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.CREATE_RUNTIME, RuntimeIdentity("runtime-b")))
        protocol.commitDirectStage(create)
        val prefill = requireNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.PREFILL, RuntimeIdentity("runtime-b")))
        protocol.commitDirectStage(prefill)
        assertNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.ARM, RuntimeIdentity("runtime-b")))
        assertTrue(protocol.observeAdapterStarted(adapterB, b))
        assertNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.ARM, RuntimeIdentity("runtime-b")))
    }

    @Test
    fun fullyCurrentDirectPauseKeepsSingleOwnershipAndSwitchesContentToGap() {
        val (ledger, protocol) = fresh(OutputTarget.UsbBound(UsbOutputGeneration(9)))
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        val disposition = protocol.installOwnedFamilyForModel(PlaybackFamily.DOP, MutationId(1), adapterA, a, RuntimeIdentity("carrier")) as CommitDisposition.CurrentPlaying
        val lease = disposition.writeLease
        assertTrue(lease.tryEnter(a, MutationId(1), adapterA, WriteKind.DOP_CONTENT))
        lease.exit()

        ledger.publish(PlaybackIntent.PAUSE)
        protocol.adoptLatestIntent()

        assertFalse(lease.tryEnter(a, MutationId(1), adapterA, WriteKind.DOP_CONTENT))
        assertTrue(lease.tryEnter(a, MutationId(1), adapterA, WriteKind.DOP_GAP))
        lease.exit()
        assertEquals(disposition.familyOwnershipId, (protocol.snapshot().familyOwnership as FamilyOwnership.DopOwned).ownershipId)
    }

    @Test
    fun directSeekRequiresCarrierBarrierBeforeSourceAccept() {
        val (ledger, protocol) = fresh(OutputTarget.UsbBound(UsbOutputGeneration(9)))
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(protocol.beginMutation(MutationKind.SEEK, "B", PlaybackFamily.DOP, "dop128", b))
        val create = requireNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.CREATE_RUNTIME, RuntimeIdentity("seek-runtime"), carrierBarrierSatisfied = false))
        protocol.commitDirectStage(create)
        val prefill = requireNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.PREFILL, RuntimeIdentity("seek-runtime")))
        protocol.commitDirectStage(prefill)
        protocol.observeAdapterStarted(adapterB, b)
        val arm = requireNotNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.ARM, RuntimeIdentity("seek-runtime")))
        protocol.commitDirectStage(arm)
        assertNull(protocol.prepareDirectStage(mutation.mutationId, adapterB, b, DirectStage.SOURCE_ACCEPT, RuntimeIdentity("seek-runtime")))
    }

    @Test
    fun technicalRestoreAlwaysUsesLatestLedgerSnapshot() {
        val (ledger, protocol) = fresh()
        val play = ledger.publish(PlaybackIntent.PLAY)
        protocol.adoptLatestIntent()
        val fence = protocol.captureTechnicalIntentFence()
        assertEquals(play.revision, fence)
        val pause = ledger.publish(PlaybackIntent.PAUSE)

        assertEquals(pause, protocol.restoreAfterTechnicalQuiesce(fence))
        assertEquals(PlaybackIntent.PAUSE, protocol.snapshot().adoptedIntent.desired)
    }

    @Test
    fun outputTargetsAreAlgebraicallyDistinctAndUnavailableCannotActivate() {
        val ledger = PlaybackIntentLedger()
        ledger.publish(PlaybackIntent.PLAY)
        val shared = UsbExclusivePlaybackProtocol(ledger, PlaybackStackId(1), OutputTarget.SharedPcm)
        shared.registerAdapter(adapterB)
        shared.updateApplicationCurrent("B", b.periodUid, b)
        val sharedMutation = requireNotNull(shared.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.PCM, "pcm96", b))
        assertNotNull(shared.preparePcmConfigure(sharedMutation.mutationId, adapterB, b, "pcm96"))

        val usb = UsbExclusivePlaybackProtocol(ledger, PlaybackStackId(2), OutputTarget.UsbBound(UsbOutputGeneration(7)))
        usb.registerAdapter(adapterB)
        usb.updateApplicationCurrent("B", b.periodUid, b)
        val usbMutation = requireNotNull(usb.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.PCM, "pcm96", b))
        assertTrue(requireNotNull(usb.preparePcmConfigure(usbMutation.mutationId, adapterB, b, "pcm96")).outputTarget is OutputTarget.UsbBound)

        val unavailable = UsbExclusivePlaybackProtocol(ledger, PlaybackStackId(3), OutputTarget.Unavailable)
        unavailable.registerAdapter(adapterB)
        unavailable.updateApplicationCurrent("B", b.periodUid, b)
        val unavailableMutation = requireNotNull(unavailable.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.PCM, "pcm96", b))
        assertNull(unavailable.preparePcmConfigure(unavailableMutation.mutationId, adapterB, b, "pcm96"))
    }

    @Test
    fun sourceRetirementReceiptMustMatchExactOwnershipAndDrainedLease() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterA)
        val installed = protocol.installOwnedFamilyForModel(PlaybackFamily.PCM, MutationId(1), adapterA, a, RuntimeIdentity("pcm-a")) as CommitDisposition.CurrentPlaying
        assertTrue(installed.writeLease.tryEnter(a, MutationId(1), adapterA, WriteKind.PCM_DATA))
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.PCM, "pcm96", b))
        assertNull(protocol.mintRetirementReceipt(mutation.mutationId, adapterA, RetirementScope.FAMILY_RUNTIME_RELEASED, FamilyProof.StackReleased("closed")))
        installed.writeLease.exit()
        val receipt = requireNotNull(protocol.mintRetirementReceipt(mutation.mutationId, adapterA, RetirementScope.FAMILY_RUNTIME_RELEASED, FamilyProof.StackReleased("closed")))
        val wrong = receipt.copy(sourceFamilyOwnershipId = FamilyOwnershipId(999))
        assertFalse(protocol.acceptSourceRetirement(wrong))
        assertTrue(protocol.acceptSourceRetirement(receipt))
    }

    private fun fresh(output: OutputTarget = OutputTarget.SharedPcm): Pair<PlaybackIntentLedger, UsbExclusivePlaybackProtocol> {
        val ledger = PlaybackIntentLedger()
        val protocol = UsbExclusivePlaybackProtocol(ledger, PlaybackStackId(1), output)
        protocol.registerAdapter(adapterB)
        return ledger to protocol
    }

    private fun targetPcm(
        protocol: UsbExclusivePlaybackProtocol,
        occurrence: PlaybackOccurrence,
        adapter: AdapterInstanceId,
    ): MutationEpoch {
        protocol.registerAdapter(adapter)
        protocol.updateApplicationCurrent("B", occurrence.periodUid, occurrence)
        return requireNotNull(protocol.beginMutation(MutationKind.MANUAL, "B", PlaybackFamily.PCM, "pcm96", occurrence))
    }
}
