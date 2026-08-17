package com.mica.music.media.usb.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPcmPhysicalRetirementProofTest {
    private val a = PlaybackOccurrence("pcm-a", 1)
    private val b = PlaybackOccurrence("pcm-b", 2)
    private val c = PlaybackOccurrence("pcm-c", 3)
    private val adapterA = AdapterInstanceId(101)
    private val adapterB = AdapterInstanceId(102)
    private val adapterC = AdapterInstanceId(103)
    private val geometry = TEST_PCM_GEOMETRY
    private val changedGeometry = TEST_PCM_GEOMETRY.copy(sampleRate = 192_000)

    @Test
    fun p1_fullReleaseNeedsFrozenPermitAndTypedDelegateCompletionProof() {
        val (_, protocol, mutation) = seededTransition(b)
        val permit = requireNotNull(
            protocol.preparePcmSourceRetirement(
                mutation.mutationId,
                adapterA,
                b,
                geometry,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
            ),
        )
        assertNull(
            protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapterA,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
                FamilyProof.StackReleased("generic-nonblank-is-not-physical-authority"),
            ),
        )
        assertTrue(protocol.completePcmRetirement(permit, released(permit)))
        val retirement = requireNotNull(protocol.snapshot().mutation?.sourceRetirement)
        assertTrue(retirement.familyProof is FamilyProof.PcmFamilyReleased)
        assertEquals(FamilyOwnership.None, protocol.snapshot().familyOwnership)
    }

    @Test
    fun p2_projectionAlreadyOnBDoesNotRewriteFrozenAIdentity() {
        val (_, protocol, mutation) = seededTransition(b)
        val permit = requireNotNull(
            protocol.preparePcmSourceRetirement(
                mutation.mutationId,
                adapterA,
                b,
                geometry,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
            ),
        )
        assertEquals(a, permit.source.occurrence)
        assertEquals(adapterA, permit.source.adapterInstanceId)
        assertEquals(RuntimeIdentity("pcm:runtime-a"), permit.source.runtimeIdentity)
        assertEquals(OutputTarget.SharedPcm, permit.source.outputTarget)
        assertEquals(geometry, permit.source.geometry)
        assertEquals(b, protocol.snapshot().applicationCurrent.occurrence)
        assertTrue(protocol.completePcmRetirement(permit, released(permit)))
        assertEquals(a, protocol.snapshot().mutation?.sourceRetirement?.sourceOccurrence)
    }

    @Test
    fun p3_missingOrFailedDelegateProofCannotRetirePhysicalPcm() {
        val (_, protocol, mutation) = seededTransition(b)
        val permit = requireNotNull(
            protocol.preparePcmSourceRetirement(
                mutation.mutationId,
                adapterA,
                b,
                geometry,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
            ),
        )
        assertFalse(protocol.completePcmRetirement(permit, released(permit, sequence = 0L)))
        assertNull(protocol.snapshot().mutation?.sourceRetirement)
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.PcmOwned)
    }

    @Test
    fun p4_enteredWriterMustDrainBeforeRetirementPermitExists() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        val playing = installA(protocol) as CommitDisposition.CurrentPlaying
        assertTrue(playing.writeLease.tryEnter(a, MutationId(1), adapterA, WriteKind.PCM_DATA))
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", b.periodUid, b)
        val mutation = requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "B",
                PlaybackFamily.PCM,
                "pcm96",
                b,
                destinationAdapterInstanceId = adapterB,
            ),
        )
        assertNull(
            protocol.preparePcmSourceRetirement(
                mutation.mutationId,
                adapterA,
                b,
                geometry,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
            ),
        )
        assertTrue(playing.writeLease.isRevoked())
        playing.writeLease.exit()
        assertNotNull(
            protocol.preparePcmSourceRetirement(
                mutation.mutationId,
                adapterA,
                b,
                geometry,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
            ),
        )
    }

    @Test
    fun p5_leaseDrainAloneCannotAdvancePcmOwnedToRetired() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        installA(protocol)
        protocol.beginRetiring()
        assertTrue(protocol.snapshot().lifecycle is ProtocolLifecycle.Retiring)
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.PcmOwned)
        val permit = requireNotNull(protocol.prepareRetiringPcmRuntimeRelease(adapterA))
        assertTrue(protocol.snapshot().lifecycle is ProtocolLifecycle.Retiring)
        assertTrue(protocol.completePcmRetirement(permit, released(permit)))
        assertEquals(ProtocolLifecycle.Retired, protocol.snapshot().lifecycle)
    }

    @Test
    fun p6_wrongFrozenRuntimeIdentityIsRejectedFailClosed() {
        val (_, protocol, mutation) = seededTransition(b)
        val permit = requireNotNull(
            protocol.preparePcmSourceRetirement(
                mutation.mutationId,
                adapterA,
                b,
                geometry,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
            ),
        )
        assertFalse(
            protocol.completePcmRetirement(
                permit,
                released(permit).copy(runtimeIdentity = RuntimeIdentity("pcm:wrong-runtime")),
            ),
        )
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.PcmOwned)
        assertTrue(protocol.completePcmRetirement(permit, released(permit)))
    }

    @Test
    fun p7_compatibleRetainedHandoffNeedsTypedGeometryAndTailOrderingProof() {
        val (_, protocol, mutation) = seededTransition(b)
        val retirement = requireNotNull(
            protocol.preparePcmSourceRetirement(
                mutation.mutationId,
                adapterA,
                b,
                geometry,
                RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED,
            ),
        )
        assertTrue(protocol.completePcmRetirement(retirement, retained(retirement, b)))
        val handoff = requireNotNull(
            protocol.prepareRetainedPcmHandoff(
                mutation.mutationId,
                adapterB,
                b,
                retirement.source.runtimeIdentity,
            ),
        )
        val committed = protocol.commitRetainedPcmHandoff(handoff) as CommitDisposition.CurrentPlaying
        assertEquals(b, committed.writeLease.identity.occurrence)
        assertEquals(adapterB, committed.writeLease.identity.adapterInstanceId)
    }

    @Test
    fun p8_incompatibleGeometryCannotRetainButCanUseFullReleasePath() {
        val (_, protocol, mutation) = seededTransition(b)
        assertNull(
            protocol.preparePcmSourceRetirement(
                mutation.mutationId,
                adapterA,
                b,
                changedGeometry,
                RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED,
            ),
        )
        val release = requireNotNull(
            protocol.preparePcmSourceRetirement(
                mutation.mutationId,
                adapterA,
                b,
                changedGeometry,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
            ),
        )
        assertTrue(protocol.completePcmRetirement(release, released(release)))
        assertNotNull(protocol.preparePcmConfigure(mutation.mutationId, adapterB, b, "pcm96"))
    }

    @Test
    fun p9_supersededBRetirementPermitCannotTransferAuthorityIntoC() {
        val (_, protocol, mutationB) = seededTransition(b)
        val oldPermit = requireNotNull(
            protocol.preparePcmSourceRetirement(
                mutationB.mutationId,
                adapterA,
                b,
                geometry,
                RetirementScope.FAMILY_RUNTIME_RELEASED,
            ),
        )
        protocol.registerAdapter(adapterC)
        protocol.updateApplicationCurrent("C", c.periodUid, c)
        val mutationC = requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "C",
                PlaybackFamily.PCM,
                "pcm96",
                c,
                destinationAdapterInstanceId = adapterC,
            ),
        )
        assertFalse(protocol.completePcmRetirement(oldPermit, released(oldPermit)))
        assertEquals(mutationC.mutationId, protocol.snapshot().mutation?.mutationId)
        assertNull(protocol.snapshot().mutation?.sourceRetirement)
    }

    @Test
    fun p10_pausedRetainedHandoffCommitsButCannotAdmitPcmWriteUntilPlay() {
        val (ledger, protocol, mutation) = seededTransition(b)
        ledger.publish(PlaybackIntent.PAUSE)
        protocol.adoptLatestIntent()
        val retirement = requireNotNull(
            protocol.preparePcmSourceRetirement(
                mutation.mutationId,
                adapterA,
                b,
                geometry,
                RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED,
            ),
        )
        assertTrue(protocol.completePcmRetirement(retirement, retained(retirement, b)))
        val handoff = requireNotNull(
            protocol.prepareRetainedPcmHandoff(
                mutation.mutationId,
                adapterB,
                b,
                retirement.source.runtimeIdentity,
            ),
        )
        val committed = protocol.commitRetainedPcmHandoff(handoff) as CommitDisposition.CurrentPaused
        assertFalse(committed.writeLease.tryEnter(b, mutation.mutationId, adapterB, WriteKind.PCM_DATA))
        ledger.publish(PlaybackIntent.PLAY)
        protocol.adoptLatestIntent()
        assertTrue(committed.writeLease.tryEnter(b, mutation.mutationId, adapterB, WriteKind.PCM_DATA))
        committed.writeLease.exit()
    }

    @Test
    fun stackTeardownRejectsForgedPermitMatchingOnlyRuntimeAndGeometry() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        installA(protocol)
        protocol.beginRetiring()
        val exact = requireNotNull(protocol.prepareRetiringPcmRuntimeRelease(adapterA))
        val forged = exact.copy(
            retiringMutationId = MutationId(999),
            source = exact.source.copy(
                familyOwnershipId = FamilyOwnershipId(999),
                mutationId = MutationId(999),
                occurrence = PlaybackOccurrence("forged-pcm", 99),
                adapterInstanceId = adapterB,
                outputTarget = OutputTarget.UsbBound(UsbOutputGeneration(9)),
            ),
            targetOccurrence = b,
            targetGeometry = geometry,
        )
        assertEquals(exact.source.runtimeIdentity, forged.source.runtimeIdentity)
        assertEquals(exact.source.geometry, forged.source.geometry)
        assertFalse(protocol.completePcmRetirement(forged, released(exact)))
        assertTrue(protocol.snapshot().lifecycle is ProtocolLifecycle.Retiring)
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.PcmOwned)
        assertTrue(protocol.completePcmRetirement(exact, released(exact)))
        assertEquals(ProtocolLifecycle.Retired, protocol.snapshot().lifecycle)
    }

    @Test
    fun stackTeardownRejectsWrongOwnershipOccurrenceMutationAndOutput() {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        installA(protocol)
        protocol.beginRetiring()
        val exact = requireNotNull(protocol.prepareRetiringPcmRuntimeRelease(adapterA))
        val proof = released(exact)
        assertFalse(
            protocol.completePcmRetirement(
                exact.copy(source = exact.source.copy(familyOwnershipId = FamilyOwnershipId(0))),
                proof,
            ),
        )
        assertFalse(
            protocol.completePcmRetirement(
                exact.copy(source = exact.source.copy(occurrence = b)),
                proof,
            ),
        )
        assertFalse(
            protocol.completePcmRetirement(
                exact.copy(
                    retiringMutationId = MutationId(0),
                    source = exact.source.copy(mutationId = MutationId(0)),
                ),
                proof,
            ),
        )
        assertFalse(
            protocol.completePcmRetirement(
                exact.copy(source = exact.source.copy(outputTarget = OutputTarget.Unavailable)),
                proof,
            ),
        )
        assertFalse(
            protocol.completePcmRetirement(
                exact.copy(source = exact.source.copy(adapterInstanceId = adapterB)),
                proof,
            ),
        )
        assertFalse(
            protocol.completePcmRetirement(
                exact.copy(targetOccurrence = a, targetGeometry = geometry),
                proof,
            ),
        )
        assertTrue(protocol.snapshot().lifecycle is ProtocolLifecycle.Retiring)
        assertTrue(protocol.snapshot().familyOwnership is FamilyOwnership.PcmOwned)
        assertTrue(protocol.completePcmRetirement(exact, proof))
        assertEquals(ProtocolLifecycle.Retired, protocol.snapshot().lifecycle)
    }

    private fun fresh(): Pair<PlaybackIntentLedger, UsbExclusivePlaybackProtocol> {
        val ledger = PlaybackIntentLedger()
        return ledger to UsbExclusivePlaybackProtocol(ledger, PlaybackStackId(77), OutputTarget.SharedPcm)
    }

    private fun installA(protocol: UsbExclusivePlaybackProtocol): CommitDisposition {
        protocol.registerAdapter(adapterA)
        return protocol.installOwnedFamilyForModel(
            PlaybackFamily.PCM,
            MutationId(1),
            adapterA,
            a,
            RuntimeIdentity("pcm:runtime-a"),
            facts = "pcm96",
            geometry = geometry,
        )
    }

    private fun seededTransition(
        target: PlaybackOccurrence,
    ): Triple<PlaybackIntentLedger, UsbExclusivePlaybackProtocol, MutationEpoch> {
        val (ledger, protocol) = fresh()
        ledger.publish(PlaybackIntent.PLAY)
        installA(protocol)
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent("B", target.periodUid, target)
        val mutation = requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "B",
                PlaybackFamily.PCM,
                "pcm96",
                target,
                destinationAdapterInstanceId = adapterB,
            ),
        )
        return Triple(ledger, protocol, mutation)
    }

    private fun released(
        permit: PcmRetirementPermit,
        sequence: Long = 1L,
    ): FamilyProof.PcmFamilyReleased = FamilyProof.PcmFamilyReleased(
        runtimeIdentity = permit.source.runtimeIdentity,
        sourceGeometry = permit.source.geometry,
        terminal = PcmDelegateTerminal.RESET_COMPLETED,
        sinkBoundarySequence = sequence,
    )

    private fun retained(
        permit: PcmRetirementPermit,
        target: PlaybackOccurrence,
    ): FamilyProof.PcmRuntimeRetained = FamilyProof.PcmRuntimeRetained(
        runtimeIdentity = permit.source.runtimeIdentity,
        sourceGeometry = permit.source.geometry,
        targetGeometry = requireNotNull(permit.targetGeometry),
        tailOrdering = PcmTailOrderingProof(
            sourceOccurrence = permit.source.occurrence,
            targetOccurrence = target,
            sinkBoundarySequence = 1L,
        ),
    )
}
