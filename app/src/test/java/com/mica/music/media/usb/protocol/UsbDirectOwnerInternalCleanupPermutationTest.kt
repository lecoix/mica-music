package com.mica.music.media.usb.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDirectOwnerInternalCleanupPermutationTest {
    private val b = PlaybackOccurrence("uid-b", 2)
    private val c = PlaybackOccurrence("uid-c", 3)
    private val adapterB = AdapterInstanceId(20)

    @Test
    fun currentPartialRequiresExactCleanupBeforeRetry() {
        val protocol = destination(b, "B")
        val mutation = requireNotNull(protocol.snapshot().mutation)
        val create = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("runtime-b"),
            ),
        )
        val resource = ResourceIdentity("current-partial")
        assertEquals(
            CommitDisposition.CurrentCleanupRequired(resource, CleanupContinuation.RETRY_SAME_MUTATION),
            protocol.commitDirectStage(
                create,
                SideEffectReceipt.PartialNeedsCleanup(create.activationId, resource, "partial", create.runtimeIdentity),
            ),
        )
        assertNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("runtime-b"),
            ),
        )
        assertEquals(CommitDisposition.RetryPendingSameMutation, protocol.completeCleanup(resource))
        assertNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("runtime-b"),
            ),
        )
    }

    @Test
    fun supersededPartialCleanupMustFinishBeforeSuccessorPrepare() {
        val protocol = destination(b, "B")
        val first = requireNotNull(protocol.snapshot().mutation)
        val create = requireNotNull(
            protocol.prepareDirectStage(
                first.mutationId,
                adapterB,
                b,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("runtime-b"),
            ),
        )
        val resource = ResourceIdentity("superseded-partial")
        assertEquals(
            CommitDisposition.CurrentCleanupRequired(resource, CleanupContinuation.RETRY_SAME_MUTATION),
            protocol.commitDirectStage(
                create,
                SideEffectReceipt.PartialNeedsCleanup(create.activationId, resource, "partial", create.runtimeIdentity),
            ),
        )
        protocol.updateApplicationCurrent("C", c.periodUid, c)
        val successor = requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "C",
                PlaybackFamily.DOP,
                "dop128",
                c,
                destinationAdapterInstanceId = adapterB,
            ),
        )
        assertNull(
            protocol.prepareDirectStage(
                successor.mutationId,
                adapterB,
                c,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("runtime-c"),
            ),
        )
        assertEquals(CommitDisposition.StaleNoEffect, protocol.completeCleanup(resource))
        assertNotNull(
            protocol.prepareDirectStage(
                successor.mutationId,
                adapterB,
                c,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("runtime-c"),
            ),
        )
    }

    @Test
    fun retiringPartialCleanupCannotRetryObsoleteActivation() {
        val protocol = destination(b, "B")
        val mutation = requireNotNull(protocol.snapshot().mutation)
        val create = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("runtime-b"),
            ),
        )
        val resource = ResourceIdentity("retiring-partial")
        protocol.commitDirectStage(
            create,
            SideEffectReceipt.PartialNeedsCleanup(create.activationId, resource, "partial", create.runtimeIdentity),
        )
        protocol.beginRetiring()
        assertTrue(protocol.snapshot().lifecycle is ProtocolLifecycle.Retiring)
        assertEquals(CommitDisposition.StaleNoEffect, protocol.completeCleanup(resource))
        assertEquals(ProtocolLifecycle.Retired, protocol.snapshot().lifecycle)
        assertTrue(protocol.snapshot().cleanupRequirements.isEmpty())
        assertTrue(protocol.snapshot().inFlightActivations.isEmpty())
    }

    @Test
    fun terminalWithResourceWaitsForEveryOwnedIdentityThenFailsClosed() {
        val protocol = destination(b, "B")
        val mutation = requireNotNull(protocol.snapshot().mutation)
        val create = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("runtime-b"),
            ),
        )
        val createResource = ResourceIdentity("terminal-create")
        assertNull(
            protocol.commitDirectStage(
                create,
                SideEffectReceipt.Completed(create.activationId, createResource, "created", create.runtimeIdentity),
            ),
        )
        val prefill = requireNotNull(
            protocol.prepareDirectStage(
                mutation.mutationId,
                adapterB,
                b,
                DirectStage.PREFILL,
                RuntimeIdentity("runtime-b"),
            ),
        )
        val failedResource = ResourceIdentity("terminal-prefill")
        assertEquals(
            CommitDisposition.CurrentCleanupRequired(failedResource, CleanupContinuation.TERMINAL),
            protocol.commitDirectStage(
                prefill,
                SideEffectReceipt.TerminalFailure(prefill.activationId, failedResource, "failed", prefill.runtimeIdentity),
            ),
        )
        assertEquals(setOf(createResource, failedResource), protocol.snapshot().cleanupRequirements)
        assertEquals(CommitDisposition.StaleNoEffect, protocol.completeCleanup(failedResource))
        assertEquals(CommitDisposition.TerminalFailure, protocol.completeCleanup(createResource))
        assertTrue(protocol.snapshot().cleanupRequirements.isEmpty())
        assertTrue(protocol.snapshot().inFlightActivations.isEmpty())
    }

    @Test
    fun lateCompletionAfterNewerMutationCannotReviveOldActivation() {
        val protocol = destination(b, "B")
        val first = requireNotNull(protocol.snapshot().mutation)
        val create = requireNotNull(
            protocol.prepareDirectStage(
                first.mutationId,
                adapterB,
                b,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("runtime-b"),
            ),
        )
        protocol.updateApplicationCurrent("C", c.periodUid, c)
        val successor = requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                "C",
                PlaybackFamily.DOP,
                "dop128",
                c,
                destinationAdapterInstanceId = adapterB,
            ),
        )
        val lateResource = ResourceIdentity("late-create")
        assertEquals(
            CommitDisposition.StaleCleanupRequired(lateResource),
            protocol.commitDirectStage(
                create,
                SideEffectReceipt.Completed(create.activationId, lateResource, "late", create.runtimeIdentity),
            ),
        )
        assertEquals(CommitDisposition.StaleNoEffect, protocol.completeCleanup(lateResource))
        assertEquals(
            CommitDisposition.StaleNoEffect,
            protocol.commitDirectStage(
                create,
                SideEffectReceipt.Completed(create.activationId, lateResource, "replay", create.runtimeIdentity),
            ),
        )
        assertNotNull(
            protocol.prepareDirectStage(
                successor.mutationId,
                adapterB,
                c,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("runtime-c"),
            ),
        )
        assertFalse(protocol.snapshot().inFlightActivations.contains(create.activationId))
    }

    private fun destination(occurrence: PlaybackOccurrence, mediaId: String): UsbExclusivePlaybackProtocol {
        val ledger = PlaybackIntentLedger()
        val protocol = UsbExclusivePlaybackProtocol(
            ledger,
            PlaybackStackId(1),
            OutputTarget.UsbBound(UsbOutputGeneration(5)),
        )
        ledger.publish(PlaybackIntent.PLAY)
        protocol.registerAdapter(adapterB)
        protocol.updateApplicationCurrent(mediaId, occurrence.periodUid, occurrence)
        requireNotNull(
            protocol.beginMutation(
                MutationKind.MANUAL,
                mediaId,
                PlaybackFamily.DOP,
                "dop128",
                occurrence,
                destinationAdapterInstanceId = adapterB,
            ),
        )
        return protocol
    }
}
